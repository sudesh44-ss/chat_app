package database

import (
	"context"
	"fmt"
	"log"

	"github.com/jackc/pgx/v5/pgxpool"
	"nexa/wire/internal/identity"
)

// BackfillPhoneProtection computes phone_hmac/phone_encrypted for accounts that
// still only have a plaintext phone_number. It is idempotent and safe to run on
// every startup: once every row is backfilled it performs a single cheap query.
//
// This is the bridge migration described in migration 007. The plaintext column
// is intentionally not dropped here; dropping it is a separate, reviewed data
// retention change.
func BackfillPhoneProtection(ctx context.Context, pool *pgxpool.Pool) (int, error) {
	rows, err := pool.Query(ctx, `
		SELECT nx_id, phone_number
		FROM accounts
		WHERE phone_number IS NOT NULL
		  AND phone_number <> ''
		  AND (phone_hmac IS NULL OR phone_hmac = '')
	`)
	if err != nil {
		return 0, fmt.Errorf("phone backfill query failed: %w", err)
	}

	type pendingRow struct {
		nxID  string
		phone string
	}
	pending := make([]pendingRow, 0)
	for rows.Next() {
		var r pendingRow
		if err := rows.Scan(&r.nxID, &r.phone); err == nil {
			pending = append(pending, r)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, fmt.Errorf("phone backfill read failed: %w", err)
	}

	updated := 0
	for _, row := range pending {
		hmacHex, err := identity.PhoneHMAC(row.phone)
		if err != nil {
			log.Printf("[Database] Phone backfill unavailable: %v", err)
			return updated, err
		}
		encrypted, err := identity.EncryptPhone(row.phone)
		if err != nil {
			return updated, err
		}

		if _, err := pool.Exec(ctx, `
			UPDATE accounts
			SET phone_hmac = $2, phone_encrypted = $3
			WHERE nx_id = $1 AND (phone_hmac IS NULL OR phone_hmac = '')
		`, row.nxID, hmacHex, encrypted); err != nil {
			// A duplicate HMAC means the plaintext unique index and the HMAC
			// index disagree. Log and continue so one bad row cannot block the
			// whole startup; the unique constraint still protects the data.
			log.Printf("[Database] Phone backfill skipped %s: %v", row.nxID, err)
			continue
		}
		updated++
	}

	if updated > 0 {
		log.Printf("[Database] Backfilled phone protection for %d account(s).", updated)
	}
	return updated, nil
}
