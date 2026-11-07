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
	GetDevice(deviceID string) (*Device, bool)
	GetDevicesForAccount(nxID string) []*Device
	GetDevices(nxID string) ([]*Device, error)
	RevokeDevice(deviceID string) error
	RevokeDeviceForUser(nxID string, deviceID string) error
	UpdateDeviceFcmToken(deviceID string, token string) error

	// Session Management
	CreateSession(sess *Session) error
	GetSession(token string) (*Session, bool)
	RevokeSession(token string) error

	// System & Health
	CountAccounts() (int, error)
	Close() error
}
