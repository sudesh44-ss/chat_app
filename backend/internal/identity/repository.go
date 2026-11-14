package identity

// IdentityRepository defines the authoritative persistence contract for NEXA identity,
// accounts, devices, cryptographic keys, and sessions.
// Both Store (JSON file-backed) and PostgresRepository implement this interface.
type IdentityRepository interface {
	// AllocateNxID generates or retrieves a unique NX-ID in locked NX-XXXXXXXX format
	// bound to the given Ed25519 public key hex.
	AllocateNxID(pubKeyHex string) (string, error)

	// CreateAccount registers a new UserAccount. Fails if NX-ID or public key exists.
	CreateAccount(account *UserAccount) error

	// Account Lookups
	GetAccountByNxID(nxID string) (*UserAccount, bool)
	GetAccountByPhone(phone string) (*UserAccount, bool)
	GetAccountByGoogleID(googleID string) (*UserAccount, bool)
	GetAccountBySupabaseUID(supabaseUID string) (*UserAccount, bool)
	GetAccountByPublicKey(pubKeyHex string) (*UserAccount, bool)

	// UpdateAccount updates profile metadata and secondary identities.
	UpdateAccount(account *UserAccount) error

	// LookupVerifiedPhoneNumbers provides privacy-preserving phone number discovery.
	// Never stores or leaks non-matching query numbers.
	LookupVerifiedPhoneNumbers(phoneNumbers []string) []*ContactMatch

	// Device Management
	RegisterDevice(d *Device) error
	// RegisterDeviceReplacingOthers makes d the account's single active device,
	// revoking every other active device and its sessions in the same operation.
	// It returns the revoked device IDs. Used by login/registration paths.
	RegisterDeviceReplacingOthers(d *Device, reason string) ([]string, error)
	GetDevice(deviceID string) (*Device, bool)
	GetDevicesForAccount(nxID string) []*Device
	// GetAllDevicesForAccount includes revoked devices so callers can present
	// revocation history.
	GetAllDevicesForAccount(nxID string) []*Device
	GetDevices(nxID string) ([]*Device, error)
	RevokeDevice(deviceID string) error
	RevokeDeviceForUser(nxID string, deviceID string) error
	UpdateDeviceFcmToken(deviceID string, token string) error

	// Device audit trail (append-only).
	RecordDeviceEvent(event *DeviceEvent) error
	GetDeviceEvents(nxID string, limit int) ([]*DeviceEvent, error)

	// Session Management
	CreateSession(sess *Session) error
	GetSession(token string) (*Session, bool)
	RevokeSession(token string) error
	// RevokeSessionsForDevice revokes active sessions for an account/device,
	// keeping exceptToken (when non-empty) active. When createdBefore is
	// non-zero, only sessions created strictly before that instant are revoked,
	// which lets callers issue a replacement token first and then retire older
	// tokens without racing concurrent rotations. Returns how many were revoked.
	RevokeSessionsForDevice(nxID string, deviceID string, exceptToken string, createdBefore int64) (int, error)

	// System & Health
	CountAccounts() (int, error)
	Close() error
}
