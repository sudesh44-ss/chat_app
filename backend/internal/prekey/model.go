package prekey

import "errors"

var (
	ErrIdentityKeyMismatch     = errors.New("identity signing key mismatch")
	ErrInvalidSignature        = errors.New("invalid cryptographic Ed25519 signature")
	ErrNoActiveSignedPrekey    = errors.New("target device has no active signed prekey")
	ErrPrekeyNotFound          = errors.New("prekey bundle not found")
	ErrClaimNotFound           = errors.New("prekey claim not found")
	ErrClaimExpired            = errors.New("prekey claim has expired and its one-time prekey is burned")
	ErrClaimAlreadyConsumed    = errors.New("prekey claim has already been consumed")
	ErrClaimOwnershipViolation = errors.New("claim ownership violation: caller does not match claim initiator")
	ErrOpkAlreadyDisclosed     = errors.New("one-time prekey has already been disclosed and cannot be reassigned")
	ErrRateLimitExceeded       = errors.New("rate limit exceeded for prekey operations")
	ErrInvalidKeySize          = errors.New("invalid public key size: must be 32 bytes (64 hex characters)")
)

// IdentityKeyBinding represents an authenticated X25519 identity DH public key bound to an Ed25519 signing key.
type IdentityKeyBinding struct {
	ID                  string `json:"id"`
	AccountID           string `json:"account_id"`
	NxID                string `json:"nx_id"`
	DeviceID            string `json:"device_id"`
	SigningPublicKeyHex string `json:"signing_public_key"` // 32-byte Ed25519
	DhPublicKeyHex      string `json:"dh_public_key"`      // 32-byte X25519
	SignatureHex        string `json:"signature"`          // 64-byte Ed25519 signature over canonical TLV
	Verified            bool   `json:"verified"`
	CreatedAt           int64  `json:"created_at"`
}

// SignedPrekey represents a medium-term X25519 prekey certified by the device Ed25519 signing key.
type SignedPrekey struct {
	ID           string `json:"id"`
	AccountID    string `json:"account_id"`
	NxID         string `json:"nx_id"`
	DeviceID     string `json:"device_id"`
	KeyID        int    `json:"key_id"`
	PublicKeyHex string `json:"public_key"` // 32-byte X25519
	SignatureHex string `json:"signature"`  // 64-byte Ed25519 signature over canonical TLV
	IsActive     bool   `json:"is_active"`
	CreatedAt    int64  `json:"created_at"`
	ExpiresAt    int64  `json:"expires_at"`
}

// OneTimePrekey represents a single-use X25519 prekey.
type OneTimePrekey struct {
	ID                string `json:"id"`
	AccountID         string `json:"account_id"`
	NxID              string `json:"nx_id"`
	DeviceID          string `json:"device_id"`
	KeyID             int    `json:"key_id"`
	PublicKeyHex      string `json:"public_key"` // 32-byte X25519
	Status            string `json:"status"`     // AVAILABLE, CLAIMED, CONSUMED, BURNED
	ClaimID           string `json:"claim_id,omitempty"`
	ClaimedByNxID     string `json:"claimed_by_nx_id,omitempty"`
	ClaimedByDeviceID string `json:"claimed_by_device_id,omitempty"`
	ClaimedAt         int64  `json:"claimed_at,omitempty"`
	ConsumedAt        int64  `json:"consumed_at,omitempty"`
	ExpiresAt         int64  `json:"expires_at,omitempty"`
	CreatedAt         int64  `json:"created_at"`
}

// PrekeyBundle represents the public prekey bundle queried via GET.
// Crucial: GET is 100% idempotent and NEVER returns or consumes a One-Time Prekey!
type PrekeyBundle struct {
	NxID                   string `json:"nx_id"`
	DeviceID               string `json:"device_id"`
	IdentityKeyHex         string `json:"identity_key"`        // 32-byte X25519 DH public key
	SigningKeyHex          string `json:"signing_key"`         // 32-byte Ed25519 signing public key
	SignedPrekeyHex        string `json:"signed_prekey"`       // 32-byte X25519 signed prekey
	SignedPrekeyID         int    `json:"signed_prekey_id"`
	SignedPrekeySignature  string `json:"signed_prekey_sig"`   // 64-byte Ed25519 signature
}

// ClaimRequest is submitted to POST /api/v1/prekeys/claim to atomically reserve an OPK.
type ClaimRequest struct {
	RecipientNxID  string `json:"recipient_nx_id"`
	TargetNxID     string `json:"target_nx_id,omitempty"` // Alias for RecipientNxID
	TargetDeviceID string `json:"target_device_id,omitempty"`
	ClaimID        string `json:"claim_id"` // Client UUIDv4 idempotency key
}

// ClaimResponse is returned from POST /api/v1/prekeys/claim upon atomic OPK disclosure.
type ClaimResponse struct {
	ClaimID               string  `json:"claim_id"`
	InitiatorNxID         string  `json:"initiator_nx_id"`
	InitiatorDeviceID     string  `json:"initiator_device_id"`
	TargetNxID            string  `json:"target_nx_id"`
	TargetDeviceID        string  `json:"target_device_id"`
	IdentityKeyHex        string  `json:"identity_key"`
	SigningKeyHex         string  `json:"signing_key"`
	SignedPrekeyHex       string  `json:"signed_prekey"`
	SignedPrekeyID        int     `json:"signed_prekey_id"`
	SignedPrekeySignature string  `json:"signed_prekey_sig"`
	OneTimePrekeyHex      *string `json:"one_time_prekey"` // Disclosed once; null if pool exhausted
	OneTimePrekeyID       *int    `json:"one_time_prekey_id"`
	ExpiresAt             int64   `json:"expires_at"` // 5-minute reservation deadline
}

// PrekeyClaim tracks the claim record in the database.
type PrekeyClaim struct {
	ClaimID           string `json:"claim_id"`
	InitiatorNxID     string `json:"initiator_nx_id"`
	InitiatorDeviceID string `json:"initiator_device_id"`
	TargetNxID        string `json:"target_nx_id"`
	TargetDeviceID    string `json:"target_device_id"`
	OpkID             string `json:"opk_id,omitempty"`
	OpkKeyID          *int   `json:"opk_key_id,omitempty"`
	SpkID             string `json:"spk_id"`
	CreatedAt         int64  `json:"created_at"`
	ExpiresAt         int64  `json:"expires_at"`
	Status            string `json:"status"` // ACTIVE, CONSUMED, EXPIRED
}

// UploadIdentityKeyRequest represents the payload for POST /api/v1/users/identity-key.
type UploadIdentityKeyRequest struct {
	DhPublicKey string `json:"dh_public_key"`
	Signature   string `json:"signature"`
	Timestamp   int64  `json:"timestamp"`
}

// UploadSignedPrekeyRequest represents the payload for POST /api/v1/users/prekeys/signed.
type UploadSignedPrekeyRequest struct {
	KeyID     int    `json:"key_id"`
	PublicKey string `json:"public_key"`
	Signature string `json:"signature"`
	Timestamp int64  `json:"timestamp"`
}

// UploadOneTimePrekeysRequest represents the payload for POST /api/v1/users/prekeys/one-time.
type UploadOneTimePrekeysRequest struct {
	Prekeys []OneTimePrekeyItem `json:"prekeys"`
}

type OneTimePrekeyItem struct {
	KeyID     int    `json:"key_id"`
	PublicKey string `json:"public_key"`
}

// CombinedPrekeyUploadRequest represents the all-in-one payload for POST /api/v1/users/prekey.
type CombinedPrekeyUploadRequest struct {
	NxID             string              `json:"nx_id,omitempty"`
	DeviceID         string              `json:"device_id,omitempty"`
	IdentityKey      string              `json:"identity_key,omitempty"`
	IdentityKeySig   string              `json:"identity_key_sig,omitempty"`
	SigningKey       string              `json:"signing_key,omitempty"`
	SignedPrekey     string              `json:"signed_prekey"`
	SignedPrekeyID   int                 `json:"signed_prekey_id"`
	SignedPrekeySig  string              `json:"signed_prekey_sig"`
	Timestamp        int64               `json:"timestamp,omitempty"`
	OneTimePrekeys   []OneTimePrekeyItem `json:"one_time_prekeys,omitempty"`
}
