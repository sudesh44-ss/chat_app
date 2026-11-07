package prekey

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	ClaimReservationWindowMs = 5 * 60 * 1000 // 5 minutes
)

type PostgresPrekeyRepository struct {
	pool *pgxpool.Pool
}

func NewPostgresPrekeyRepository(pool *pgxpool.Pool) *PostgresPrekeyRepository {
	return &PostgresPrekeyRepository{pool: pool}
}

func (r *PostgresPrekeyRepository) UploadIdentityKeyBinding(ctx context.Context, binding *IdentityKeyBinding) error {
	if r.pool == nil {
		return errors.New("database pool is nil")
	}

	if binding.ID == "" {
		binding.ID = "bind_" + newUUID("")
	}
	if binding.CreatedAt == 0 {
		binding.CreatedAt = time.Now().UnixMilli()
	}

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Resolve account_id if missing
	if binding.AccountID == "" {
		var accId string
		err := tx.QueryRow(ctx, "SELECT id FROM accounts WHERE nx_id = $1", binding.NxID).Scan(&accId)
		if err != nil {
			return fmt.Errorf("account not found for NX-ID %s: %w", binding.NxID, err)
		}
		binding.AccountID = accId
	}

	upsertSQL := `
	INSERT INTO identity_key_bindings (
		id, account_id, nx_id, device_id, signing_public_key_hex,
		dh_public_key_hex, signature_hex, verified, created_at
	) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	ON CONFLICT (nx_id, device_id) DO UPDATE SET
		dh_public_key_hex = EXCLUDED.dh_public_key_hex,
		signature_hex = EXCLUDED.signature_hex,
		created_at = EXCLUDED.created_at;
	`
	_, err = tx.Exec(ctx, upsertSQL,
		binding.ID, binding.AccountID, binding.NxID, binding.DeviceID,
		strings.ToLower(binding.SigningPublicKeyHex),
		strings.ToLower(binding.DhPublicKeyHex),
		strings.ToLower(binding.SignatureHex),
		binding.Verified, binding.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to upsert identity key binding: %w", err)
	}

	// Update devices table with dh_key_hex
	_, _ = tx.Exec(ctx, "UPDATE devices SET dh_key_hex = $1 WHERE device_id = $2", strings.ToLower(binding.DhPublicKeyHex), binding.DeviceID)
	// Update accounts table with dh_key_hex
	_, _ = tx.Exec(ctx, "UPDATE accounts SET dh_key_hex = $1 WHERE nx_id = $2", strings.ToLower(binding.DhPublicKeyHex), binding.NxID)

	return tx.Commit(ctx)
}

func (r *PostgresPrekeyRepository) GetIdentityKeyBinding(ctx context.Context, nxId, deviceId string) (*IdentityKeyBinding, error) {
	if r.pool == nil {
		return nil, errors.New("database pool is nil")
	}

	var b IdentityKeyBinding
	query := `
	SELECT id, account_id, nx_id, device_id, signing_public_key_hex,
	       dh_public_key_hex, signature_hex, verified, created_at
	FROM identity_key_bindings
	WHERE nx_id = $1 AND device_id = $2
	`
	err := r.pool.QueryRow(ctx, query, nxId, deviceId).Scan(
		&b.ID, &b.AccountID, &b.NxID, &b.DeviceID,
		&b.SigningPublicKeyHex, &b.DhPublicKeyHex, &b.SignatureHex,
		&b.Verified, &b.CreatedAt,
	)
	if err == nil {
		return &b, nil
	}

	if !errors.Is(err, pgx.ErrNoRows) {
		return nil, fmt.Errorf("failed to query identity key binding: %w", err)
	}

	// Fallback to accounts/devices dh_key_hex if explicit binding not yet uploaded
	var signingKey, dhKey, accId string
	fallbackQuery := `
	SELECT a.id, a.public_key_hex, COALESCE(d.dh_key_hex, a.dh_key_hex, '')
	FROM accounts a
	LEFT JOIN devices d ON d.device_id = $2 AND d.nx_id = a.nx_id AND d.is_revoked = FALSE
	WHERE a.nx_id = $1
	`
	err = r.pool.QueryRow(ctx, fallbackQuery, nxId, deviceId).Scan(&accId, &signingKey, &dhKey)
	if err != nil {
		return nil, ErrPrekeyNotFound
	}

	if dhKey == "" {
		return nil, ErrPrekeyNotFound
	}

	return &IdentityKeyBinding{
		ID:                  "fallback_" + nxId + "_" + deviceId,
		AccountID:           accId,
		NxID:                nxId,
		DeviceID:            deviceId,
		SigningPublicKeyHex: signingKey,
		DhPublicKeyHex:      dhKey,
		SignatureHex:        "",
		Verified:            true,
		CreatedAt:           time.Now().UnixMilli(),
	}, nil
}

func (r *PostgresPrekeyRepository) UploadSignedPrekey(ctx context.Context, spk *SignedPrekey) error {
	if r.pool == nil {
		return errors.New("database pool is nil")
	}

	if spk.ID == "" {
		spk.ID = "spk_" + newUUID("")
	}
	now := time.Now().UnixMilli()
	if spk.CreatedAt == 0 {
		spk.CreatedAt = now
	}
	if spk.ExpiresAt == 0 {
		spk.ExpiresAt = now + (14 * 24 * 3600 * 1000) // 14-day validity
	}

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Resolve account_id if missing
	if spk.AccountID == "" {
		var accId string
		err := tx.QueryRow(ctx, "SELECT id FROM accounts WHERE nx_id = $1", spk.NxID).Scan(&accId)
		if err != nil {
			return fmt.Errorf("account not found for NX-ID %s: %w", spk.NxID, err)
		}
		spk.AccountID = accId
	}

	// Deactivate any prior active SPKs for this device
	deactSQL := `UPDATE signed_prekeys SET is_active = FALSE WHERE nx_id = $1 AND device_id = $2 AND is_active = TRUE`
	_, _ = tx.Exec(ctx, deactSQL, spk.NxID, spk.DeviceID)

	// Insert new active SPK
	insertSQL := `
	INSERT INTO signed_prekeys (
		id, account_id, nx_id, device_id, key_id, public_key_hex,
		signature_hex, is_active, created_at, expires_at
	) VALUES ($1, $2, $3, $4, $5, $6, $7, TRUE, $8, $9)
	`
	_, err = tx.Exec(ctx, insertSQL,
		spk.ID, spk.AccountID, spk.NxID, spk.DeviceID, spk.KeyID,
		strings.ToLower(spk.PublicKeyHex),
		strings.ToLower(spk.SignatureHex),
		spk.CreatedAt, spk.ExpiresAt,
	)
	if err != nil {
		return fmt.Errorf("failed to insert signed prekey: %w", err)
	}

	return tx.Commit(ctx)
}

func (r *PostgresPrekeyRepository) GetActiveSignedPrekey(ctx context.Context, nxId, deviceId string) (*SignedPrekey, error) {
	if r.pool == nil {
		return nil, errors.New("database pool is nil")
	}

	var spk SignedPrekey
	var query string
	var args []interface{}

	if deviceId != "" && deviceId != "default" {
		query = `
		SELECT id, account_id, nx_id, device_id, key_id, public_key_hex,
		       signature_hex, is_active, created_at, expires_at
		FROM signed_prekeys
		WHERE nx_id = $1 AND device_id = $2 AND is_active = TRUE
		ORDER BY created_at DESC LIMIT 1
		`
		args = []interface{}{nxId, deviceId}
	} else {
		query = `
		SELECT id, account_id, nx_id, device_id, key_id, public_key_hex,
		       signature_hex, is_active, created_at, expires_at
		FROM signed_prekeys
		WHERE nx_id = $1 AND is_active = TRUE
		ORDER BY created_at DESC LIMIT 1
		`
		args = []interface{}{nxId}
	}

	err := r.pool.QueryRow(ctx, query, args...).Scan(
		&spk.ID, &spk.AccountID, &spk.NxID, &spk.DeviceID, &spk.KeyID,
		&spk.PublicKeyHex, &spk.SignatureHex, &spk.IsActive,
		&spk.CreatedAt, &spk.ExpiresAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNoActiveSignedPrekey
		}
		return nil, fmt.Errorf("failed to query active signed prekey: %w", err)
	}

	return &spk, nil
}

func (r *PostgresPrekeyRepository) UploadOneTimePrekeys(ctx context.Context, opks []*OneTimePrekey) error {
	if r.pool == nil {
		return errors.New("database pool is nil")
	}
	if len(opks) == 0 {
		return nil
	}

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	now := time.Now().UnixMilli()

	// Resolve account_id if needed from first item
	var accId string
	if opks[0].AccountID != "" {
		accId = opks[0].AccountID
	} else {
		err := tx.QueryRow(ctx, "SELECT id FROM accounts WHERE nx_id = $1", opks[0].NxID).Scan(&accId)
		if err != nil {
			return fmt.Errorf("account not found for NX-ID %s: %w", opks[0].NxID, err)
		}
	}

	stmt := `
	INSERT INTO one_time_prekeys (
		id, account_id, nx_id, device_id, key_id, public_key_hex,
		status, created_at
	) VALUES ($1, $2, $3, $4, $5, $6, 'AVAILABLE', $7)
	ON CONFLICT (nx_id, device_id, key_id) DO NOTHING;
	`

	for _, opk := range opks {
		if opk.ID == "" {
			opk.ID = "opk_" + newUUID("")
		}
		if opk.CreatedAt == 0 {
			opk.CreatedAt = now
		}
		_, err := tx.Exec(ctx, stmt,
			opk.ID, accId, opk.NxID, opk.DeviceID, opk.KeyID,
			strings.ToLower(opk.PublicKeyHex), opk.CreatedAt,
		)
		if err != nil {
			return fmt.Errorf("failed to insert OPK %d: %w", opk.KeyID, err)
		}
	}

	return tx.Commit(ctx)
}

func (r *PostgresPrekeyRepository) GetAvailableOpkCount(ctx context.Context, nxId, deviceId string) (int, error) {
	if r.pool == nil {
		return 0, errors.New("database pool is nil")
	}

	var count int
	var query string
	var args []interface{}

	if deviceId != "" && deviceId != "default" {
		query = `SELECT COUNT(*) FROM one_time_prekeys WHERE nx_id = $1 AND device_id = $2 AND status = 'AVAILABLE'`
		args = []interface{}{nxId, deviceId}
	} else {
		query = `SELECT COUNT(*) FROM one_time_prekeys WHERE nx_id = $1 AND status = 'AVAILABLE'`
		args = []interface{}{nxId}
	}

	err := r.pool.QueryRow(ctx, query, args...).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("failed to count available OPKs: %w", err)
	}

	return count, nil
}

func (r *PostgresPrekeyRepository) GetPrekeyBundle(ctx context.Context, targetNxId, targetDeviceId string) (*PrekeyBundle, error) {
	if r.pool == nil {
		return nil, errors.New("database pool is nil")
	}

	// 1. Fetch active SPK
	spk, err := r.GetActiveSignedPrekey(ctx, targetNxId, targetDeviceId)
	if err != nil {
		return nil, err
	}

	effectiveDeviceId := spk.DeviceID

	// 2. Fetch identity binding
	binding, err := r.GetIdentityKeyBinding(ctx, targetNxId, effectiveDeviceId)
	if err != nil {
		return nil, err
	}

	return &PrekeyBundle{
		NxID:                  targetNxId,
		DeviceID:              effectiveDeviceId,
		IdentityKeyHex:        binding.DhPublicKeyHex,
		SigningKeyHex:         binding.SigningPublicKeyHex,
		SignedPrekeyHex:       spk.PublicKeyHex,
		SignedPrekeyID:        spk.KeyID,
		SignedPrekeySignature: spk.SignatureHex,
	}, nil
}

func (r *PostgresPrekeyRepository) ClaimPrekey(ctx context.Context, initiatorNxId, initiatorDeviceId string, req *ClaimRequest) (*ClaimResponse, error) {
	if r.pool == nil {
		return nil, errors.New("database pool is nil")
	}

	targetNxId := req.RecipientNxID
	if targetNxId == "" {
		targetNxId = req.TargetNxID
	}
	if targetNxId == "" {
		return nil, errors.New("missing recipient_nx_id or target_nx_id")
	}
	if req.ClaimID == "" {
		return nil, errors.New("missing claim_id: must be client-generated UUID")
	}

	now := time.Now().UnixMilli()
	expiresAt := now + ClaimReservationWindowMs

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Step 1: Idempotency & Ownership Check
	var existingClaim PrekeyClaim
	claimQuery := `
	SELECT claim_id, initiator_nx_id, initiator_device_id, target_nx_id, target_device_id,
	       COALESCE(opk_id, ''), opk_key_id, spk_id, created_at, expires_at, status
	FROM prekey_claims
	WHERE claim_id = $1
	FOR UPDATE
	`
	err = tx.QueryRow(ctx, claimQuery, req.ClaimID).Scan(
		&existingClaim.ClaimID, &existingClaim.InitiatorNxID, &existingClaim.InitiatorDeviceID,
		&existingClaim.TargetNxID, &existingClaim.TargetDeviceID,
		&existingClaim.OpkID, &existingClaim.OpkKeyID, &existingClaim.SpkID,
		&existingClaim.CreatedAt, &existingClaim.ExpiresAt, &existingClaim.Status,
	)

	if err == nil {
		// Existing claim found! Enforce Mandatory OPK Security Rules:
		// A. Ownership validation
		if existingClaim.InitiatorNxID != initiatorNxId || existingClaim.InitiatorDeviceID != initiatorDeviceId || existingClaim.TargetNxID != targetNxId {
			return nil, ErrClaimOwnershipViolation
		}

		// B. Expiration check: If expired, burn the OPK permanently and return explicit expired error
		if now > existingClaim.ExpiresAt || existingClaim.Status == "EXPIRED" {
			if existingClaim.Status != "EXPIRED" {
				_, _ = tx.Exec(ctx, "UPDATE prekey_claims SET status = 'EXPIRED' WHERE claim_id = $1", req.ClaimID)
				if existingClaim.OpkID != "" {
					_, _ = tx.Exec(ctx, "UPDATE one_time_prekeys SET status = 'BURNED' WHERE id = $1", existingClaim.OpkID)
				}
				_ = tx.Commit(ctx)
			}
			return nil, ErrClaimExpired
		}

		// C. Consumption check: Cannot re-claim consumed session
		if existingClaim.Status == "CONSUMED" {
			return nil, ErrClaimAlreadyConsumed
		}

		// D. Idempotent Retry: Fetch and return the identical bundle
		var spkPub, spkSig string
		var spkKeyId int
		err = tx.QueryRow(ctx, "SELECT public_key_hex, signature_hex, key_id FROM signed_prekeys WHERE id = $1", existingClaim.SpkID).Scan(&spkPub, &spkSig, &spkKeyId)
		if err != nil {
			return nil, fmt.Errorf("failed to load claimed SPK: %w", err)
		}

		var opkPub *string
		if existingClaim.OpkID != "" {
			var pub string
			err = tx.QueryRow(ctx, "SELECT public_key_hex FROM one_time_prekeys WHERE id = $1", existingClaim.OpkID).Scan(&pub)
			if err == nil {
				opkPub = &pub
			}
		}

		binding, _ := r.GetIdentityKeyBinding(ctx, existingClaim.TargetNxID, existingClaim.TargetDeviceID)
		identKey := ""
		signKey := ""
		if binding != nil {
			identKey = binding.DhPublicKeyHex
			signKey = binding.SigningPublicKeyHex
		}

		_ = tx.Commit(ctx)
		return &ClaimResponse{
			ClaimID:               existingClaim.ClaimID,
			InitiatorNxID:         existingClaim.InitiatorNxID,
			InitiatorDeviceID:     existingClaim.InitiatorDeviceID,
			TargetNxID:            existingClaim.TargetNxID,
			TargetDeviceID:        existingClaim.TargetDeviceID,
			IdentityKeyHex:        identKey,
			SigningKeyHex:         signKey,
			SignedPrekeyHex:       spkPub,
			SignedPrekeyID:        spkKeyId,
			SignedPrekeySignature: spkSig,
			OneTimePrekeyHex:      opkPub,
			OneTimePrekeyID:       existingClaim.OpkKeyID,
			ExpiresAt:             existingClaim.ExpiresAt,
		}, nil
	}

	if !errors.Is(err, pgx.ErrNoRows) {
		return nil, fmt.Errorf("error querying existing claim: %w", err)
	}

	// Step 2: New Claim!
	// Fetch target active SPK and identity key binding
	spk, err := r.GetActiveSignedPrekey(ctx, targetNxId, req.TargetDeviceID)
	if err != nil {
		return nil, err
	}
	targetDeviceID := spk.DeviceID

	binding, err := r.GetIdentityKeyBinding(ctx, targetNxId, targetDeviceID)
	if err != nil {
		return nil, err
	}

	// Select 1 available OPK with FOR UPDATE SKIP LOCKED
	allocQuery := `
	SELECT id, key_id, public_key_hex
	FROM one_time_prekeys
	WHERE nx_id = $1 AND device_id = $2 AND status = 'AVAILABLE'
	ORDER BY key_id ASC
	LIMIT 1
	FOR UPDATE SKIP LOCKED
	`
	var opkId, opkPub string
	var opkKeyId int
	opkFound := true

	err = tx.QueryRow(ctx, allocQuery, targetNxId, targetDeviceID).Scan(&opkId, &opkKeyId, &opkPub)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			opkFound = false
		} else {
			return nil, fmt.Errorf("failed to allocate OPK: %w", err)
		}
	}

	var allocatedOpkId *string
	var allocatedOpkKeyId *int
	var allocatedOpkPub *string

	if opkFound {
		// Permanently mark OPK as CLAIMED (Mandatory Rule: never reassigned!)
		updateOpkSQL := `
		UPDATE one_time_prekeys
		SET status = 'CLAIMED',
		    claim_id = $1,
		    claimed_by_nx_id = $2,
		    claimed_by_device_id = $3,
		    claimed_at = $4,
		    expires_at = $5
		WHERE id = $6
		`
		_, err = tx.Exec(ctx, updateOpkSQL, req.ClaimID, initiatorNxId, initiatorDeviceId, now, expiresAt, opkId)
		if err != nil {
			return nil, fmt.Errorf("failed to mark OPK as CLAIMED: %w", err)
		}

		allocatedOpkId = &opkId
		allocatedOpkKeyId = &opkKeyId
		allocatedOpkPub = &opkPub
	}

	// Insert into prekey_claims
	insertClaimSQL := `
	INSERT INTO prekey_claims (
		claim_id, initiator_nx_id, initiator_device_id,
		target_nx_id, target_device_id, opk_id, opk_key_id,
		spk_id, created_at, expires_at, status
	) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 'ACTIVE')
	`
	_, err = tx.Exec(ctx, insertClaimSQL,
		req.ClaimID, initiatorNxId, initiatorDeviceId,
		targetNxId, targetDeviceID, allocatedOpkId, allocatedOpkKeyId,
		spk.ID, now, expiresAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to insert prekey claim: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("failed to commit claim transaction: %w", err)
	}

	return &ClaimResponse{
		ClaimID:               req.ClaimID,
		InitiatorNxID:         initiatorNxId,
		InitiatorDeviceID:     initiatorDeviceId,
		TargetNxID:            targetNxId,
		TargetDeviceID:        targetDeviceID,
		IdentityKeyHex:        binding.DhPublicKeyHex,
		SigningKeyHex:         binding.SigningPublicKeyHex,
		SignedPrekeyHex:       spk.PublicKeyHex,
		SignedPrekeyID:        spk.KeyID,
		SignedPrekeySignature: spk.SignatureHex,
		OneTimePrekeyHex:      allocatedOpkPub,
		OneTimePrekeyID:       allocatedOpkKeyId,
		ExpiresAt:             expiresAt,
	}, nil
}

func (r *PostgresPrekeyRepository) ConsumeClaim(ctx context.Context, claimId, initiatorNxId string) error {
	if r.pool == nil {
		return errors.New("database pool is nil")
	}

	now := time.Now().UnixMilli()
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var opkId *string
	var exp int64
	var status, owner string

	err = tx.QueryRow(ctx, "SELECT COALESCE(opk_id, ''), expires_at, status, initiator_nx_id FROM prekey_claims WHERE claim_id = $1 FOR UPDATE", claimId).Scan(&opkId, &exp, &status, &owner)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrClaimNotFound
		}
		return fmt.Errorf("error querying claim for consumption: %w", err)
	}

	if owner != initiatorNxId {
		return ErrClaimOwnershipViolation
	}
	if status == "CONSUMED" {
		return nil // Idempotent success
	}
	if now > exp || status == "EXPIRED" {
		if opkId != nil && *opkId != "" {
			_, _ = tx.Exec(ctx, "UPDATE one_time_prekeys SET status = 'BURNED' WHERE id = $1", *opkId)
		}
		_, _ = tx.Exec(ctx, "UPDATE prekey_claims SET status = 'EXPIRED' WHERE claim_id = $1", claimId)
		_ = tx.Commit(ctx)
		return ErrClaimExpired
	}

	// Finalize consumption
	_, err = tx.Exec(ctx, "UPDATE prekey_claims SET status = 'CONSUMED' WHERE claim_id = $1", claimId)
	if err != nil {
		return fmt.Errorf("failed to update claim status to CONSUMED: %w", err)
	}

	if opkId != nil && *opkId != "" {
		_, err = tx.Exec(ctx, "UPDATE one_time_prekeys SET status = 'CONSUMED', consumed_at = $1 WHERE id = $2", now, *opkId)
		if err != nil {
			return fmt.Errorf("failed to update OPK status to CONSUMED: %w", err)
		}
	}

	return tx.Commit(ctx)
}

func (r *PostgresPrekeyRepository) Close() error {
	return nil
}
