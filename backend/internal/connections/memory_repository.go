package connections

import (
	"context"
	"fmt"
	"sync"
	"time"

	"nexa/wire/internal/identity"
)

// MemoryRepository is an in-memory implementation of ConnectionRepository for tests & fallback.
type MemoryRepository struct {
	mu           sync.RWMutex
	accounts     identity.IdentityRepository
	requests     map[string]*ConnectionRequest // id -> request
	connections  map[string]*Connection        // canonicalPair(u1, u2) -> connection
	blocks       map[string]int64              // "blocker:blocked" -> timestamp
	phoneGrants  map[string]bool               // "granter:grantee" -> bool
}

func NewMemoryRepository(accounts identity.IdentityRepository) *MemoryRepository {
	return &MemoryRepository{
		accounts:    accounts,
		requests:    make(map[string]*ConnectionRequest),
		connections: make(map[string]*Connection),
		blocks:      make(map[string]int64),
		phoneGrants: make(map[string]bool),
	}
}

var _ ConnectionRepository = (*MemoryRepository)(nil)

func (m *MemoryRepository) SearchUserByNxID(ctx context.Context, requesterNxID, targetNxID string) (*PublicProfile, error) {
	if !identity.IsValidNxID(targetNxID) {
		return nil, ErrInvalidNxID
	}

	blocked, err := m.IsBlocked(ctx, requesterNxID, targetNxID)
	if err != nil {
		return nil, err
	}
	if blocked {
		return nil, ErrUserNotFound
	}

	acc, ok := m.accounts.GetAccountByNxID(targetNxID)
	if !ok || acc == nil {
		return nil, ErrUserNotFound
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	now := time.Now().UnixMilli()
	uA, uB := CanonicalPair(requesterNxID, targetNxID)
	pairKey := fmt.Sprintf("%s:%s", uA, uB)
	conn, hasConn := m.connections[pairKey]
	isConnected := hasConn && conn.Status == ConnectionStatusActive

	isPending := false
	for _, req := range m.requests {
		if ((req.SenderNxID == requesterNxID && req.RecipientNxID == targetNxID) ||
			(req.SenderNxID == targetNxID && req.RecipientNxID == requesterNxID)) &&
			req.Status == RequestStatusPending && req.ExpiresAt > now {
			isPending = true
			break
		}
	}

	return &PublicProfile{
		NxID:         acc.NxID,
		DisplayName:  acc.ProfileName,
		AvatarUrl:    acc.AvatarUrl,
		Bio:          acc.Bio,
		PublicKeyHex: acc.PublicKeyHex,
		IsConnected:  isConnected,
		IsPending:    isPending,
		IsBlocked:    false,
		CreatedAt:    acc.CreatedAt,
	}, nil
}

func (m *MemoryRepository) SendRequest(ctx context.Context, senderNxID, recipientNxID, introMessage string, ttl time.Duration) (*ConnectionRequest, error) {
	if senderNxID == recipientNxID {
		return nil, ErrSelfRequest
	}
	if !identity.IsValidNxID(senderNxID) || !identity.IsValidNxID(recipientNxID) {
		return nil, ErrInvalidNxID
	}

	// Verify target exists
	acc, ok := m.accounts.GetAccountByNxID(recipientNxID)
	if !ok || acc == nil {
		return nil, ErrUserNotFound
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	if ttl <= 0 {
		ttl = DefaultRequestTTL
	}
	expiresAt := now + int64(ttl/time.Millisecond)

	// Check blocks
	if m.isBlockedLocked(senderNxID, recipientNxID) {
		return nil, ErrBlockedByUser
	}

	// Check active connection
	uA, uB := CanonicalPair(senderNxID, recipientNxID)
	pairKey := fmt.Sprintf("%s:%s", uA, uB)
	if conn, exists := m.connections[pairKey]; exists && conn.Status == ConnectionStatusActive {
		return nil, ErrAlreadyConnected
	}

	// Check reciprocal simultaneous request
	for _, req := range m.requests {
		if req.SenderNxID == recipientNxID && req.RecipientNxID == senderNxID &&
			req.Status == RequestStatusPending && req.ExpiresAt > now {
			// Auto-accept reciprocal request!
			req.Status = RequestStatusAccepted
			req.UpdatedAt = now

			conn := &Connection{
				ID:        generateID("conn"),
				UserANxID: uA,
				UserBNxID: uB,
				Status:    ConnectionStatusActive,
				CreatedAt: now,
				UpdatedAt: now,
			}
			m.connections[pairKey] = conn

			return &ConnectionRequest{
				ID:            req.ID,
				SenderNxID:    senderNxID,
				RecipientNxID: recipientNxID,
				IntroMessage:  introMessage,
				Status:        RequestStatusAccepted,
				CreatedAt:     now,
				UpdatedAt:     now,
				ExpiresAt:     expiresAt,
			}, nil
		}
	}

	// Check existing pending request from sender
	for _, req := range m.requests {
		if req.SenderNxID == senderNxID && req.RecipientNxID == recipientNxID &&
			req.Status == RequestStatusPending && req.ExpiresAt > now {
			return nil, ErrPendingRequestExists
		}
	}

	reqID := generateID("req")
	req := &ConnectionRequest{
		ID:            reqID,
		SenderNxID:    senderNxID,
		RecipientNxID: recipientNxID,
		IntroMessage:  introMessage,
		Status:        RequestStatusPending,
		CreatedAt:     now,
		UpdatedAt:     now,
		ExpiresAt:     expiresAt,
	}
	m.requests[reqID] = req

	return req, nil
}

func (m *MemoryRepository) GetIncomingRequests(ctx context.Context, recipientNxID string, limit, offset int) ([]*ConnectionRequestWithProfile, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	var results []*ConnectionRequestWithProfile

	for _, req := range m.requests {
		if req.RecipientNxID == recipientNxID {
			if req.Status == RequestStatusPending && req.ExpiresAt <= now {
				req.Status = RequestStatusExpired
				req.UpdatedAt = now
				continue
			}
			if req.Status == RequestStatusPending {
				if m.isBlockedLocked(recipientNxID, req.SenderNxID) {
					continue
				}
				senderAcc, ok := m.accounts.GetAccountByNxID(req.SenderNxID)
				if !ok || senderAcc == nil {
					continue
				}
				results = append(results, &ConnectionRequestWithProfile{
					ConnectionRequest: *req,
					Profile: &PublicProfile{
						NxID:         senderAcc.NxID,
						DisplayName:  senderAcc.ProfileName,
						AvatarUrl:    senderAcc.AvatarUrl,
						Bio:          senderAcc.Bio,
						PublicKeyHex: senderAcc.PublicKeyHex,
						IsPending:    true,
						CreatedAt:    senderAcc.CreatedAt,
					},
				})
			}
		}
	}
	return results, nil
}

func (m *MemoryRepository) GetOutgoingRequests(ctx context.Context, senderNxID string, limit, offset int) ([]*ConnectionRequestWithProfile, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	var results []*ConnectionRequestWithProfile

	for _, req := range m.requests {
		if req.SenderNxID == senderNxID {
			if req.Status == RequestStatusPending && req.ExpiresAt <= now {
				req.Status = RequestStatusExpired
				req.UpdatedAt = now
				continue
			}
			if req.Status == RequestStatusPending {
				recipientAcc, ok := m.accounts.GetAccountByNxID(req.RecipientNxID)
				if !ok || recipientAcc == nil {
					continue
				}
				results = append(results, &ConnectionRequestWithProfile{
					ConnectionRequest: *req,
					Profile: &PublicProfile{
						NxID:         recipientAcc.NxID,
						DisplayName:  recipientAcc.ProfileName,
						AvatarUrl:    recipientAcc.AvatarUrl,
						Bio:          recipientAcc.Bio,
						PublicKeyHex: recipientAcc.PublicKeyHex,
						IsPending:    true,
						CreatedAt:    recipientAcc.CreatedAt,
					},
				})
			}
		}
	}
	return results, nil
}

func (m *MemoryRepository) AcceptRequest(ctx context.Context, recipientNxID, requestID string) (*Connection, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	req, exists := m.requests[requestID]
	if !exists {
		return nil, ErrRequestNotFound
	}

	if req.RecipientNxID != recipientNxID {
		return nil, ErrUnauthorizedAction
	}
	if req.Status != RequestStatusPending {
		return nil, ErrRequestNotPending
	}
	if req.ExpiresAt <= now {
		req.Status = RequestStatusExpired
		req.UpdatedAt = now
		return nil, ErrRequestExpired
	}

	if m.isBlockedLocked(req.SenderNxID, req.RecipientNxID) {
		return nil, ErrBlockedByUser
	}

	req.Status = RequestStatusAccepted
	req.UpdatedAt = now

	uA, uB := CanonicalPair(req.SenderNxID, req.RecipientNxID)
	pairKey := fmt.Sprintf("%s:%s", uA, uB)

	conn := &Connection{
		ID:        generateID("conn"),
		UserANxID: uA,
		UserBNxID: uB,
		Status:    ConnectionStatusActive,
		Source:    ConnectionSourceNexaID,
		CreatedAt: now,
		UpdatedAt: now,
	}
	m.connections[pairKey] = conn

	return conn, nil
}

func (m *MemoryRepository) RejectRequest(ctx context.Context, recipientNxID, requestID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	req, exists := m.requests[requestID]
	if !exists {
		return ErrRequestNotFound
	}
	if req.RecipientNxID != recipientNxID {
		return ErrUnauthorizedAction
	}
	if req.Status != RequestStatusPending {
		return ErrRequestNotPending
	}

	req.Status = RequestStatusRejected
	req.UpdatedAt = time.Now().UnixMilli()
	return nil
}

func (m *MemoryRepository) CancelRequest(ctx context.Context, senderNxID, requestID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	req, exists := m.requests[requestID]
	if !exists {
		return ErrRequestNotFound
	}
	if req.SenderNxID != senderNxID {
		return ErrUnauthorizedAction
	}
	if req.Status != RequestStatusPending {
		return ErrRequestNotPending
	}

	req.Status = RequestStatusCancelled
	req.UpdatedAt = time.Now().UnixMilli()
	return nil
}

func (m *MemoryRepository) ListConnections(ctx context.Context, userNxID string, limit, offset int) ([]*PublicProfile, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var results []*PublicProfile
	for _, conn := range m.connections {
		if conn.Status == ConnectionStatusActive && (conn.UserANxID == userNxID || conn.UserBNxID == userNxID) {
			otherNx := conn.UserBNxID
			if conn.UserBNxID == userNxID {
				otherNx = conn.UserANxID
			}

			if m.isBlockedLocked(userNxID, otherNx) {
				continue
			}

			acc, ok := m.accounts.GetAccountByNxID(otherNx)
			if ok && acc != nil {
				results = append(results, &PublicProfile{
					NxID:         acc.NxID,
					DisplayName:  acc.ProfileName,
					AvatarUrl:    acc.AvatarUrl,
					Bio:          acc.Bio,
					PublicKeyHex: acc.PublicKeyHex,
					IsConnected:  true,
					CreatedAt:    conn.CreatedAt,
				})
			}
		}
	}
	return results, nil
}

func (m *MemoryRepository) RemoveConnection(ctx context.Context, userNxID, targetNxID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	uA, uB := CanonicalPair(userNxID, targetNxID)
	pairKey := fmt.Sprintf("%s:%s", uA, uB)

	conn, exists := m.connections[pairKey]
	if !exists || conn.Status != ConnectionStatusActive {
		return ErrConnectionNotFound
	}

	conn.Status = ConnectionStatusRemoved
	conn.UpdatedAt = time.Now().UnixMilli()
	return nil
}

func (m *MemoryRepository) BlockUser(ctx context.Context, blockerNxID, blockedNxID string) error {
	if blockerNxID == blockedNxID {
		return ErrSelfBlock
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	m.blocks[fmt.Sprintf("%s:%s", blockerNxID, blockedNxID)] = now

	// Terminate active connection
	uA, uB := CanonicalPair(blockerNxID, blockedNxID)
	pairKey := fmt.Sprintf("%s:%s", uA, uB)
	if conn, exists := m.connections[pairKey]; exists {
		conn.Status = ConnectionStatusRemoved
		conn.UpdatedAt = now
	}

	// Reject any pending requests between them
	for _, req := range m.requests {
		if ((req.SenderNxID == blockerNxID && req.RecipientNxID == blockedNxID) ||
			(req.SenderNxID == blockedNxID && req.RecipientNxID == blockerNxID)) &&
			req.Status == RequestStatusPending {
			req.Status = RequestStatusRejected
			req.UpdatedAt = now
		}
	}

	return nil
}

func (m *MemoryRepository) UnblockUser(ctx context.Context, blockerNxID, blockedNxID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	delete(m.blocks, fmt.Sprintf("%s:%s", blockerNxID, blockedNxID))
	return nil
}

func (m *MemoryRepository) ListBlockedUsers(ctx context.Context, blockerNxID string) ([]*PublicProfile, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var results []*PublicProfile
	prefix := blockerNxID + ":"
	for key, ts := range m.blocks {
		if len(key) > len(prefix) && key[:len(prefix)] == prefix {
			blockedNx := key[len(prefix):]
			acc, ok := m.accounts.GetAccountByNxID(blockedNx)
			if ok && acc != nil {
				results = append(results, &PublicProfile{
					NxID:         acc.NxID,
					DisplayName:  acc.ProfileName,
					AvatarUrl:    acc.AvatarUrl,
					Bio:          acc.Bio,
					PublicKeyHex: acc.PublicKeyHex,
					IsBlocked:    true,
					CreatedAt:    ts,
				})
			}
		}
	}
	return results, nil
}

func (m *MemoryRepository) IsBlocked(ctx context.Context, userA, userB string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.isBlockedLocked(userA, userB), nil
}

func (m *MemoryRepository) IsConnected(ctx context.Context, userA, userB string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	uA, uB := CanonicalPair(userA, userB)
	pairKey := fmt.Sprintf("%s:%s", uA, uB)
	conn, ok := m.connections[pairKey]
	return ok && conn.Status == ConnectionStatusActive, nil
}

func (m *MemoryRepository) isBlockedLocked(userA, userB string) bool {
	_, b1 := m.blocks[fmt.Sprintf("%s:%s", userA, userB)]
	_, b2 := m.blocks[fmt.Sprintf("%s:%s", userB, userA)]
	return b1 || b2
}

func (m *MemoryRepository) SyncQrHandshake(ctx context.Context, scannerNxID, scannedNxID string) (*Connection, error) {
	if scannerNxID == scannedNxID {
		return nil, ErrSelfRequest
	}
	if !identity.IsValidNxID(scannerNxID) || !identity.IsValidNxID(scannedNxID) {
		return nil, ErrInvalidNxID
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if m.isBlockedLocked(scannerNxID, scannedNxID) {
		return nil, ErrBlockedByUser
	}

	acc1, ok1 := m.accounts.GetAccountByNxID(scannerNxID)
	acc2, ok2 := m.accounts.GetAccountByNxID(scannedNxID)
	if !ok1 || acc1 == nil || !ok2 || acc2 == nil {
		return nil, ErrUserNotFound
	}

	now := time.Now().UnixMilli()

	// Update any pending requests
	for _, req := range m.requests {
		if ((req.SenderNxID == scannerNxID && req.RecipientNxID == scannedNxID) ||
			(req.SenderNxID == scannedNxID && req.RecipientNxID == scannerNxID)) &&
			req.Status == RequestStatusPending {
			req.Status = RequestStatusAccepted
			req.UpdatedAt = now
		}
	}

	uA, uB := CanonicalPair(scannerNxID, scannedNxID)
	pairKey := fmt.Sprintf("%s:%s", uA, uB)

	conn, exists := m.connections[pairKey]
	if !exists {
		conn = &Connection{
			ID:        generateID("conn"),
			UserANxID: uA,
			UserBNxID: uB,
			Status:    ConnectionStatusActive,
			Source:    ConnectionSourceQrScan,
			CreatedAt: now,
			UpdatedAt: now,
		}
		m.connections[pairKey] = conn
	} else {
		conn.Status = ConnectionStatusActive
		conn.Source = ConnectionSourceQrScan
		conn.UpdatedAt = now
	}

	return conn, nil
}

func (m *MemoryRepository) SetPhoneVisibility(ctx context.Context, granterNxID, granteeNxID string, isActive bool) error {
	if granterNxID == granteeNxID {
		return nil
	}
	if !identity.IsValidNxID(granterNxID) || !identity.IsValidNxID(granteeNxID) {
		return ErrInvalidNxID
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	key := fmt.Sprintf("%s:%s", granterNxID, granteeNxID)
	if isActive {
		m.phoneGrants[key] = true
	} else {
		delete(m.phoneGrants, key)
	}
	return nil
}

func (m *MemoryRepository) HasPhoneVisibility(ctx context.Context, granterNxID, granteeNxID string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	key := fmt.Sprintf("%s:%s", granterNxID, granteeNxID)
	return m.phoneGrants[key], nil
}

func (m *MemoryRepository) ListPhoneVisibilityGrants(ctx context.Context, granterNxID string) ([]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var grantees []string
	prefix := granterNxID + ":"
	for key, active := range m.phoneGrants {
		if active && len(key) > len(prefix) && key[:len(prefix)] == prefix {
			grantees = append(grantees, key[len(prefix):])
		}
	}
	return grantees, nil
}

func (m *MemoryRepository) GetMutualConnections(ctx context.Context, userA, userB string, limit, offset int) ([]*PublicProfile, int, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	// 1. Get active connections of userA
	userAConns := make(map[string]bool)
	for _, conn := range m.connections {
		if conn.Status != ConnectionStatusActive {
			continue
		}
		var other string
		if conn.UserANxID == userA {
			other = conn.UserBNxID
		} else if conn.UserBNxID == userA {
			other = conn.UserANxID
		}
		if other != "" && other != userB && !m.isBlockedLocked(userA, other) {
			userAConns[other] = true
		}
	}

	// 2. Intersect with active connections of userB
	var mutualIDs []string
	for _, conn := range m.connections {
		if conn.Status != ConnectionStatusActive {
			continue
		}
		var other string
		if conn.UserANxID == userB {
			other = conn.UserBNxID
		} else if conn.UserBNxID == userB {
			other = conn.UserANxID
		}
		if other != "" && other != userA && userAConns[other] && !m.isBlockedLocked(userB, other) {
			mutualIDs = append(mutualIDs, other)
		}
	}

	totalCount := len(mutualIDs)
	if offset >= totalCount {
		return []*PublicProfile{}, totalCount, nil
	}

	end := offset + limit
	if end > totalCount {
		end = totalCount
	}

	var profiles []*PublicProfile
	for _, nxID := range mutualIDs[offset:end] {
		acc, ok := m.accounts.GetAccountByNxID(nxID)
		if ok && acc != nil {
			profiles = append(profiles, &PublicProfile{
				NxID:         acc.NxID,
				DisplayName:  acc.ProfileName,
				AvatarUrl:    acc.AvatarUrl,
				Bio:          acc.Bio,
				PublicKeyHex: acc.PublicKeyHex,
				IsConnected:  true,
				CreatedAt:    acc.CreatedAt,
			})
		}
	}

	return profiles, totalCount, nil
}
