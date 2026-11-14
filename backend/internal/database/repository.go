package database

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/jackc/pgx/v5/pgxpool"
	"nexa/wire/internal/identity"
)

// PostgresRepository implements identity.IdentityRepository backed by PostgreSQL.
type PostgresRepository struct {
	pool *pgxpool.Pool
}

func NewPostgresRepository(pool *pgxpool.Pool) *PostgresRepository {
	return &PostgresRepository{pool: pool}
}

// Ensure interface compliance at compile time
var _ identity.IdentityRepository = (*PostgresRepository)(nil)

// AllocateNxID allocates a unique random NX-ID conforming to the locked NX-XXXXXXXX format.
// If the public key is already registered to an existing account, that stable NX-ID is returned.
func (r *PostgresRepository) AllocateNxID(pubKeyHex string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// 1. Stability: check if this public key is already assigned an NX-ID
	if pubKeyHex != "" {
		var existingNx string
		err := r.pool.QueryRow(ctx, "SELECT nx_id FROM accounts WHERE public_key_hex = $1 LIMIT 1", pubKeyHex).Scan(&existingNx)
		if err == nil && existingNx != "" {
			return existingNx, nil
		}
	}

	// 2. Generate random NX-XXXXXXXX with collision check and retry
	for attempt := 0; attempt < 10; attempt++ {
		candidateNx, err := identity.GenerateRandomNxID()
		if err != nil {
			return "", err
		}

		var exists bool
		err = r.pool.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM accounts WHERE nx_id = $1)", candidateNx).Scan(&exists)
		if err != nil {
			return "", fmt.Errorf("failed to query nx_id collision: %w", err)
		}

		if !exists {
			return candidateNx, nil
		}
	}

	return "", fmt.Errorf("failed to allocate unique random NX-ID after 10 attempts")
}

func (r *PostgresRepository) CreateAccount(account *identity.UserAccount) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if account.PhoneNumber != "" {
		hmacHex, err := identity.PhoneHMAC(account.PhoneNumber)
		if err != nil {
			return err
		}
		encrypted, err := identity.EncryptPhone(account.PhoneNumber)
		if err != nil {
			return err
		}
		account.PhoneHMAC = hmacHex
		account.PhoneEncrypted = encrypted
	}

	query := `
		INSERT INTO accounts (
			id, nx_id, public_key_hex, dh_key_hex, phone_number, phone_hmac, phone_encrypted,
			phone_verified, is_discoverable_by_phone, google_id, supabase_user_id,
			email, profile_name, avatar_url, bio, gender, age, created_at, updated_at
		) VALUES (
			$1, $2, $3, $4, NULLIF($5, ''), NULLIF($6, ''), NULLIF($7, ''),
			$8, $9, NULLIF($10, ''), NULLIF($11, ''),
			NULLIF($12, ''), $13, $14, $15, $16, $17, $18, $19
		);
	`

	_, err := r.pool.Exec(ctx, query,
		account.ID, account.NxID, account.PublicKeyHex, account.DhKeyHex, account.PhoneNumber,
		account.PhoneHMAC, account.PhoneEncrypted,
		account.PhoneVerified, account.IsDiscoverableByPhone, account.GoogleID, account.SupabaseUserID,
		account.Email, account.ProfileName, account.AvatarUrl, account.Bio, account.Gender, account.Age,
		account.CreatedAt, account.UpdatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to insert account %s: %w", account.NxID, err)
	}

	return nil
}

const accountSelectFields = `
	id, nx_id, public_key_hex, COALESCE(dh_key_hex, ''), COALESCE(phone_number, ''),
	COALESCE(phone_hmac, ''), COALESCE(phone_encrypted, ''),
	phone_verified, is_discoverable_by_phone, COALESCE(google_id, ''), COALESCE(supabase_user_id, ''),
	COALESCE(email, ''), profile_name, COALESCE(avatar_url, ''), COALESCE(bio, ''),
	COALESCE(gender, ''), age, created_at, updated_at
`

func scanAccount(row pgx.Row) (*identity.UserAccount, error) {
	var a identity.UserAccount
	err := row.Scan(
		&a.ID, &a.NxID, &a.PublicKeyHex, &a.DhKeyHex, &a.PhoneNumber,
		&a.PhoneHMAC, &a.PhoneEncrypted,
		&a.PhoneVerified, &a.IsDiscoverableByPhone, &a.GoogleID, &a.SupabaseUserID,
		&a.Email, &a.ProfileName, &a.AvatarUrl, &a.Bio,
		&a.Gender, &a.Age, &a.CreatedAt, &a.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}
	return &a, nil
}

func (r *PostgresRepository) GetAccountByNxID(nxID string) (*identity.UserAccount, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := "SELECT " + accountSelectFields + " FROM accounts WHERE nx_id = $1 LIMIT 1"
	acc, err := scanAccount(r.pool.QueryRow(ctx, query, nxID))
	if err != nil {
		return nil, false
	}
	return acc, true
}

func (r *PostgresRepository) GetAccountByPhone(phone string) (*identity.UserAccount, bool) {
	if phone == "" {
		return nil, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// Prefer the HMAC index. The plaintext column is consulted only as a
	// transitional fallback for rows written before phone protection existed.
	if hmacHex, err := identity.PhoneHMAC(phone); err == nil {
		query := "SELECT " + accountSelectFields + " FROM accounts WHERE phone_hmac = $1 LIMIT 1"
		if acc, err := scanAccount(r.pool.QueryRow(ctx, query, hmacHex)); err == nil {
			return acc, true
		}
	}

	query := "SELECT " + accountSelectFields + " FROM accounts WHERE phone_number = $1 LIMIT 1"
	acc, err := scanAccount(r.pool.QueryRow(ctx, query, phone))
	if err != nil {
		return nil, false
	}
	return acc, true
}

func (r *PostgresRepository) GetAccountByGoogleID(googleID string) (*identity.UserAccount, bool) {
	if googleID == "" {
		return nil, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := "SELECT " + accountSelectFields + " FROM accounts WHERE google_id = $1 LIMIT 1"
	acc, err := scanAccount(r.pool.QueryRow(ctx, query, googleID))
	if err != nil {
		return nil, false
	}
	return acc, true
}

func (r *PostgresRepository) GetAccountBySupabaseUID(supabaseUID string) (*identity.UserAccount, bool) {
	if supabaseUID == "" {
		return nil, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := "SELECT " + accountSelectFields + " FROM accounts WHERE supabase_user_id = $1 LIMIT 1"
	acc, err := scanAccount(r.pool.QueryRow(ctx, query, supabaseUID))
	if err != nil {
		return nil, false
	}
	return acc, true
}

func (r *PostgresRepository) GetAccountByPublicKey(pubKeyHex string) (*identity.UserAccount, bool) {
	if pubKeyHex == "" {
		return nil, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := "SELECT " + accountSelectFields + " FROM accounts WHERE public_key_hex = $1 LIMIT 1"
	acc, err := scanAccount(r.pool.QueryRow(ctx, query, pubKeyHex))
	if err != nil {
		return nil, false
	}
	return acc, true
}

func (r *PostgresRepository) UpdateAccount(account *identity.UserAccount) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	account.UpdatedAt = time.Now().UnixMilli()

	var phoneHMAC, phoneEncrypted string
	if account.PhoneNumber != "" {
		var err error
		phoneHMAC, err = identity.PhoneHMAC(account.PhoneNumber)
		if err != nil {
			return err
		}
		phoneEncrypted, err = identity.EncryptPhone(account.PhoneNumber)
		if err != nil {
			return err
		}
	}
	account.PhoneHMAC = phoneHMAC
	account.PhoneEncrypted = phoneEncrypted

	query := `
		UPDATE accounts SET
			public_key_hex = $2,
			dh_key_hex = $3,
			phone_number = NULLIF($4, ''),
			phone_hmac = NULLIF($5, ''),
			phone_encrypted = NULLIF($6, ''),
			phone_verified = $7,
			is_discoverable_by_phone = $8,
			google_id = NULLIF($9, ''),
			supabase_user_id = NULLIF($10, ''),
			email = NULLIF($11, ''),
			profile_name = $12,
			avatar_url = $13,
			bio = $14,
			gender = $15,
			age = $16,
			updated_at = $17
		WHERE nx_id = $1;
	`

	res, err := r.pool.Exec(ctx, query,
		account.NxID, account.PublicKeyHex, account.DhKeyHex, account.PhoneNumber,
		account.PhoneHMAC, account.PhoneEncrypted,
		account.PhoneVerified, account.IsDiscoverableByPhone, account.GoogleID, account.SupabaseUserID,
		account.Email, account.ProfileName, account.AvatarUrl, account.Bio,
		account.Gender, account.Age, account.UpdatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to update account %s: %w", account.NxID, err)
	}

	if res.RowsAffected() == 0 {
		return fmt.Errorf("account with NX-ID %s not found for update", account.NxID)
	}

	return nil
}

// LookupVerifiedPhoneNumbers provides privacy-preserving phone discovery.
// Never stores or logs the input numbers. Returns only confirmed matches for discoverable verified accounts.
func (r *PostgresRepository) LookupVerifiedPhoneNumbers(phoneNumbers []string) []*identity.ContactMatch {
	if len(phoneNumbers) == 0 {
		return nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// Dedup input list
	unique := make([]string, 0, len(phoneNumbers))
	seen := make(map[string]bool)
	for _, p := range phoneNumbers {
		if p != "" && !seen[p] {
			seen[p] = true
			unique = append(unique, p)
		}
	}

	if len(unique) == 0 {
		return nil
	}

	query := `
		SELECT COALESCE(phone_hmac, ''), nx_id, profile_name, public_key_hex
		FROM accounts
		WHERE phone_hmac = ANY($1)
		  AND phone_verified = TRUE
		  AND is_discoverable_by_phone = TRUE;
	`

	// Map input phone -> HMAC so the plaintext is only ever used locally and
	// never sent to the database. The stored phone value is not selected.
	hmacToPhone := make(map[string]string, len(unique))
	hmacs := make([]string, 0, len(unique))
	for _, p := range unique {
		hmacHex, err := identity.PhoneHMAC(p)
		if err != nil {
			return nil
		}
		hmacToPhone[hmacHex] = p
		hmacs = append(hmacs, hmacHex)
	}

	var matches []*identity.ContactMatch
	matchedPhones := make(map[string]bool, len(unique))
	matchedNX := make(map[string]bool)

	rows, err := r.pool.Query(ctx, query, hmacs)
	if err == nil {
		for rows.Next() {
			var m identity.ContactMatch
			var hmacHex string
			if err := rows.Scan(&hmacHex, &m.NxID, &m.DisplayName, &m.PublicKeyHex); err == nil {
				m.PhoneNumber = hmacToPhone[hmacHex]
				matchedPhones[m.PhoneNumber] = true
				matchedNX[m.NxID] = true
				matches = append(matches, &m)
			}
		}
		rows.Close()
	}

	// Legacy fallback: rows written before phone protection have no HMAC yet.
	// This path disappears once BackfillPhoneProtection has run.
	legacy := make([]string, 0, len(unique))
	for _, p := range unique {
		if !matchedPhones[p] {
			legacy = append(legacy, p)
		}
	}
	if len(legacy) > 0 {
		legacyQuery := `
			SELECT phone_number, nx_id, profile_name, public_key_hex
			FROM accounts
			WHERE phone_number = ANY($1)
			  AND (phone_hmac IS NULL OR phone_hmac = '')
			  AND phone_verified = TRUE
			  AND is_discoverable_by_phone = TRUE;
		`
		legacyRows, legacyErr := r.pool.Query(ctx, legacyQuery, legacy)
		if legacyErr == nil {
			for legacyRows.Next() {
				var m identity.ContactMatch
				if err := legacyRows.Scan(&m.PhoneNumber, &m.NxID, &m.DisplayName, &m.PublicKeyHex); err == nil {
					if matchedNX[m.NxID] {
						continue
					}
					matches = append(matches, &m)
				}
			}
			legacyRows.Close()
		}
	}

	return matches
}

func (r *PostgresRepository) RegisterDevice(d *identity.Device) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := `
		INSERT INTO devices (
			device_id, account_id, nx_id, device_name, public_key_hex,
			dh_key_hex, fcm_token, is_revoked, revoked_at, revoked_reason, created_at, last_active_at
		) VALUES (
			$1, $2, $3, $4, $5,
			$6, $7, $8, NULLIF($9, 0), NULLIF($10, ''), $11, $12
		)
		ON CONFLICT (device_id) DO UPDATE SET
			account_id = EXCLUDED.account_id,
			nx_id = EXCLUDED.nx_id,
			device_name = EXCLUDED.device_name,
			public_key_hex = EXCLUDED.public_key_hex,
			dh_key_hex = EXCLUDED.dh_key_hex,
			fcm_token = EXCLUDED.fcm_token,
			is_revoked = EXCLUDED.is_revoked,
			revoked_at = EXCLUDED.revoked_at,
			revoked_reason = EXCLUDED.revoked_reason,
			last_active_at = EXCLUDED.last_active_at;
	`

	_, err := r.pool.Exec(ctx, query,
		d.DeviceID, d.AccountID, d.NxID, d.DeviceName, d.PublicKeyHex,
		d.DhKeyHex, d.FcmToken, d.IsRevoked, d.RevokedAt, d.RevokedReason,
		d.CreatedAt, d.LastActiveAt,
	)
	if err != nil {
		return fmt.Errorf("failed to register device %s: %w", d.DeviceID, err)
	}

	return nil
}

// RegisterDeviceReplacingOthers implements the single-active-device rule inside
// a transaction: the new device and its audit event are written, and every
// other active device plus its sessions is revoked atomically.
func (r *PostgresRepository) RegisterDeviceReplacingOthers(d *identity.Device, reason string) ([]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Serialize replacement transactions for the same account. Without this,
	// two concurrent first-time registrations both observe "no active device",
	// both insert, and the account ends up with two active devices. The lock is
	// transaction-scoped and released automatically on commit/rollback.
	if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", d.NxID); err != nil {
		return nil, fmt.Errorf("failed to lock device set for %s: %w", d.NxID, err)
	}

	now := time.Now().UnixMilli()
	if reason == "" {
		reason = "replaced by new device sign-in"
	}

	rows, err := tx.Query(ctx,
		"SELECT device_id FROM devices WHERE nx_id = $1 AND is_revoked = FALSE AND device_id <> $2 FOR UPDATE",
		d.NxID, d.DeviceID,
	)
	if err != nil {
		return nil, err
	}
	var revoked []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err == nil {
			revoked = append(revoked, id)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	for _, id := range revoked {
		if _, err := tx.Exec(ctx,
			"UPDATE devices SET is_revoked = TRUE, revoked_at = $2, revoked_reason = $3, fcm_token = '' WHERE device_id = $1",
			id, now, reason,
		); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx, "UPDATE sessions SET is_revoked = TRUE WHERE device_id = $1", id); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx,
			`INSERT INTO device_events (id, nx_id, device_id, event_type, reason, actor, created_at)
			 VALUES ($1, $2, $3, 'REVOKED_REPLACED', $4, 'system', $5)`,
			fmt.Sprintf("evt_%d_%s", now, id), d.NxID, id, reason, now,
		); err != nil {
			return nil, err
		}
	}

	insert := `
		INSERT INTO devices (
			device_id, account_id, nx_id, device_name, public_key_hex,
			dh_key_hex, fcm_token, is_revoked, revoked_at, revoked_reason, created_at, last_active_at
		) VALUES (
			$1, $2, $3, $4, $5,
			$6, $7, FALSE, NULL, NULL, $8, $9
		)
		ON CONFLICT (device_id) DO UPDATE SET
			account_id = EXCLUDED.account_id,
			nx_id = EXCLUDED.nx_id,
			device_name = EXCLUDED.device_name,
			public_key_hex = EXCLUDED.public_key_hex,
			dh_key_hex = EXCLUDED.dh_key_hex,
			fcm_token = EXCLUDED.fcm_token,
			is_revoked = FALSE,
			revoked_at = NULL,
			revoked_reason = NULL,
			last_active_at = EXCLUDED.last_active_at;
	`
	if _, err := tx.Exec(ctx, insert,
		d.DeviceID, d.AccountID, d.NxID, d.DeviceName, d.PublicKeyHex,
		d.DhKeyHex, d.FcmToken, d.CreatedAt, d.LastActiveAt,
	); err != nil {
		return nil, fmt.Errorf("failed to register device %s: %w", d.DeviceID, err)
	}

	if _, err := tx.Exec(ctx,
		`INSERT INTO device_events (id, nx_id, device_id, event_type, reason, actor, created_at)
		 VALUES ($1, $2, $3, 'REGISTERED', 'active device', 'system', $4)`,
		fmt.Sprintf("evt_%d_%s", now, d.DeviceID), d.NxID, d.DeviceID, now,
	); err != nil {
		return nil, err
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return revoked, nil
}

const deviceSelectFields = `
	device_id, account_id, nx_id, device_name, public_key_hex,
	COALESCE(dh_key_hex, ''), COALESCE(fcm_token, ''), is_revoked,
	COALESCE(revoked_at, 0), COALESCE(revoked_reason, ''), created_at, last_active_at
`

func scanDevice(row pgx.Row) (*identity.Device, error) {
	var d identity.Device
	err := row.Scan(
		&d.DeviceID, &d.AccountID, &d.NxID, &d.DeviceName, &d.PublicKeyHex,
		&d.DhKeyHex, &d.FcmToken, &d.IsRevoked, &d.RevokedAt, &d.RevokedReason,
		&d.CreatedAt, &d.LastActiveAt,
	)
	if err != nil {
		return nil, err
	}
	return &d, nil
}

func (r *PostgresRepository) GetDevice(deviceID string) (*identity.Device, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := "SELECT " + deviceSelectFields + " FROM devices WHERE device_id = $1 LIMIT 1"
	d, err := scanDevice(r.pool.QueryRow(ctx, query, deviceID))
	if err != nil {
		return nil, false
	}
	return d, true
}

func (r *PostgresRepository) GetDevicesForAccount(nxID string) []*identity.Device {
	devices := r.queryDevices(nxID, false)
	return devices
}

// GetAllDevicesForAccount includes revoked devices so callers can present
// revocation history.
func (r *PostgresRepository) GetAllDevicesForAccount(nxID string) []*identity.Device {
	return r.queryDevices(nxID, true)
}

func (r *PostgresRepository) queryDevices(nxID string, includeRevoked bool) []*identity.Device {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := "SELECT " + deviceSelectFields + " FROM devices WHERE nx_id = $1"
	if !includeRevoked {
		query += " AND is_revoked = FALSE"
	}
	query += " ORDER BY created_at ASC"

	rows, err := r.pool.Query(ctx, query, nxID)
	if err != nil {
		return nil
	}
	defer rows.Close()

	var devices []*identity.Device
	for rows.Next() {
		d, err := scanDevice(rows)
		if err == nil {
			devices = append(devices, d)
		}
	}
	return devices
}

func (r *PostgresRepository) GetDevices(nxID string) ([]*identity.Device, error) {
	return r.GetDevicesForAccount(nxID), nil
}

func (r *PostgresRepository) RevokeDevice(deviceID string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	now := time.Now().UnixMilli()

	var nxID string
	if err := tx.QueryRow(ctx, "SELECT nx_id FROM devices WHERE device_id = $1", deviceID).Scan(&nxID); err != nil {
		return fmt.Errorf("device %s not found", deviceID)
	}

	if _, err := tx.Exec(ctx,
		"UPDATE devices SET is_revoked = TRUE, revoked_at = $2, revoked_reason = $3, fcm_token = '' WHERE device_id = $1",
		deviceID, now, "revoked by user",
	); err != nil {
		return err
	}

	if _, err := tx.Exec(ctx, "UPDATE sessions SET is_revoked = TRUE WHERE device_id = $1", deviceID); err != nil {
		return err
	}

	if _, err := tx.Exec(ctx,
		`INSERT INTO device_events (id, nx_id, device_id, event_type, reason, actor, created_at)
		 VALUES ($1, $2, $3, 'REVOKED', 'revoked by user', 'user', $4)`,
		fmt.Sprintf("evt_%d_%s", now, deviceID), nxID, deviceID, now,
	); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *PostgresRepository) RevokeDeviceForUser(nxID string, deviceID string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var owner string
	if err := r.pool.QueryRow(ctx, "SELECT nx_id FROM devices WHERE device_id = $1", deviceID).Scan(&owner); err != nil {
		return fmt.Errorf("device %s not found", deviceID)
	}
	if owner != nxID {
		return fmt.Errorf("device %s is not owned by account %s", deviceID, nxID)
	}
	return r.RevokeDevice(deviceID)
}

func (r *PostgresRepository) RecordDeviceEvent(event *identity.DeviceEvent) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if event.CreatedAt == 0 {
		event.CreatedAt = time.Now().UnixMilli()
	}
	if event.ID == "" {
		event.ID = fmt.Sprintf("evt_%d_%s", event.CreatedAt, event.DeviceID)
	}

	_, err := r.pool.Exec(ctx,
		`INSERT INTO device_events (id, nx_id, device_id, event_type, reason, actor, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		event.ID, event.NxID, event.DeviceID, event.EventType, event.Reason, event.Actor, event.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to record device event: %w", err)
	}
	return nil
}

func (r *PostgresRepository) GetDeviceEvents(nxID string, limit int) ([]*identity.DeviceEvent, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if limit <= 0 {
		limit = 50
	}
	rows, err := r.pool.Query(ctx,
		`SELECT id, nx_id, device_id, event_type, COALESCE(reason, ''), COALESCE(actor, ''), created_at
		 FROM device_events WHERE nx_id = $1 ORDER BY created_at DESC LIMIT $2`,
		nxID, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var events []*identity.DeviceEvent
	for rows.Next() {
		var e identity.DeviceEvent
		if err := rows.Scan(&e.ID, &e.NxID, &e.DeviceID, &e.EventType, &e.Reason, &e.Actor, &e.CreatedAt); err == nil {
			events = append(events, &e)
		}
	}
	return events, rows.Err()
}

func (r *PostgresRepository) UpdateDeviceFcmToken(deviceID string, token string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	now := time.Now().UnixMilli()
	query := "UPDATE devices SET fcm_token = $2, last_active_at = $3 WHERE device_id = $1"
	res, err := r.pool.Exec(ctx, query, deviceID, token, now)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("device %s not found", deviceID)
	}
	return nil
}

func (r *PostgresRepository) CreateSession(sess *identity.Session) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := `
		INSERT INTO sessions (token, account_id, nx_id, device_id, expires_at, is_revoked, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7);
	`
	_, err := r.pool.Exec(ctx, query,
		sess.Token, sess.AccountID, sess.NxID, sess.DeviceID,
		sess.ExpiresAt, sess.IsRevoked, sess.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to insert session: %w", err)
	}
	return nil
}

func (r *PostgresRepository) GetSession(token string) (*identity.Session, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	now := time.Now().UnixMilli()
	query := `
		SELECT token, account_id, nx_id, device_id, expires_at, is_revoked, created_at
		FROM sessions
		WHERE token = $1 AND is_revoked = FALSE AND expires_at > $2
		LIMIT 1;
	`
	var s identity.Session
	err := r.pool.QueryRow(ctx, query, token, now).Scan(
		&s.Token, &s.AccountID, &s.NxID, &s.DeviceID, &s.ExpiresAt, &s.IsRevoked, &s.CreatedAt,
	)
	if err != nil {
		return nil, false
	}
	return &s, true
}

func (r *PostgresRepository) RevokeSession(token string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, err := r.pool.Exec(ctx, "UPDATE sessions SET is_revoked = TRUE WHERE token = $1", token)
	return err
}

// RevokeSessionsForDevice revokes active sessions for an account/device,
// optionally keeping exceptToken active and only retiring sessions created
// before createdBefore. Returns the number of rows updated.
func (r *PostgresRepository) RevokeSessionsForDevice(nxID string, deviceID string, exceptToken string, createdBefore int64) (int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := `
		UPDATE sessions
		SET is_revoked = TRUE
		WHERE nx_id = $1 AND is_revoked = FALSE AND token <> $2
	`
	args := []interface{}{nxID, exceptToken}
	if deviceID != "" {
		args = append(args, deviceID)
		query += fmt.Sprintf(" AND device_id = $%d", len(args))
	}
	if createdBefore > 0 {
		args = append(args, createdBefore)
		query += fmt.Sprintf(" AND created_at < $%d", len(args))
	}

	tag, err := r.pool.Exec(ctx, query, args...)
	if err != nil {
		return 0, fmt.Errorf("failed to revoke sessions for device: %w", err)
	}
	return int(tag.RowsAffected()), nil
}

func (r *PostgresRepository) CountAccounts() (int, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var count int
	err := r.pool.QueryRow(ctx, "SELECT COUNT(*) FROM accounts").Scan(&count)
	if err != nil {
		return 0, err
	}
	return count, nil
}

func (r *PostgresRepository) Close() error {
	if r.pool != nil {
		r.pool.Close()
	}
	return nil
}
