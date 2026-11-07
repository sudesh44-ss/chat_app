package prekey

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"
)

// MemoryPrekeyRepository provides an in-memory thread-safe implementation of PrekeyRepository
// strictly adhering to the Mandatory OPK Security Rules for testing and isolated suites.
type MemoryPrekeyRepository struct {
	mu           sync.RWMutex
	bindings     map[string]*IdentityKeyBinding // key: nxId + ":" + deviceId
	signedPrekeys map[string][]*SignedPrekey   // key: nxId + ":" + deviceId
	oneTimePrekeys map[string][]*OneTimePrekey // key: nxId + ":" + deviceId
	claims       map[string]*PrekeyClaim        // key: claim_id
}

func NewMemoryPrekeyRepository() *MemoryPrekeyRepository {
	return &MemoryPrekeyRepository{
		bindings:      make(map[string]*IdentityKeyBinding),
		signedPrekeys:  make(map[string][]*SignedPrekey),
		oneTimePrekeys: make(map[string][]*OneTimePrekey),
		claims:        make(map[string]*PrekeyClaim),
	}
}

func (m *MemoryPrekeyRepository) key(nxId, deviceId string) string {
	return fmt.Sprintf("%s:%s", nxId, deviceId)
}

func (m *MemoryPrekeyRepository) UploadIdentityKeyBinding(ctx context.Context, binding *IdentityKeyBinding) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if binding.ID == "" {
		binding.ID = "bind_" + newUUID("")
	}
	if binding.CreatedAt == 0 {
		binding.CreatedAt = time.Now().UnixMilli()
	}

	k := m.key(binding.NxID, binding.DeviceID)
	m.bindings[k] = binding
	return nil
}

func (m *MemoryPrekeyRepository) GetIdentityKeyBinding(ctx context.Context, nxId, deviceId string) (*IdentityKeyBinding, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	k := m.key(nxId, deviceId)
	if b, ok := m.bindings[k]; ok {
		return b, nil
	}
	// Try without device ID
	for _, b := range m.bindings {
		if b.NxID == nxId {
			return b, nil
		}
	}
	return nil, ErrPrekeyNotFound
}

func (m *MemoryPrekeyRepository) UploadSignedPrekey(ctx context.Context, spk *SignedPrekey) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if spk.ID == "" {
		spk.ID = "spk_" + newUUID("")
	}
	now := time.Now().UnixMilli()
	if spk.CreatedAt == 0 {
		spk.CreatedAt = now
	}
	if spk.ExpiresAt == 0 {
		spk.ExpiresAt = now + (14 * 24 * 3600 * 1000)
	}

	k := m.key(spk.NxID, spk.DeviceID)
	// Deactivate prior active SPKs
	for _, oldSpk := range m.signedPrekeys[k] {
		oldSpk.IsActive = false
	}

	spk.IsActive = true
	m.signedPrekeys[k] = append(m.signedPrekeys[k], spk)
	return nil
}

func (m *MemoryPrekeyRepository) GetActiveSignedPrekey(ctx context.Context, nxId, deviceId string) (*SignedPrekey, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	k := m.key(nxId, deviceId)
	if list, ok := m.signedPrekeys[k]; ok {
		for i := len(list) - 1; i >= 0; i-- {
			if list[i].IsActive {
				return list[i], nil
			}
		}
	}

	// Fallback to any active SPK for that account
	for _, list := range m.signedPrekeys {
		for i := len(list) - 1; i >= 0; i-- {
			if list[i].NxID == nxId && list[i].IsActive {
				return list[i], nil
			}
		}
	}

	return nil, ErrNoActiveSignedPrekey
}

func (m *MemoryPrekeyRepository) UploadOneTimePrekeys(ctx context.Context, opks []*OneTimePrekey) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	for _, opk := range opks {
		if opk.ID == "" {
			opk.ID = "opk_" + newUUID("")
		}
		if opk.CreatedAt == 0 {
			opk.CreatedAt = now
		}
		opk.Status = "AVAILABLE"

		k := m.key(opk.NxID, opk.DeviceID)
		// Check duplicate key_id
		exists := false
		for _, existing := range m.oneTimePrekeys[k] {
			if existing.KeyID == opk.KeyID {
				exists = true
				break
			}
		}
		if !exists {
			m.oneTimePrekeys[k] = append(m.oneTimePrekeys[k], opk)
		}
	}
	return nil
}

func (m *MemoryPrekeyRepository) GetAvailableOpkCount(ctx context.Context, nxId, deviceId string) (int, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	count := 0
	k := m.key(nxId, deviceId)
	if list, ok := m.oneTimePrekeys[k]; ok {
		for _, opk := range list {
			if opk.Status == "AVAILABLE" {
				count++
			}
		}
		return count, nil
	}

	for _, list := range m.oneTimePrekeys {
		for _, opk := range list {
			if opk.NxID == nxId && opk.Status == "AVAILABLE" {
				count++
			}
		}
	}
	return count, nil
}

func (m *MemoryPrekeyRepository) GetPrekeyBundle(ctx context.Context, targetNxId, targetDeviceId string) (*PrekeyBundle, error) {
	spk, err := m.GetActiveSignedPrekey(ctx, targetNxId, targetDeviceId)
	if err != nil {
		return nil, err
	}

	binding, err := m.GetIdentityKeyBinding(ctx, targetNxId, spk.DeviceID)
	if err != nil {
		return nil, err
	}

	return &PrekeyBundle{
		NxID:                  targetNxId,
		DeviceID:              spk.DeviceID,
		IdentityKeyHex:        binding.DhPublicKeyHex,
		SigningKeyHex:         binding.SigningPublicKeyHex,
		SignedPrekeyHex:       spk.PublicKeyHex,
		SignedPrekeyID:        spk.KeyID,
		SignedPrekeySignature: spk.SignatureHex,
	}, nil
}

func (m *MemoryPrekeyRepository) ClaimPrekey(ctx context.Context, initiatorNxId, initiatorDeviceId string, req *ClaimRequest) (*ClaimResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	targetNxId := req.RecipientNxID
	if targetNxId == "" {
		targetNxId = req.TargetNxID
	}
	now := time.Now().UnixMilli()
	expiresAt := now + ClaimReservationWindowMs

	// 1. Idempotency Check
	if existing, found := m.claims[req.ClaimID]; found {
		if existing.InitiatorNxID != initiatorNxId || existing.InitiatorDeviceID != initiatorDeviceId || existing.TargetNxID != targetNxId {
			return nil, ErrClaimOwnershipViolation
		}

		if now > existing.ExpiresAt || existing.Status == "EXPIRED" {
			existing.Status = "EXPIRED"
			if existing.OpkID != "" {
				m.burnOpk(existing.OpkID)
			}
			return nil, ErrClaimExpired
		}

		if existing.Status == "CONSUMED" {
			return nil, ErrClaimAlreadyConsumed
		}

		// Idempotent retry: Return same keys
		return m.buildClaimResponse(existing)
	}

	// 2. Locate Target SPK & Binding
	targetKey := m.key(targetNxId, req.TargetDeviceID)
	var spk *SignedPrekey
	if list, ok := m.signedPrekeys[targetKey]; ok {
		for i := len(list) - 1; i >= 0; i-- {
			if list[i].IsActive {
				spk = list[i]
				break
			}
		}
	}
	if spk == nil {
		for _, list := range m.signedPrekeys {
			for i := len(list) - 1; i >= 0; i-- {
				if list[i].NxID == targetNxId && list[i].IsActive {
					spk = list[i]
					break
				}
			}
			if spk != nil {
				break
			}
		}
	}
	if spk == nil {
		return nil, ErrNoActiveSignedPrekey
	}

	targetDevId := spk.DeviceID
	binding := m.bindings[m.key(targetNxId, targetDevId)]
	if binding == nil {
		for _, b := range m.bindings {
			if b.NxID == targetNxId {
				binding = b
				break
			}
		}
	}
	if binding == nil {
		return nil, ErrPrekeyNotFound
	}

	// 3. Select lowest key_id AVAILABLE OPK (Simulating FOR UPDATE SKIP LOCKED)
	var allocatedOpk *OneTimePrekey
	targetOpkKey := m.key(targetNxId, targetDevId)
	if opkList, ok := m.oneTimePrekeys[targetOpkKey]; ok {
		sort.Slice(opkList, func(i, j int) bool { return opkList[i].KeyID < opkList[j].KeyID })
		for _, opk := range opkList {
			if opk.Status == "AVAILABLE" {
				allocatedOpk = opk
				break
			}
		}
	}

	var opkId string
	var opkKeyId *int
	var opkPub *string

	if allocatedOpk != nil {
		allocatedOpk.Status = "CLAIMED"
		allocatedOpk.ClaimID = req.ClaimID
		allocatedOpk.ClaimedByNxID = initiatorNxId
		allocatedOpk.ClaimedByDeviceID = initiatorDeviceId
		allocatedOpk.ClaimedAt = now
		allocatedOpk.ExpiresAt = expiresAt

		opkId = allocatedOpk.ID
		opkKeyId = &allocatedOpk.KeyID
		pubCopy := allocatedOpk.PublicKeyHex
		opkPub = &pubCopy
	}

	claim := &PrekeyClaim{
		ClaimID:           req.ClaimID,
		InitiatorNxID:     initiatorNxId,
		InitiatorDeviceID: initiatorDeviceId,
		TargetNxID:        targetNxId,
		TargetDeviceID:    targetDevId,
		OpkID:             opkId,
		OpkKeyID:          opkKeyId,
		SpkID:             spk.ID,
		CreatedAt:         now,
		ExpiresAt:         expiresAt,
		Status:            "ACTIVE",
	}
	m.claims[req.ClaimID] = claim

	return &ClaimResponse{
		ClaimID:               req.ClaimID,
		InitiatorNxID:         initiatorNxId,
		InitiatorDeviceID:     initiatorDeviceId,
		TargetNxID:            targetNxId,
		TargetDeviceID:        targetDevId,
		IdentityKeyHex:        binding.DhPublicKeyHex,
		SigningKeyHex:         binding.SigningPublicKeyHex,
		SignedPrekeyHex:       spk.PublicKeyHex,
		SignedPrekeyID:        spk.KeyID,
		SignedPrekeySignature: spk.SignatureHex,
		OneTimePrekeyHex:      opkPub,
		OneTimePrekeyID:       opkKeyId,
		ExpiresAt:             expiresAt,
	}, nil
}

func (m *MemoryPrekeyRepository) buildClaimResponse(claim *PrekeyClaim) (*ClaimResponse, error) {
	binding := m.bindings[m.key(claim.TargetNxID, claim.TargetDeviceID)]
	identKey, signKey := "", ""
	if binding != nil {
		identKey = binding.DhPublicKeyHex
		signKey = binding.SigningPublicKeyHex
	}

	var spk *SignedPrekey
	for _, list := range m.signedPrekeys {
		for _, s := range list {
			if s.ID == claim.SpkID {
				spk = s
				break
			}
		}
	}
	if spk == nil {
		return nil, ErrNoActiveSignedPrekey
	}

	var opkPub *string
	if claim.OpkID != "" {
		for _, list := range m.oneTimePrekeys {
			for _, o := range list {
				if o.ID == claim.OpkID {
					pub := o.PublicKeyHex
					opkPub = &pub
					break
				}
			}
		}
	}

	return &ClaimResponse{
		ClaimID:               claim.ClaimID,
		InitiatorNxID:         claim.InitiatorNxID,
		InitiatorDeviceID:     claim.InitiatorDeviceID,
		TargetNxID:            claim.TargetNxID,
		TargetDeviceID:        claim.TargetDeviceID,
		IdentityKeyHex:        identKey,
		SigningKeyHex:         signKey,
		SignedPrekeyHex:       spk.PublicKeyHex,
		SignedPrekeyID:        spk.KeyID,
		SignedPrekeySignature: spk.SignatureHex,
		OneTimePrekeyHex:      opkPub,
		OneTimePrekeyID:       claim.OpkKeyID,
		ExpiresAt:             claim.ExpiresAt,
	}, nil
}

func (m *MemoryPrekeyRepository) burnOpk(opkId string) {
	for _, list := range m.oneTimePrekeys {
		for _, o := range list {
			if o.ID == opkId {
				o.Status = "BURNED"
				return
			}
		}
	}
}

func (m *MemoryPrekeyRepository) ConsumeClaim(ctx context.Context, claimId, initiatorNxId string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	claim, found := m.claims[claimId]
	if !found {
		return ErrClaimNotFound
	}
	if claim.InitiatorNxID != initiatorNxId {
		return ErrClaimOwnershipViolation
	}
	if claim.Status == "CONSUMED" {
		return nil // Idempotent success
	}
	now := time.Now().UnixMilli()
	if now > claim.ExpiresAt || claim.Status == "EXPIRED" {
		claim.Status = "EXPIRED"
		if claim.OpkID != "" {
			m.burnOpk(claim.OpkID)
		}
		return ErrClaimExpired
	}

	claim.Status = "CONSUMED"
	if claim.OpkID != "" {
		for _, list := range m.oneTimePrekeys {
			for _, o := range list {
				if o.ID == claim.OpkID {
					o.Status = "CONSUMED"
					o.ConsumedAt = now
					break
				}
			}
		}
	}
	return nil
}

func (m *MemoryPrekeyRepository) Close() error {
	return nil
}
