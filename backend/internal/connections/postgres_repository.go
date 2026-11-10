package connections

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"nexa/wire/internal/identity"
)

type PostgresRepository struct {
	pool *pgxpool.Pool
}

func NewPostgresRepository(pool *pgxpool.Pool) *PostgresRepository {
	return &PostgresRepository{pool: pool}
}

var _ ConnectionRepository = (*PostgresRepository)(nil)

func generateID(prefix string) string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%s_%d_%s", prefix, time.Now().UnixNano(), hex.EncodeToString(b))
}

func (r *PostgresRepository) SearchUserByNxID(ctx context.Context, requesterNxID, targetNxID string) (*PublicProfile, error) {
	if !identity.IsValidNxID(targetNxID) {
		return nil, ErrInvalidNxID
	}

	now := time.Now().UnixMilli()

	// Check if blocked in either direction
	blocked, err := r.IsBlocked(ctx, requesterNxID, targetNxID)
	if err != nil {
		return nil, err
	}
	if blocked {
		return nil, ErrUserNotFound
	}

	// Query target account public details
	query := `
		SELECT nx_id, profile_name, COALESCE(avatar_url, ''), COALESCE(bio, ''), public_key_hex, created_at
		FROM accounts
		WHERE nx_id = $1
		LIMIT 1;
	`
	var p PublicProfile
	err = r.pool.QueryRow(ctx, query, targetNxID).Scan(
		&p.NxID, &p.DisplayName, &p.AvatarUrl, &p.Bio, &p.PublicKeyHex, &p.CreatedAt,
	)
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, ErrUserNotFound
		}
		return nil, fmt.Errorf("failed to query user %s: %w", targetNxID, err)
	}

	// Determine connection status
	uA, uB := CanonicalPair(requesterNxID, targetNxID)
	var isConnected bool
	connCheckSQL := `SELECT EXISTS(SELECT 1 FROM connections WHERE user_a_nx_id = $1 AND user_b_nx_id = $2 AND status = 'ACTIVE')`
	_ = r.pool.QueryRow(ctx, connCheckSQL, uA, uB).Scan(&isConnected)
	p.IsConnected = isConnected

	var isPending bool
	pendingCheckSQL := `
		SELECT EXISTS(
			SELECT 1 FROM connection_requests 
			WHERE ((sender_nx_id = $1 AND recipient_nx_id = $2) OR (sender_nx_id = $2 AND recipient_nx_id = $1))
			  AND status = 'PENDING' AND expires_at > $3
		)`
	_ = r.pool.QueryRow(ctx, pendingCheckSQL, requesterNxID, targetNxID, now).Scan(&isPending)
	p.IsPending = isPending

	return &p, nil
}

func (r *PostgresRepository) SendRequest(ctx context.Context, senderNxID, recipientNxID, introMessage string, ttl time.Duration) (*ConnectionRequest, error) {
	if senderNxID == recipientNxID {
		return nil, ErrSelfRequest
	}
	if !identity.IsValidNxID(senderNxID) || !identity.IsValidNxID(recipientNxID) {
		return nil, ErrInvalidNxID
	}

	now := time.Now().UnixMilli()
	if ttl <= 0 {
		ttl = DefaultRequestTTL
	}
	expiresAt := now + int64(ttl/time.Millisecond)

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// 1. Verify target exists
	var recipientExists bool
	if err := tx.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM accounts WHERE nx_id = $1)", recipientNxID).Scan(&recipientExists); err != nil || !recipientExists {
		return nil, ErrUserNotFound
	}

	// 2. Check blocks in both directions
	var blocked bool
	blockSQL := `SELECT EXISTS(SELECT 1 FROM user_blocks WHERE (blocker_nx_id = $1 AND blocked_nx_id = $2) OR (blocker_nx_id = $2 AND blocked_nx_id = $1))`
	if err := tx.QueryRow(ctx, blockSQL, senderNxID, recipientNxID).Scan(&blocked); err != nil {
		return nil, err
	}
	if blocked {
		return nil, ErrBlockedByUser
	}

	// 3. Check existing active connection
	uA, uB := CanonicalPair(senderNxID, recipientNxID)
	var alreadyConnected bool
	connSQL := `SELECT EXISTS(SELECT 1 FROM connections WHERE user_a_nx_id = $1 AND user_b_nx_id = $2 AND status = 'ACTIVE')`
	if err := tx.QueryRow(ctx, connSQL, uA, uB).Scan(&alreadyConnected); err != nil {
		return nil, err
	}
	if alreadyConnected {
		return nil, ErrAlreadyConnected
	}

	// 4. Handle reciprocal pending request (Simultaneous requests edge case)
	// If recipient already sent an unexpired pending request to sender, auto-accept it!
	var reciprocalID string
	reciprocalSQL := `
		SELECT id FROM connection_requests 
		WHERE sender_nx_id = $1 AND recipient_nx_id = $2 AND status = 'PENDING' AND expires_at > $3 
		LIMIT 1 FOR UPDATE;
	`
	err = tx.QueryRow(ctx, reciprocalSQL, recipientNxID, senderNxID, now).Scan(&reciprocalID)
	if err == nil && reciprocalID != "" {
		// Auto-accept reciprocal request
		_, err = tx.Exec(ctx, "UPDATE connection_requests SET status = 'ACCEPTED', updated_at = $2 WHERE id = $1", reciprocalID, now)
		if err != nil {
			return nil, err
		}
		connID := generateID("conn")
		insertConnSQL := `
			INSERT INTO connections (id, user_a_nx_id, user_b_nx_id, status, created_at, updated_at)
			VALUES ($1, $2, $3, 'ACTIVE', $4, $4)
			ON CONFLICT (user_a_nx_id, user_b_nx_id) DO UPDATE SET status = 'ACTIVE', updated_at = EXCLUDED.updated_at;
		`
		if _, err := tx.Exec(ctx, insertConnSQL, connID, uA, uB, now); err != nil {
			return nil, err
		}
		if err := tx.Commit(ctx); err != nil {
			return nil, err
		}
		return &ConnectionRequest{
			ID:            reciprocalID,
			SenderNxID:    senderNxID,
			RecipientNxID: recipientNxID,
			IntroMessage:  introMessage,
			Status:        RequestStatusAccepted,
			CreatedAt:     now,
			UpdatedAt:     now,
			ExpiresAt:     expiresAt,
		}, nil
	}

	// 5. Check if duplicate pending request from sender exists
	var pendingExists bool
	existingReqSQL := `SELECT EXISTS(SELECT 1 FROM connection_requests WHERE sender_nx_id = $1 AND recipient_nx_id = $2 AND status = 'PENDING' AND expires_at > $3)`
	if err := tx.QueryRow(ctx, existingReqSQL, senderNxID, recipientNxID, now).Scan(&pendingExists); err != nil {
		return nil, err
	}
	if pendingExists {
		return nil, ErrPendingRequestExists
	}

	// 6. Create request
	reqID := generateID("req")
	insertSQL := `
		INSERT INTO connection_requests (id, sender_nx_id, recipient_nx_id, intro_message, status, created_at, updated_at, expires_at)
		VALUES ($1, $2, $3, $4, 'PENDING', $5, $5, $6);
	`
	_, err = tx.Exec(ctx, insertSQL, reqID, senderNxID, recipientNxID, introMessage, now, expiresAt)
	if err != nil {
		return nil, fmt.Errorf("failed to insert connection request: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	return &ConnectionRequest{
		ID:            reqID,
		SenderNxID:    senderNxID,
		RecipientNxID: recipientNxID,
		IntroMessage:  introMessage,
		Status:        RequestStatusPending,
		CreatedAt:     now,
		UpdatedAt:     now,
		ExpiresAt:     expiresAt,
	}, nil
}

func (r *PostgresRepository) GetIncomingRequests(ctx context.Context, recipientNxID string, limit, offset int) ([]*ConnectionRequestWithProfile, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	now := time.Now().UnixMilli()

	// Lazily expire old pending requests
	_, _ = r.pool.Exec(ctx, "UPDATE connection_requests SET status = 'EXPIRED', updated_at = $2 WHERE recipient_nx_id = $1 AND status = 'PENDING' AND expires_at <= $2", recipientNxID, now)

	query := `
		SELECT 
			r.id, r.sender_nx_id, r.recipient_nx_id, COALESCE(r.intro_message, ''), r.status, r.created_at, r.updated_at, r.expires_at,
			a.profile_name, COALESCE(a.avatar_url, ''), COALESCE(a.bio, ''), a.public_key_hex, a.created_at
		FROM connection_requests r
		JOIN accounts a ON r.sender_nx_id = a.nx_id
		LEFT JOIN user_blocks b ON (b.blocker_nx_id = $1 AND b.blocked_nx_id = r.sender_nx_id)
		WHERE r.recipient_nx_id = $1
		  AND r.status = 'PENDING'
		  AND r.expires_at > $2
		  AND b.blocker_nx_id IS NULL
		ORDER BY r.created_at DESC
		LIMIT $3 OFFSET $4;
	`

	rows, err := r.pool.Query(ctx, query, recipientNxID, now, limit, offset)
	if err != nil {
		return nil, fmt.Errorf("failed to query incoming requests: %w", err)
	}
	defer rows.Close()

	var results []*ConnectionRequestWithProfile
	for rows.Next() {
		var req ConnectionRequestWithProfile
		req.Profile = &PublicProfile{}
		err := rows.Scan(
			&req.ID, &req.SenderNxID, &req.RecipientNxID, &req.IntroMessage, &req.Status, &req.CreatedAt, &req.UpdatedAt, &req.ExpiresAt,
			&req.Profile.DisplayName, &req.Profile.AvatarUrl, &req.Profile.Bio, &req.Profile.PublicKeyHex, &req.Profile.CreatedAt,
		)
		if err != nil {
			return nil, err
		}
		req.Profile.NxID = req.SenderNxID
		req.Profile.IsPending = true
		results = append(results, &req)
	}

	return results, nil
}

func (r *PostgresRepository) GetOutgoingRequests(ctx context.Context, senderNxID string, limit, offset int) ([]*ConnectionRequestWithProfile, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	now := time.Now().UnixMilli()

	// Lazily expire old pending requests
	_, _ = r.pool.Exec(ctx, "UPDATE connection_requests SET status = 'EXPIRED', updated_at = $2 WHERE sender_nx_id = $1 AND status = 'PENDING' AND expires_at <= $2", senderNxID, now)

	query := `
		SELECT 
			r.id, r.sender_nx_id, r.recipient_nx_id, COALESCE(r.intro_message, ''), r.status, r.created_at, r.updated_at, r.expires_at,
			a.profile_name, COALESCE(a.avatar_url, ''), COALESCE(a.bio, ''), a.public_key_hex, a.created_at
		FROM connection_requests r
		JOIN accounts a ON r.recipient_nx_id = a.nx_id
		WHERE r.sender_nx_id = $1
		  AND r.status = 'PENDING'
		  AND r.expires_at > $2
		ORDER BY r.created_at DESC
		LIMIT $3 OFFSET $4;
	`

	rows, err := r.pool.Query(ctx, query, senderNxID, now, limit, offset)
	if err != nil {
		return nil, fmt.Errorf("failed to query outgoing requests: %w", err)
	}
	defer rows.Close()

	var results []*ConnectionRequestWithProfile
	for rows.Next() {
		var req ConnectionRequestWithProfile
		req.Profile = &PublicProfile{}
		err := rows.Scan(
			&req.ID, &req.SenderNxID, &req.RecipientNxID, &req.IntroMessage, &req.Status, &req.CreatedAt, &req.UpdatedAt, &req.ExpiresAt,
			&req.Profile.DisplayName, &req.Profile.AvatarUrl, &req.Profile.Bio, &req.Profile.PublicKeyHex, &req.Profile.CreatedAt,
		)
		if err != nil {
			return nil, err
		}
		req.Profile.NxID = req.RecipientNxID
		req.Profile.IsPending = true
		results = append(results, &req)
	}

	return results, nil
}

func (r *PostgresRepository) AcceptRequest(ctx context.Context, recipientNxID, requestID string) (*Connection, error) {
	now := time.Now().UnixMilli()

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Lock request row
	query := `SELECT id, sender_nx_id, recipient_nx_id, status, expires_at FROM connection_requests WHERE id = $1 FOR UPDATE;`
	var req ConnectionRequest
	err = tx.QueryRow(ctx, query, requestID).Scan(&req.ID, &req.SenderNxID, &req.RecipientNxID, &req.Status, &req.ExpiresAt)
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, ErrRequestNotFound
		}
		return nil, err
	}

	// Verification rules
	if req.RecipientNxID != recipientNxID {
		return nil, ErrUnauthorizedAction
	}
	if req.Status != RequestStatusPending {
		return nil, ErrRequestNotPending
	}
	if req.ExpiresAt <= now {
		_, _ = tx.Exec(ctx, "UPDATE connection_requests SET status = 'EXPIRED', updated_at = $2 WHERE id = $1", requestID, now)
		_ = tx.Commit(ctx)
		return nil, ErrRequestExpired
	}

	// Verify not blocked
	var blocked bool
	blockSQL := `SELECT EXISTS(SELECT 1 FROM user_blocks WHERE (blocker_nx_id = $1 AND blocked_nx_id = $2) OR (blocker_nx_id = $2 AND blocked_nx_id = $1))`
	if err := tx.QueryRow(ctx, blockSQL, req.SenderNxID, req.RecipientNxID).Scan(&blocked); err != nil || blocked {
		return nil, ErrBlockedByUser
	}

	// Mark request accepted
	_, err = tx.Exec(ctx, "UPDATE connection_requests SET status = 'ACCEPTED', updated_at = $2 WHERE id = $1", requestID, now)
	if err != nil {
		return nil, err
	}

	// Mark any reciprocal requests accepted
	_, _ = tx.Exec(ctx, "UPDATE connection_requests SET status = 'ACCEPTED', updated_at = $3 WHERE sender_nx_id = $1 AND recipient_nx_id = $2 AND status = 'PENDING'",
		req.RecipientNxID, req.SenderNxID, now)

	// Create canonical bilateral connection
	uA, uB := CanonicalPair(req.SenderNxID, req.RecipientNxID)
	connID := generateID("conn")

	insertConnSQL := `
		INSERT INTO connections (id, user_a_nx_id, user_b_nx_id, status, source, created_at, updated_at)
		VALUES ($1, $2, $3, 'ACTIVE', 'NEXA_ID', $4, $4)
		ON CONFLICT (user_a_nx_id, user_b_nx_id) DO UPDATE SET
			status = 'ACTIVE',
			updated_at = EXCLUDED.updated_at
		RETURNING id, user_a_nx_id, user_b_nx_id, status, source, created_at, updated_at;
	`
	var conn Connection
	err = tx.QueryRow(ctx, insertConnSQL, connID, uA, uB, now).Scan(
		&conn.ID, &conn.UserANxID, &conn.UserBNxID, &conn.Status, &conn.Source, &conn.CreatedAt, &conn.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create bilateral connection: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	return &conn, nil
}

func (r *PostgresRepository) RejectRequest(ctx context.Context, recipientNxID, requestID string) error {
	now := time.Now().UnixMilli()

	query := `SELECT id, recipient_nx_id, status FROM connection_requests WHERE id = $1;`
	var req ConnectionRequest
	err := r.pool.QueryRow(ctx, query, requestID).Scan(&req.ID, &req.RecipientNxID, &req.Status)
	if err != nil {
		if err == pgx.ErrNoRows {
			return ErrRequestNotFound
		}
		return err
	}

	if req.RecipientNxID != recipientNxID {
		return ErrUnauthorizedAction
	}
	if req.Status != RequestStatusPending {
		return ErrRequestNotPending
	}

	_, err = r.pool.Exec(ctx, "UPDATE connection_requests SET status = 'REJECTED', updated_at = $2 WHERE id = $1", requestID, now)
	return err
}

func (r *PostgresRepository) CancelRequest(ctx context.Context, senderNxID, requestID string) error {
	now := time.Now().UnixMilli()

	query := `SELECT id, sender_nx_id, status FROM connection_requests WHERE id = $1;`
	var req ConnectionRequest
	err := r.pool.QueryRow(ctx, query, requestID).Scan(&req.ID, &req.SenderNxID, &req.Status)
	if err != nil {
		if err == pgx.ErrNoRows {
			return ErrRequestNotFound
		}
		return err
	}

	if req.SenderNxID != senderNxID {
		return ErrUnauthorizedAction
	}
	if req.Status != RequestStatusPending {
		return ErrRequestNotPending
	}

	_, err = r.pool.Exec(ctx, "UPDATE connection_requests SET status = 'CANCELLED', updated_at = $2 WHERE id = $1", requestID, now)
	return err
}

func (r *PostgresRepository) ListConnections(ctx context.Context, userNxID string, limit, offset int) ([]*PublicProfile, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	query := `
		SELECT 
			a.nx_id, a.profile_name, COALESCE(a.avatar_url, ''), COALESCE(a.bio, ''), a.public_key_hex, c.created_at
		FROM connections c
		JOIN accounts a ON a.nx_id = CASE WHEN c.user_a_nx_id = $1 THEN c.user_b_nx_id ELSE c.user_a_nx_id END
		LEFT JOIN user_blocks b ON (
			(b.blocker_nx_id = $1 AND b.blocked_nx_id = a.nx_id) OR 
			(b.blocker_nx_id = a.nx_id AND b.blocked_nx_id = $1)
		)
		WHERE (c.user_a_nx_id = $1 OR c.user_b_nx_id = $1)
		  AND c.status = 'ACTIVE'
		  AND b.blocker_nx_id IS NULL
		ORDER BY c.updated_at DESC
		LIMIT $2 OFFSET $3;
	`

	rows, err := r.pool.Query(ctx, query, userNxID, limit, offset)
	if err != nil {
		return nil, fmt.Errorf("failed to query connections: %w", err)
	}
	defer rows.Close()

	var results []*PublicProfile
	for rows.Next() {
		var p PublicProfile
		if err := rows.Scan(&p.NxID, &p.DisplayName, &p.AvatarUrl, &p.Bio, &p.PublicKeyHex, &p.CreatedAt); err != nil {
			return nil, err
		}
		p.IsConnected = true
		results = append(results, &p)
	}

	return results, nil
}

func (r *PostgresRepository) RemoveConnection(ctx context.Context, userNxID, targetNxID string) error {
	uA, uB := CanonicalPair(userNxID, targetNxID)
	now := time.Now().UnixMilli()

	query := `
		UPDATE connections 
		SET status = 'REMOVED', updated_at = $3 
		WHERE user_a_nx_id = $1 AND user_b_nx_id = $2 AND status = 'ACTIVE';
	`
	res, err := r.pool.Exec(ctx, query, uA, uB, now)
	if err != nil {
		return err
	}
	if res.RowsAffected() == 0 {
		return ErrConnectionNotFound
	}
	return nil
}

func (r *PostgresRepository) BlockUser(ctx context.Context, blockerNxID, blockedNxID string) error {
	if blockerNxID == blockedNxID {
		return ErrSelfBlock
	}

	now := time.Now().UnixMilli()
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// 1. Insert into user_blocks
	blockInsertSQL := `
		INSERT INTO user_blocks (blocker_nx_id, blocked_nx_id, created_at)
		VALUES ($1, $2, $3)
		ON CONFLICT (blocker_nx_id, blocked_nx_id) DO NOTHING;
	`
	if _, err := tx.Exec(ctx, blockInsertSQL, blockerNxID, blockedNxID, now); err != nil {
		return err
	}

	// 2. Terminate active connection
	uA, uB := CanonicalPair(blockerNxID, blockedNxID)
	_, _ = tx.Exec(ctx, "UPDATE connections SET status = 'REMOVED', updated_at = $3 WHERE user_a_nx_id = $1 AND user_b_nx_id = $2", uA, uB, now)

	// 3. Mark pending requests between them as REJECTED
	_, _ = tx.Exec(ctx, `
		UPDATE connection_requests SET status = 'REJECTED', updated_at = $3 
		WHERE ((sender_nx_id = $1 AND recipient_nx_id = $2) OR (sender_nx_id = $2 AND recipient_nx_id = $1))
		  AND status = 'PENDING';
	`, blockerNxID, blockedNxID, now)

	return tx.Commit(ctx)
}

func (r *PostgresRepository) UnblockUser(ctx context.Context, blockerNxID, blockedNxID string) error {
	_, err := r.pool.Exec(ctx, "DELETE FROM user_blocks WHERE blocker_nx_id = $1 AND blocked_nx_id = $2", blockerNxID, blockedNxID)
	return err
}

func (r *PostgresRepository) ListBlockedUsers(ctx context.Context, blockerNxID string) ([]*PublicProfile, error) {
	query := `
		SELECT a.nx_id, a.profile_name, COALESCE(a.avatar_url, ''), COALESCE(a.bio, ''), a.public_key_hex, b.created_at
		FROM user_blocks b
		JOIN accounts a ON b.blocked_nx_id = a.nx_id
		WHERE b.blocker_nx_id = $1
		ORDER BY b.created_at DESC;
	`
	rows, err := r.pool.Query(ctx, query, blockerNxID)
	if err != nil {
		return nil, fmt.Errorf("failed to query blocked users: %w", err)
	}
	defer rows.Close()

	var results []*PublicProfile
	for rows.Next() {
		var p PublicProfile
		if err := rows.Scan(&p.NxID, &p.DisplayName, &p.AvatarUrl, &p.Bio, &p.PublicKeyHex, &p.CreatedAt); err != nil {
			return nil, err
		}
		p.IsBlocked = true
		results = append(results, &p)
	}
	return results, nil
}

func (r *PostgresRepository) IsBlocked(ctx context.Context, userA, userB string) (bool, error) {
	query := `
		SELECT EXISTS(
			SELECT 1 FROM user_blocks 
			WHERE (blocker_nx_id = $1 AND blocked_nx_id = $2) 
			   OR (blocker_nx_id = $2 AND blocked_nx_id = $1)
		);
	`
	var blocked bool
	err := r.pool.QueryRow(ctx, query, userA, userB).Scan(&blocked)
	return blocked, err
}

func (r *PostgresRepository) IsConnected(ctx context.Context, userA, userB string) (bool, error) {
	uA, uB := CanonicalPair(userA, userB)
	query := `SELECT EXISTS(SELECT 1 FROM connections WHERE user_a_nx_id = $1 AND user_b_nx_id = $2 AND status = 'ACTIVE');`
	var isConnected bool
	err := r.pool.QueryRow(ctx, query, uA, uB).Scan(&isConnected)
	return isConnected, err
}

func (r *PostgresRepository) SyncQrHandshake(ctx context.Context, scannerNxID, scannedNxID string) (*Connection, error) {
	if scannerNxID == scannedNxID {
		return nil, ErrSelfRequest
	}
	if !identity.IsValidNxID(scannerNxID) || !identity.IsValidNxID(scannedNxID) {
		return nil, ErrInvalidNxID
	}

	blocked, err := r.IsBlocked(ctx, scannerNxID, scannedNxID)
	if err != nil {
		return nil, err
	}
	if blocked {
		return nil, ErrBlockedByUser
	}

	var exists bool
	if err := r.pool.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM accounts WHERE nx_id = $1)", scannerNxID).Scan(&exists); err != nil || !exists {
		return nil, ErrUserNotFound
	}
	if err := r.pool.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM accounts WHERE nx_id = $1)", scannedNxID).Scan(&exists); err != nil || !exists {
		return nil, ErrUserNotFound
	}

	uA, uB := CanonicalPair(scannerNxID, scannedNxID)
	now := time.Now().UnixMilli()
	connID := generateID("conn")

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Update any pending connection requests to ACCEPTED
	_, _ = tx.Exec(ctx, `
		UPDATE connection_requests SET status = 'ACCEPTED', updated_at = $3
		WHERE ((sender_nx_id = $1 AND recipient_nx_id = $2) OR (sender_nx_id = $2 AND recipient_nx_id = $1))
		  AND status = 'PENDING';
	`, scannerNxID, scannedNxID, now)

	insertConnSQL := `
		INSERT INTO connections (id, user_a_nx_id, user_b_nx_id, status, source, created_at, updated_at)
		VALUES ($1, $2, $3, 'ACTIVE', 'QR_SCAN', $4, $4)
		ON CONFLICT (user_a_nx_id, user_b_nx_id) DO UPDATE SET
			status = 'ACTIVE',
			source = 'QR_SCAN',
			updated_at = EXCLUDED.updated_at
		RETURNING id, user_a_nx_id, user_b_nx_id, status, source, created_at, updated_at;
	`
	var conn Connection
	err = tx.QueryRow(ctx, insertConnSQL, connID, uA, uB, now).Scan(
		&conn.ID, &conn.UserANxID, &conn.UserBNxID, &conn.Status, &conn.Source, &conn.CreatedAt, &conn.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to sync QR connection: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	return &conn, nil
}

func (r *PostgresRepository) SetPhoneVisibility(ctx context.Context, granterNxID, granteeNxID string, isActive bool) error {
	if granterNxID == granteeNxID {
		return nil
	}
	if !identity.IsValidNxID(granterNxID) || !identity.IsValidNxID(granteeNxID) {
		return ErrInvalidNxID
	}

	now := time.Now().UnixMilli()
	query := `
		INSERT INTO phone_visibility_grants (granter_nx_id, grantee_nx_id, is_active, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $4)
		ON CONFLICT (granter_nx_id, grantee_nx_id) DO UPDATE SET
			is_active = EXCLUDED.is_active,
			updated_at = EXCLUDED.updated_at;
	`
	_, err := r.pool.Exec(ctx, query, granterNxID, granteeNxID, isActive, now)
	return err
}

func (r *PostgresRepository) HasPhoneVisibility(ctx context.Context, granterNxID, granteeNxID string) (bool, error) {
	query := `
		SELECT EXISTS(
			SELECT 1 FROM phone_visibility_grants
			WHERE granter_nx_id = $1 AND grantee_nx_id = $2 AND is_active = TRUE
		);
	`
	var hasVisibility bool
	err := r.pool.QueryRow(ctx, query, granterNxID, granteeNxID).Scan(&hasVisibility)
	return hasVisibility, err
}

func (r *PostgresRepository) ListPhoneVisibilityGrants(ctx context.Context, granterNxID string) ([]string, error) {
	query := `
		SELECT grantee_nx_id FROM phone_visibility_grants
		WHERE granter_nx_id = $1 AND is_active = TRUE
		ORDER BY updated_at DESC;
	`
	rows, err := r.pool.Query(ctx, query, granterNxID)
	if err != nil {
		return nil, fmt.Errorf("failed to query phone visibility grants: %w", err)
	}
	defer rows.Close()

	var grantees []string
	for rows.Next() {
		var grantee string
		if err := rows.Scan(&grantee); err != nil {
			return nil, err
		}
		grantees = append(grantees, grantee)
	}
	return grantees, nil
}

func (r *PostgresRepository) GetMutualConnections(ctx context.Context, userA, userB string, limit, offset int) ([]*PublicProfile, int, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	countQuery := `
		WITH user_a_conns AS (
			SELECT CASE WHEN user_a_nx_id = $1 THEN user_b_nx_id ELSE user_a_nx_id END AS nx_id
			FROM connections
			WHERE (user_a_nx_id = $1 OR user_b_nx_id = $1) AND status = 'ACTIVE'
		),
		user_b_conns AS (
			SELECT CASE WHEN user_a_nx_id = $2 THEN user_b_nx_id ELSE user_a_nx_id END AS nx_id
			FROM connections
			WHERE (user_a_nx_id = $2 OR user_b_nx_id = $2) AND status = 'ACTIVE'
		)
		SELECT COUNT(DISTINCT a.nx_id)
		FROM user_a_conns a
		JOIN user_b_conns b ON a.nx_id = b.nx_id
		LEFT JOIN user_blocks blk ON (
			(blk.blocker_nx_id = $1 AND blk.blocked_nx_id = a.nx_id) OR
			(blk.blocker_nx_id = a.nx_id AND blk.blocked_nx_id = $1)
		)
		WHERE a.nx_id != $1 AND a.nx_id != $2 AND blk.blocker_nx_id IS NULL;
	`
	var totalCount int
	if err := r.pool.QueryRow(ctx, countQuery, userA, userB).Scan(&totalCount); err != nil {
		return nil, 0, fmt.Errorf("failed to count mutual connections: %w", err)
	}

	dataQuery := `
		WITH user_a_conns AS (
			SELECT CASE WHEN user_a_nx_id = $1 THEN user_b_nx_id ELSE user_a_nx_id END AS nx_id
			FROM connections
			WHERE (user_a_nx_id = $1 OR user_b_nx_id = $1) AND status = 'ACTIVE'
		),
		user_b_conns AS (
			SELECT CASE WHEN user_a_nx_id = $2 THEN user_b_nx_id ELSE user_a_nx_id END AS nx_id
			FROM connections
			WHERE (user_a_nx_id = $2 OR user_b_nx_id = $2) AND status = 'ACTIVE'
		),
		mutual AS (
			SELECT DISTINCT a.nx_id
			FROM user_a_conns a
			JOIN user_b_conns b ON a.nx_id = b.nx_id
			LEFT JOIN user_blocks blk ON (
				(blk.blocker_nx_id = $1 AND blk.blocked_nx_id = a.nx_id) OR
				(blk.blocker_nx_id = a.nx_id AND blk.blocked_nx_id = $1)
			)
			WHERE a.nx_id != $1 AND a.nx_id != $2 AND blk.blocker_nx_id IS NULL
		)
		SELECT 
			acc.nx_id, acc.profile_name, COALESCE(acc.avatar_url, ''), COALESCE(acc.bio, ''), acc.public_key_hex, acc.created_at
		FROM mutual m
		JOIN accounts acc ON m.nx_id = acc.nx_id
		ORDER BY acc.profile_name ASC
		LIMIT $3 OFFSET $4;
	`
	rows, err := r.pool.Query(ctx, dataQuery, userA, userB, limit, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to query mutual connections: %w", err)
	}
	defer rows.Close()

	var profiles []*PublicProfile
	for rows.Next() {
		var p PublicProfile
		if err := rows.Scan(&p.NxID, &p.DisplayName, &p.AvatarUrl, &p.Bio, &p.PublicKeyHex, &p.CreatedAt); err != nil {
			return nil, 0, err
		}
		p.IsConnected = true
		profiles = append(profiles, &p)
	}

	return profiles, totalCount, nil
}
