package connections

import (
	"strings"
	"time"
)

const (
	RequestStatusPending   = "PENDING"
	RequestStatusAccepted  = "ACCEPTED"
	RequestStatusRejected  = "REJECTED"
	RequestStatusCancelled = "CANCELLED"
	RequestStatusExpired   = "EXPIRED"

	ConnectionStatusActive  = "ACTIVE"
	ConnectionStatusRemoved = "REMOVED"

	DefaultRequestTTL = 7 * 24 * time.Hour // 7 days strict TTL
)

// PublicProfile represents permitted, non-sensitive public user metadata.
// Strict Privacy: Never exposes email, domestic phone numbers, private keys, or internal account IDs.
type PublicProfile struct {
	NxID         string `json:"nx_id"`
	DisplayName  string `json:"display_name"`
	AvatarUrl    string `json:"avatar_url,omitempty"`
	Bio          string `json:"bio,omitempty"`
	PublicKeyHex string `json:"public_key_hex"`
	IsConnected  bool   `json:"is_connected"`
	IsPending    bool   `json:"is_pending"`
	IsBlocked    bool   `json:"is_blocked"`
	CreatedAt    int64  `json:"created_at"`
}

// ConnectionRequest represents an asymmetric invitation to connect between two users.
type ConnectionRequest struct {
	ID           string `json:"id"`
	SenderNxID   string `json:"sender_nx_id"`
	RecipientNxID string `json:"recipient_nx_id"`
	IntroMessage string `json:"intro_message,omitempty"`
	Status       string `json:"status"` // PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
	ExpiresAt    int64  `json:"expires_at"`
}

// ConnectionRequestWithProfile augments a ConnectionRequest with counterpart public metadata.
type ConnectionRequestWithProfile struct {
	ConnectionRequest
	Profile *PublicProfile `json:"profile"`
}

// Connection represents an established, bilateral relationship.
type Connection struct {
	ID        string `json:"id"`
	UserANxID string `json:"user_a_nx_id"`
	UserBNxID string `json:"user_b_nx_id"`
	Status    string `json:"status"` // ACTIVE, REMOVED
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
}

// CanonicalPair returns users in strictly canonical lexicographical order: userA < userB.
func CanonicalPair(user1, user2 string) (string, string) {
	u1 := strings.TrimSpace(user1)
	u2 := strings.TrimSpace(user2)
	if u1 < u2 {
		return u1, u2
	}
	return u2, u1
}
