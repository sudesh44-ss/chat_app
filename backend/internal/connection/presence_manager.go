package connection

import (
	"context"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"nexa/wire/internal/wire"
)

const (
	DefaultPresenceTTL = 90 * time.Second
	OfflinePresenceTTL = 10 * time.Second
)

// PresenceManager manages user active status in Redis with privacy reciprocity.
type PresenceManager struct {
	mu          sync.RWMutex
	localStatus map[string]wire.PresenceStatus
	localExpiry map[string]time.Time
	redisClient *redis.Client
}

func NewPresenceManager(redisClient *redis.Client) *PresenceManager {
	return &PresenceManager{
		localStatus: make(map[string]wire.PresenceStatus),
		localExpiry: make(map[string]time.Time),
		redisClient: redisClient,
	}
}

// SetPresence records presence status for an NX-ID with TTL.
func (pm *PresenceManager) SetPresence(ctx context.Context, nxId string, status wire.PresenceStatus, ttl time.Duration) error {
	pm.mu.Lock()
	pm.localStatus[nxId] = status
	pm.localExpiry[nxId] = time.Now().Add(ttl)
	pm.mu.Unlock()

	if pm.redisClient != nil {
		key := "presence:" + nxId
		return pm.redisClient.Set(ctx, key, int(status), ttl).Err()
	}
	return nil
}

// GetPresence retrieves presence status for an NX-ID.
func (pm *PresenceManager) GetPresence(ctx context.Context, nxId string) (wire.PresenceStatus, error) {
	if pm.redisClient != nil {
		key := "presence:" + nxId
		val, err := pm.redisClient.Get(ctx, key).Result()
		if err == nil {
			statusInt, _ := strconv.Atoi(val)
			return wire.PresenceStatus(statusInt), nil
		}
	}

	pm.mu.RLock()
	defer pm.mu.RUnlock()

	expiry, exists := pm.localExpiry[nxId]
	if !exists || time.Now().After(expiry) {
		return wire.PresenceStatus_PRESENCE_OFFLINE, nil
	}
	return pm.localStatus[nxId], nil
}

// CanViewPeerPresence enforces the reciprocity rule:
// A user who sets their presence to HIDDEN (Active Status: OFF) cannot view others' active status.
func (pm *PresenceManager) CanViewPeerPresence(ctx context.Context, viewerNxId, peerNxId string) (bool, error) {
	viewerStatus, err := pm.GetPresence(ctx, viewerNxId)
	if err != nil {
		return false, err
	}

	// Reciprocity: If viewer is hidden, viewer cannot observe peer
	if viewerStatus == wire.PresenceStatus_PRESENCE_HIDDEN {
		return false, nil
	}

	// Peer must be online (and not hidden)
	peerStatus, err := pm.GetPresence(ctx, peerNxId)
	if err != nil {
		return false, err
	}

	if peerStatus == wire.PresenceStatus_PRESENCE_ONLINE {
		return true, nil
	}

	return false, nil
}
