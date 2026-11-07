package prekey

import "context"

// PrekeyRepository defines the authoritative persistence contract for prekey and claim lifecycles.
type PrekeyRepository interface {
	// UploadIdentityKeyBinding stores or updates the authenticated X25519 identity key binding.
	UploadIdentityKeyBinding(ctx context.Context, binding *IdentityKeyBinding) error

	// GetIdentityKeyBinding retrieves the authenticated identity key binding for an authorized device.
	GetIdentityKeyBinding(ctx context.Context, nxId, deviceId string) (*IdentityKeyBinding, error)

	// UploadSignedPrekey commits a new active signed prekey, deactivating prior SPKs for that device.
	UploadSignedPrekey(ctx context.Context, spk *SignedPrekey) error

	// GetActiveSignedPrekey retrieves the current active signed prekey for a device.
	GetActiveSignedPrekey(ctx context.Context, nxId, deviceId string) (*SignedPrekey, error)

	// UploadOneTimePrekeys batch inserts up to 100 one-time prekeys with status AVAILABLE.
	UploadOneTimePrekeys(ctx context.Context, opks []*OneTimePrekey) error

	// GetAvailableOpkCount counts the remaining unconsumed, unreserved OPKs for a device.
	GetAvailableOpkCount(ctx context.Context, nxId, deviceId string) (int, error)

	// GetPrekeyBundle returns the public prekey bundle (identity key, signing key, active SPK).
	// Strictly idempotent: NEVER returns or marks an OPK as consumed.
	GetPrekeyBundle(ctx context.Context, targetNxId, targetDeviceId string) (*PrekeyBundle, error)

	// ClaimPrekey atomically reserves and discloses an OPK, adhering strictly to the Mandatory OPK Security Rule:
	// - Once disclosed, an OPK is permanently marked as CLAIMED and can NEVER be assigned to another claim.
	// - Retries with the same claim_id + authenticated owner/device return the identical OPK idempotently.
	// - If the claim has expired, an explicit ErrClaimExpired is returned; the OPK is BURNED, not recycled.
	ClaimPrekey(ctx context.Context, initiatorNxId, initiatorDeviceId string, req *ClaimRequest) (*ClaimResponse, error)

	// ConsumeClaim finalizes consumption of the claimed OPK upon arrival of the initial wire packet.
	ConsumeClaim(ctx context.Context, claimId, initiatorNxId string) error

	// Close terminates any underlying connection pools.
	Close() error
}
