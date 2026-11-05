package identity

type UserAccount struct {
	ID                    string `json:"id"`
	SupabaseUserID        string `json:"supabase_user_id,omitempty"`
	NxID                  string `json:"nx_id"`
	PublicKeyHex          string `json:"public_key_hex"`
	DhKeyHex              string `json:"dh_key_hex,omitempty"`
	PhoneNumber           string `json:"phone_number,omitempty"`
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
	CreatedAt     int64  `json:"created_at"`
	LastActiveAt  int64  `json:"last_active_at"`
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
