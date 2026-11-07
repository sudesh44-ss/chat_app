package connection

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	// ClusterEventsChannel is the Redis Pub/Sub channel used for cluster-wide notifications & session invalidation.
	ClusterEventsChannel = "nexa:events:cluster"

	EventDeviceRevoked      = "device:revoked"
	EventSessionRevoked     = "session:revoked"
	EventConnectionRequest  = "connection:request"
	EventConnectionAccepted = "connection:accepted"
	EventConnectionRejected = "connection:rejected"
	EventContactRemoved     = "contact:removed"
	EventUserBlocked        = "user:blocked"
	EventUserUnblocked      = "user:unblocked"
)

// ClusterEvent defines cross-instance messages distributed via Redis Pub/Sub.
type ClusterEvent struct {
	EventID          string      `json:"event_id"`
	Type             string      `json:"type"`
	OriginInstanceID string      `json:"origin_instance_id"`
	NxID             string      `json:"nx_id"`
	DeviceID         string      `json:"device_id,omitempty"`
	Payload          interface{} `json:"payload,omitempty"`
	Timestamp        int64       `json:"timestamp"`
}

func generateID(prefix string) string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%s_%d_%s", prefix, time.Now().UnixMilli(), hex.EncodeToString(b))
}

// SetRedisClient configures the Redis client for cross-instance Pub/Sub messaging.
func (h *Hub) SetRedisClient(client *redis.Client) {
	h.redisClient = client
}

// SetInstanceID overrides the instance identifier (useful for multi-instance tests).
func (h *Hub) SetInstanceID(id string) {
	h.instanceID = id
}

// GetInstanceID returns the unique instance identifier.
func (h *Hub) GetInstanceID() string {
	return h.instanceID
}

// StartClusterSubscriber starts a background listener for multi-instance events.
func (h *Hub) StartClusterSubscriber(ctx context.Context, client *redis.Client) error {
	if client == nil {
		log.Println("[Hub] Warning: No Redis client provided for cluster subscriber. Running in single-instance mode.")
		return nil
	}

	h.redisClient = client
	pubsub := client.Subscribe(ctx, ClusterEventsChannel)

	// Verify subscription connectivity
	_, err := pubsub.Receive(ctx)
	if err != nil {
		log.Printf("[Hub] Cluster subscriber subscription error: %v", err)
		return err
	}

	subCtx, cancel := context.WithCancel(ctx)
	h.pubsubCancel = cancel

	go h.clusterSubscriberLoop(subCtx, pubsub)
	log.Printf("[Hub] Cluster subscriber active on channel '%s' (Instance ID: %s)", ClusterEventsChannel, h.instanceID)
	return nil
}

// StopClusterSubscriber stops the background cluster subscriber.
func (h *Hub) StopClusterSubscriber() {
	if h.pubsubCancel != nil {
		h.pubsubCancel()
	}
}

func (h *Hub) clusterSubscriberLoop(ctx context.Context, pubsub *redis.PubSub) {
	defer pubsub.Close()
	ch := pubsub.Channel()

	for {
		select {
		case <-ctx.Done():
			log.Printf("[Hub] Stopping cluster subscriber for instance %s", h.instanceID)
			return
		case msg, ok := <-ch:
			if !ok {
				log.Printf("[Hub] Cluster subscriber channel closed for instance %s", h.instanceID)
				return
			}
			h.handleClusterMessage(msg.Payload)
		}
	}
}

func (h *Hub) handleClusterMessage(payload string) {
	var event ClusterEvent
	if err := json.Unmarshal([]byte(payload), &event); err != nil {
		log.Printf("[Hub] Error unmarshaling cluster event: %v", err)
		return
	}

	// Echo prevention: Ignore events originating from this instance
	if event.OriginInstanceID == h.instanceID {
		return
	}

	log.Printf("[Hub] Received cluster event '%s' for NX-ID %s (Origin: %s)", event.Type, event.NxID, event.OriginInstanceID)

	switch event.Type {
	case EventDeviceRevoked, EventSessionRevoked:
		reason := "Device session revoked by user"
		if r, ok := event.Payload.(string); ok && r != "" {
			reason = r
		}
		revokedCount := h.RevokeDeviceSessionLocal(event.NxID, event.DeviceID, reason)
		log.Printf("[Hub] Cluster revocation executed for NX-ID %s Device %s: %d local sessions closed", event.NxID, event.DeviceID, revokedCount)

	default:
		// Connection, contact, block events or general notifications
		if strings.HasPrefix(event.Type, "connection:") ||
			strings.HasPrefix(event.Type, "contact:") ||
			strings.HasPrefix(event.Type, "user:") {
			h.broadcastLocal(event.NxID, event.Type, event.Payload)
		}
	}
}

// RevokeDeviceSession terminates any active WebSocket session for a specific device (or all devices of user if deviceId is empty)
// and propagates the revocation event to all instances across the Redis cluster.
func (h *Hub) RevokeDeviceSession(nxId string, deviceId string, reason string) error {
	// 1. Evict locally
	h.RevokeDeviceSessionLocal(nxId, deviceId, reason)

	// 2. Publish to Redis Pub/Sub cluster
	if h.redisClient != nil {
		event := ClusterEvent{
			EventID:          generateID("evt_rev"),
			Type:             EventDeviceRevoked,
			OriginInstanceID: h.instanceID,
			NxID:             nxId,
			DeviceID:         deviceId,
			Payload:          reason,
			Timestamp:        time.Now().UnixMilli(),
		}
		data, err := json.Marshal(event)
		if err != nil {
			return err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := h.redisClient.Publish(ctx, ClusterEventsChannel, data).Err(); err != nil {
			log.Printf("[Hub] Failed to publish revocation event to cluster: %v", err)
			return err
		}
		log.Printf("[Hub] Published device revocation for NX-ID %s (Device: %s) to cluster channel %s", nxId, deviceId, ClusterEventsChannel)
	}

	return nil
}

// RevokeDeviceSessionLocal terminates matching active WebSocket sessions in local memory.
func (h *Hub) RevokeDeviceSessionLocal(nxId string, deviceId string, reason string) int {
	h.sessionsMu.Lock()
	defer h.sessionsMu.Unlock()

	devices, ok := h.sessions[nxId]
	if !ok || len(devices) == 0 {
		return 0
	}

	var sessionsToClose []*ClientSession

	if deviceId != "" && deviceId != "*" {
		if session, found := devices[deviceId]; found {
			sessionsToClose = append(sessionsToClose, session)
			delete(devices, deviceId)
		}
	} else {
		// Wildcard / all devices for this account
		for devId, session := range devices {
			sessionsToClose = append(sessionsToClose, session)
			delete(devices, devId)
		}
	}

	if len(devices) == 0 {
		delete(h.sessions, nxId)
	}

	// Close sessions outside of lock
	for _, s := range sessionsToClose {
		log.Printf("[Hub] Actively evicting WebSocket session for %s (Device: %s): %s", s.NxId, s.DeviceId, reason)
		s.CloseWithUnauthorized(reason)
		func() {
			defer func() { _ = recover() }()
			close(s.Send)
		}()
	}

	// Remove corresponding blind tokens
	if len(sessionsToClose) > 0 {
		h.blindTokensMu.Lock()
		for _, s := range sessionsToClose {
			for token, sess := range h.blindTokens {
				if sess == s {
					delete(h.blindTokens, token)
				}
			}
		}
		h.blindTokensMu.Unlock()
	}

	return len(sessionsToClose)
}
