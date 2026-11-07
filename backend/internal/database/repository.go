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

	query := `
		INSERT INTO accounts (
			id, nx_id, public_key_hex, dh_key_hex, phone_number,
			phone_verified, is_discoverable_by_phone, google_id, supabase_user_id,
			email, profile_name, avatar_url, bio, gender, age, created_at, updated_at
		) VALUES (
			$1, $2, $3, $4, NULLIF($5, ''),
			$6, $7, NULLIF($8, ''), NULLIF($9, ''),
			NULLIF($10, ''), $11, $12, $13, $14, $15, $16, $17
		);
	`

	_, err := r.pool.Exec(ctx, query,
		account.ID, account.NxID, account.PublicKeyHex, account.DhKeyHex, account.PhoneNumber,
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
	phone_verified, is_discoverable_by_phone, COALESCE(google_id, ''), COALESCE(supabase_user_id, ''),
	COALESCE(email, ''), profile_name, COALESCE(avatar_url, ''), COALESCE(bio, ''),
	COALESCE(gender, ''), age, created_at, updated_at
`

func scanAccount(row pgx.Row) (*identity.UserAccount, error) {
	var a identity.UserAccount
	err := row.Scan(
		&a.ID, &a.NxID, &a.PublicKeyHex, &a.DhKeyHex, &a.PhoneNumber,
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
	query := `
		UPDATE accounts SET
			public_key_hex = $2,
			dh_key_hex = $3,
			phone_number = NULLIF($4, ''),
			phone_verified = $5,
			is_discoverable_by_phone = $6,
			google_id = NULLIF($7, ''),
			supabase_user_id = NULLIF($8, ''),
			email = NULLIF($9, ''),
			profile_name = $10,
			avatar_url = $11,
			bio = $12,
			gender = $13,
			age = $14,
			updated_at = $15
		WHERE nx_id = $1;
	`

	res, err := r.pool.Exec(ctx, query,
		account.NxID, account.PublicKeyHex, account.DhKeyHex, account.PhoneNumber,
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
		SELECT phone_number, nx_id, profile_name, public_key_hex
		FROM accounts
		WHERE phone_number = ANY($1)
		  AND (phone_verified = TRUE OR is_discoverable_by_phone = TRUE);
	`

	rows, err := r.pool.Query(ctx, query, unique)
	if err != nil {
		return nil
	}
	defer rows.Close()

	var matches []*identity.ContactMatch
	for rows.Next() {
		var m identity.ContactMatch
		if err := rows.Scan(&m.PhoneNumber, &m.NxID, &m.DisplayName, &m.PublicKeyHex); err == nil {
			matches = append(matches, &m)
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
			dh_key_hex, fcm_token, is_revoked, created_at, last_active_at
		) VALUES (
			$1, $2, $3, $4, $5,
			$6, $7, $8, $9, $10
		)
		ON CONFLICT (device_id) DO UPDATE SET
			account_id = EXCLUDED.account_id,
			nx_id = EXCLUDED.nx_id,
			device_name = EXCLUDED.device_name,
			public_key_hex = EXCLUDED.public_key_hex,
			dh_key_hex = EXCLUDED.dh_key_hex,
			fcm_token = EXCLUDED.fcm_token,
			is_revoked = EXCLUDED.is_revoked,
			last_active_at = EXCLUDED.last_active_at;
	`

	_, err := r.pool.Exec(ctx, query,
		d.DeviceID, d.AccountID, d.NxID, d.DeviceName, d.PublicKeyHex,
		d.DhKeyHex, d.FcmToken, d.IsRevoked, d.CreatedAt, d.LastActiveAt,
	)
	if err != nil {
		return fmt.Errorf("failed to register device %s: %w", d.DeviceID, err)
	}

	return nil
}

const deviceSelectFields = `
	device_id, account_id, nx_id, device_name, public_key_hex,
	COALESCE(dh_key_hex, ''), COALESCE(fcm_token, ''), is_revoked, created_at, last_active_at
`

func scanDevice(row pgx.Row) (*identity.Device, error) {
	var d identity.Device
	err := row.Scan(
		&d.DeviceID, &d.AccountID, &d.NxID, &d.DeviceName, &d.PublicKeyHex,
		&d.DhKeyHex, &d.FcmToken, &d.IsRevoked, &d.CreatedAt, &d.LastActiveAt,
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
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	query := "SELECT " + deviceSelectFields + " FROM devices WHERE nx_id = $1 AND is_revoked = FALSE"
	rows, err := r.pool.Query(ctx, query, nxID)
	if err != nil {
		return nil
	}
	defer rows.Close()

	var devices []*identity.Device
	for rows.Next() {
		var d identity.Device
		if err := rows.Scan(
			&d.DeviceID, &d.AccountID, &d.NxID, &d.DeviceName, &d.PublicKeyHex,
			&d.DhKeyHex, &d.FcmToken, &d.IsRevoked, &d.CreatedAt, &d.LastActiveAt,
		); err == nil {
			devices = append(devices, &d)
		}
	}
	return devices
}

func (r *PostgresRepository) GetDevices(nxID string) ([]*identity.Device, error) {
	devs := r.GetDevicesForAccount(nxID)
	return devs, nil
}

func (r *PostgresRepository) RevokeDevice(deviceID string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Revoke device
	res, err := tx.Exec(ctx, "UPDATE devices SET is_revoked = TRUE, fcm_token = '' WHERE device_id = $1", deviceID)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return fmt.Errorf("device %s not found", deviceID)
	}

	// Revoke active sessions for device
	_, err = tx.Exec(ctx, "UPDATE sessions SET is_revoked = TRUE WHERE device_id = $1", deviceID)
	if err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *PostgresRepository) RevokeDeviceForUser(nxID string, deviceID string) error {
	return r.RevokeDevice(deviceID)
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
