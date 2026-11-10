package connections

import (
	"context"
	"errors"
	"time"
)

var (
	ErrSelfRequest          = errors.New("cannot send connection request to self")
	ErrSelfBlock            = errors.New("cannot block self")
	ErrUserNotFound         = errors.New("user not found")
	ErrInvalidNxID          = errors.New("invalid NX-ID format")
	ErrBlockedByUser        = errors.New("cannot interact with blocked user")
	ErrAlreadyConnected     = errors.New("users are already connected")
	ErrPendingRequestExists = errors.New("a pending connection request already exists")
	ErrRequestNotFound      = errors.New("connection request not found")
	ErrRequestNotPending    = errors.New("connection request is not in pending status")
	ErrRequestExpired       = errors.New("connection request has expired")
	ErrUnauthorizedAction   = errors.New("unauthorized action on connection request")
	ErrConnectionNotFound   = errors.New("active connection not found")
)

// ConnectionRepository defines the persistence contract for NEXA contacts, connections, and blocks.
type ConnectionRepository interface {
	// SearchUserByNxID returns permitted public profile metadata for target NX-ID.
	// Returns ErrUserNotFound if nonexistent or blocked.
	SearchUserByNxID(ctx context.Context, requesterNxID, targetNxID string) (*PublicProfile, error)

	// SendRequest creates a pending connection request between sender and recipient.
	SendRequest(ctx context.Context, senderNxID, recipientNxID, introMessage string, ttl time.Duration) (*ConnectionRequest, error)

	// GetIncomingRequests retrieves pending requests where user is recipient.
	GetIncomingRequests(ctx context.Context, recipientNxID string, limit, offset int) ([]*ConnectionRequestWithProfile, error)

	// GetOutgoingRequests retrieves pending requests initiated by user.
	GetOutgoingRequests(ctx context.Context, senderNxID string, limit, offset int) ([]*ConnectionRequestWithProfile, error)

	// AcceptRequest atomically accepts a pending request and creates a bilateral connection.
	AcceptRequest(ctx context.Context, recipientNxID, requestID string) (*Connection, error)

	// RejectRequest marks a pending request as REJECTED.
	RejectRequest(ctx context.Context, recipientNxID, requestID string) error

	// CancelRequest marks a pending request as CANCELLED.
	CancelRequest(ctx context.Context, senderNxID, requestID string) error

	// ListConnections returns all active contacts for user.
	ListConnections(ctx context.Context, userNxID string, limit, offset int) ([]*PublicProfile, error)

	// RemoveConnection transitions an active connection to REMOVED status.
	RemoveConnection(ctx context.Context, userNxID, targetNxID string) error

	// BlockUser records a block relationship and terminates any active connection.
	BlockUser(ctx context.Context, blockerNxID, blockedNxID string) error

	// UnblockUser removes a block relationship.
	UnblockUser(ctx context.Context, blockerNxID, blockedNxID string) error

	// ListBlockedUsers returns public profiles of all users blocked by blocker.
	ListBlockedUsers(ctx context.Context, blockerNxID string) ([]*PublicProfile, error)

	// IsBlocked checks if a block relationship exists in either direction.
	IsBlocked(ctx context.Context, userA, userB string) (bool, error)

	// IsConnected checks if an active bilateral connection exists between two users.
	IsConnected(ctx context.Context, userA, userB string) (bool, error)

	// SyncQrHandshake creates or confirms an instant mutual connection established via QR scan.
	SyncQrHandshake(ctx context.Context, scannerNxID, scannedNxID string) (*Connection, error)

	// SetPhoneVisibility creates or updates an explicit directional phone-sharing grant.
	SetPhoneVisibility(ctx context.Context, granterNxID, granteeNxID string, isActive bool) error

	// HasPhoneVisibility returns true if granterNxID has granted phone visibility to granteeNxID.
	HasPhoneVisibility(ctx context.Context, granterNxID, granteeNxID string) (bool, error)

	// ListPhoneVisibilityGrants returns all target NX-IDs to which user has granted phone visibility.
	ListPhoneVisibilityGrants(ctx context.Context, granterNxID string) ([]string, error)

	// GetMutualConnections returns mutual connections shared between userA and userB.
	GetMutualConnections(ctx context.Context, userA, userB string, limit, offset int) ([]*PublicProfile, int, error)
}
