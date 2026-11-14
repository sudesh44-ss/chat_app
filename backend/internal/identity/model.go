package identity

type UserAccount struct {
	ID             string `json:"id"`
	SupabaseUserID string `json:"supabase_user_id,omitempty"`
	NxID           string `json:"nx_id"`
	PublicKeyHex   string `json:"public_key_hex"`
	DhKeyHex       string `json:"dh_key_hex,omitempty"`
	PhoneNumber    string `json:"phone_number,omitempty"`
	// PhoneHMAC is the deterministic lookup hash. PhoneEncrypted is the
	// recoverable AES-GCM value. Both are intentionally excluded from JSON so
	// they can never leak through an API response; the JSON store recomputes
	// them from PhoneNumber on load.
	PhoneHMAC             string `json:"-"`
	PhoneEncrypted        string `json:"-"`
	PhoneVerified         bool   `json:"phone_verified"`
	IsDiscoverableByPhone bool   `json:"is_discoverable_by_phone"`
	GoogleID              string `json:"google_id,omitempty"`
	Email                 string `json:"email,omitempty"`
	ProfileName           string `json:"profile_name"`
	AvatarUrl             string `json:"avatar_url,omitempty"`
	Bio                   string `json:"bio,omitempty"`
	Gender                string `json:"gender,omitempty"`
	Age                   int    `json:"age,omitempty"`
	CreatedAt             int64  `json:"created_at"`
	UpdatedAt             int64  `json:"updated_at"`
}

type Device struct {
	DeviceID      string `json:"device_id"`
	AccountID     string `json:"account_id"`
	NxID          string `json:"nx_id"`
	DeviceName    string `json:"device_name"`
	PublicKeyHex  string `json:"public_key_hex"`
	DhKeyHex      string `json:"dh_key_hex,omitempty"`
	FcmToken      string `json:"fcm_token,omitempty"`
	IsRevoked     bool   `json:"is_revoked"`
	RevokedAt     int64  `json:"revoked_at,omitempty"`
	RevokedReason string `json:"revoked_reason,omitempty"`
	CreatedAt     int64  `json:"created_at"`
	LastActiveAt  int64  `json:"last_active_at"`
}

// DeviceEvent is an append-only audit record for device lifecycle changes.
// Revoked devices stay in the device table for history, and every state change
// is also written here so the user can review what happened and why.
type DeviceEvent struct {
	ID        string `json:"id"`
	NxID      string `json:"nx_id"`
	DeviceID  string `json:"device_id"`
	EventType string `json:"event_type"` // REGISTERED, REVOKED, REVOKED_REPLACED
	Reason    string `json:"reason,omitempty"`
	Actor     string `json:"actor,omitempty"` // user, system, account_lifecycle
	CreatedAt int64  `json:"created_at"`
}

type Session struct {
	Token     string `json:"token"`
	AccountID string `json:"account_id"`
	NxID      string `json:"nx_id"`
	DeviceID  string `json:"device_id"`
	ExpiresAt int64  `json:"expires_at"`
	IsRevoked bool   `json:"is_revoked"`
	CreatedAt int64  `json:"created_at"`
}

// ContactMatch represents a minimal match result for phone contact discovery.
// Strict Privacy: Never exposes address book names, internal account IDs, or private data.
type ContactMatch struct {
	PhoneNumber  string `json:"phone_number"`
	NxID         string `json:"nx_id"`
	DisplayName  string `json:"display_name"`
	PublicKeyHex string `json:"public_key_hex"`
}
