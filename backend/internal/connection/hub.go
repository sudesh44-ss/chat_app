package connection

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"log"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"nexa/wire/internal/fcm"
	"nexa/wire/internal/identity"
	"nexa/wire/internal/queue"
	"nexa/wire/internal/wire"

	"github.com/redis/go-redis/v9"
)

type idempotencyEntry struct {
	senderNxId    string
	payloadSha256 string
	expiresAt     int64
}

type typingRateLimiter struct {
	count       int
	windowStart time.Time
}

type Hub struct {
	// Concurrent in-memory routing table mapping NX-ID -> DeviceID -> Active WebSocket session
	sessionsMu sync.RWMutex
	sessions   map[string]map[string]*ClientSession

	// Blind Token mapping: blindTokenHex -> ClientSession
	blindTokensMu sync.RWMutex
	blindTokens   map[string]*ClientSession

	// Ephemeral Redis buffer (TTL: 24h)
	buffer queue.Buffer

	// Redis client for Pub/Sub and cluster distribution
	redisClient  *redis.Client
	instanceID   string
	pubsubCancel context.CancelFunc

	// FCM Service for background wake-up pings
	fcmService *fcm.FcmService

	// Identity store to lookup registered device FCM tokens
	identityStore identity.IdentityRepository

	// Presence manager
	presenceManager *PresenceManager

	// Group membership provider
	groupProvider GroupMembershipProvider

	// Typing rate limiting: session pointer -> lastTypingCount, windowStart
	typingRateMu sync.Mutex
	typingRate   map[*ClientSession]*typingRateLimiter

	// In-memory telemetry & traffic police metrics (Zero DB write IOPS)
	relayedMessagesCount      uint64
	relayedTypingCount        uint64
	offlineBufferedCount      uint64
	offlineFlushedCount       uint64
	ingestedTelemetryBatches  uint64
	queuedMessagesCount       uint64
	claimedInFlightCount      uint64
	acknowledgedDeliveryCount uint64
	revertedDeliveryCount     uint64

	// Connection & blocklist validator for wire authorization
	connValidator ConnectionValidator

	// Authoritative zero-knowledge idempotency tracking (RAM-cached, 7-day TTL)
	idempotency              sync.Map
	duplicateSuppressedCount uint64
}

// ConnectionValidator validates relationship and block status before routing packets.
type ConnectionValidator interface {
	IsBlocked(ctx context.Context, userA, userB string) (bool, error)
	IsConnected(ctx context.Context, userA, userB string) (bool, error)
}

func NewHub(buffer queue.Buffer) *Hub {
	h := &Hub{
		sessions:    make(map[string]map[string]*ClientSession),
		blindTokens: make(map[string]*ClientSession),
		buffer:      buffer,
		instanceID:  generateID("inst"),
		typingRate:  make(map[*ClientSession]*typingRateLimiter),
	}
	if buffer != nil {
		h.redisClient = buffer.GetClient()
	}
	h.presenceManager = NewPresenceManager(h.redisClient)
	h.groupProvider = NewInMemoryGroupProvider()
	return h
}

func (h *Hub) SetFcmService(fcmService *fcm.FcmService) {
	h.fcmService = fcmService
}

func (h *Hub) SetIdentityStore(identityStore identity.IdentityRepository) {
	h.identityStore = identityStore
}

func (h *Hub) SetConnectionValidator(validator ConnectionValidator) {
	h.connValidator = validator
}

func (h *Hub) SetPresenceManager(pm *PresenceManager) {
	h.presenceManager = pm
}

func (h *Hub) SetGroupProvider(gp GroupMembershipProvider) {
	h.groupProvider = gp
}

func (h *Hub) Register(session *ClientSession) {
	h.sessionsMu.Lock()
	if _, ok := h.sessions[session.NxId]; !ok {
		h.sessions[session.NxId] = make(map[string]*ClientSession)
	}
	if existing, exists := h.sessions[session.NxId][session.DeviceId]; exists && existing != session {
		log.Printf("[Hub] Single-active-device policy: closing existing session for %s (Device: %s)", session.NxId, session.DeviceId)
		existing.Close()
	}
	h.sessions[session.NxId][session.DeviceId] = session
	h.sessionsMu.Unlock()

	if h.presenceManager != nil {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_ = h.presenceManager.SetPresence(ctx, session.NxId, wire.PresenceStatus_PRESENCE_ONLINE, DefaultPresenceTTL)
		cancel()
	}

	log.Printf("[Hub] Registered active session for NX-ID: %s (Device: %s)", session.NxId, session.DeviceId)

	// User connected: Flush all pending offline messages from ephemeral Redis
	go h.flushPendingQueue(session.NxId, session)
}

func (h *Hub) RegisterBlindToken(tokenHex string, session *ClientSession) {
	h.blindTokensMu.Lock()
	h.blindTokens[tokenHex] = session
	h.blindTokensMu.Unlock()
	log.Printf("[Hub] Registered blind token %s to session NX-ID: %s", tokenHex[:min(len(tokenHex), 8)], session.NxId)
}

// RegisterRoutingTokens registers a batch of active pairwise rotating routing tokens for an authenticated session.
func (h *Hub) RegisterRoutingTokens(tokenHexes []string, session *ClientSession) {
	if !session.Authenticated {
		log.Printf("[Hub] Security rejection: unauthenticated routing token registration from %s", session.NxId)
		return
	}

	const maxTokensPerSession = 500
	validTokens := make([]string, 0, min(len(tokenHexes), maxTokensPerSession))

	h.blindTokensMu.Lock()
	for _, token := range tokenHexes {
		if len(token) != 32 {
			continue // Token must be exactly 16 bytes (32 hex characters)
		}
		// Anti-hijacking: prevent a different user from overwriting an existing token mapping
		if existing, found := h.blindTokens[token]; found && existing != session && existing.NxId != session.NxId {
			log.Printf("[Hub] Security warning: Rejected token registration %s for %s (already mapped to %s)", token[:8], session.NxId, existing.NxId)
			continue
		}
		h.blindTokens[token] = session
		validTokens = append(validTokens, token)
		if len(validTokens) >= maxTokensPerSession {
			break
		}
	}
	h.blindTokensMu.Unlock()
	log.Printf("[Hub] Registered %d valid pairwise routing tokens to session NX-ID: %s", len(validTokens), session.NxId)

	// Flush any pending offline DTN envelopes stored under these tokens
	if len(validTokens) > 0 {
		go h.FlushTokensQueue(validTokens, session)
	}
}

func (h *Hub) Unregister(session *ClientSession) {
	h.sessionsMu.Lock()
	allOffline := false
	if devices, ok := h.sessions[session.NxId]; ok {
		if existing, found := devices[session.DeviceId]; found && existing == session {
			delete(devices, session.DeviceId)
			func() {
				defer func() { _ = recover() }()
				close(session.Send)
			}()
			log.Printf("[Hub] Unregistered session for NX-ID: %s (Device: %s)", session.NxId, session.DeviceId)
		}
		if len(devices) == 0 {
			delete(h.sessions, session.NxId)
			allOffline = true
		}
	}
	h.sessionsMu.Unlock()

	if allOffline && h.presenceManager != nil {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_ = h.presenceManager.SetPresence(ctx, session.NxId, wire.PresenceStatus_PRESENCE_OFFLINE, OfflinePresenceTTL)
		cancel()
	}

	h.typingRateMu.Lock()
	delete(h.typingRate, session)
	h.typingRateMu.Unlock()

	h.blindTokensMu.Lock()
	for token, s := range h.blindTokens {
		if s == session {
			delete(h.blindTokens, token)
		}
	}
	h.blindTokensMu.Unlock()
}

// RoutePacket executes the blind-postman delivery lifecycle with zero server-side disk storage.
func (h *Hub) RoutePacket(sender *ClientSession, rawBytes []byte) {
	if !sender.Authenticated {
		log.Printf("[Hub] Security rejection: unauthenticated packet from %s", sender.NxId)
		return
	}
	packet, err := wire.Decode(rawBytes)
	if err != nil {
		log.Printf("[Hub] Malformed packet from %s: %v", sender.NxId, err)
		return
	}
	h.RouteDecodedPacket(sender, packet, rawBytes)
}

// RouteDecodedPacket routes a pre-decoded packet to recipient device(s).
func (h *Hub) RouteDecodedPacket(sender *ClientSession, packet *wire.NexaWirePacket, rawBytes []byte) {
	// 1. Handle Ping packets (keep-alive)
	if packet.Type == wire.PacketType_PACKET_PING {
		ackPacket := &wire.NexaWirePacket{
			PacketId:      packet.PacketId,
			Type:          wire.PacketType_PACKET_PING,
			SenderNxId:    "SERVER",
			RecipientNxId: sender.NxId,
			Timestamp:     time.Now().UnixMilli(),
		}
		if encoded, err := ackPacket.Encode(); err == nil {
			sender.SafeSend(encoded)
		}
		return
	}

	// Handle Presence Updates (PACKET_PRESENCE)
	if packet.Type == wire.PacketType_PACKET_PRESENCE && packet.Presence != nil {
		if sender.Authenticated && sender.NxId == packet.SenderNxId {
			status := packet.Presence.Status
			if h.presenceManager != nil {
				ctx, cancel := context.WithTimeout(context.Background(), time.Second)
				_ = h.presenceManager.SetPresence(ctx, sender.NxId, status, DefaultPresenceTTL)
				cancel()
			}
			if packet.RecipientNxId != "" && packet.RecipientNxId != "SERVER" {
				if status == wire.PresenceStatus_PRESENCE_HIDDEN {
					log.Printf("[Hub] Presence reciprocity: dropped presence forward from hidden sender %s", sender.NxId)
					return
				}
				h.sessionsMu.RLock()
				recipientDevices, isOnline := h.sessions[packet.RecipientNxId]
				var targets []*ClientSession
				if isOnline {
					for _, s := range recipientDevices {
						targets = append(targets, s)
					}
				}
				h.sessionsMu.RUnlock()
				for _, s := range targets {
					s.SafeSend(rawBytes)
				}
			}
		}
		return
	}

	// Message Edit & Delete For Everyone Control Events
	if packet.Type == wire.PacketType_PACKET_MESSAGE_EDIT || packet.Type == wire.PacketType_PACKET_MESSAGE_DELETE {
		var targetPacketId string
		if packet.MessageEdit != nil {
			targetPacketId = packet.MessageEdit.TargetPacketId
		} else if packet.MessageDelete != nil {
			targetPacketId = packet.MessageDelete.TargetPacketId
		}

		if targetPacketId == "" {
			log.Printf("[Hub] Security rejection: edit/delete packet with empty targetPacketId from %s", sender.NxId)
			return
		}

		// Verify sender is original sender from idempotency tracking
		if val, exists := h.idempotency.Load(targetPacketId); exists {
			entry := val.(idempotencyEntry)
			if entry.senderNxId != sender.NxId {
				log.Printf("[Hub] Security rejection: user %s tried to edit/delete packet %s originally sent by %s",
					sender.NxId, targetPacketId, entry.senderNxId)
				return
			}
		} else if h.redisClient != nil {
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			val, err := h.redisClient.Get(ctx, "idemp:"+targetPacketId).Result()
			cancel()
			if err == nil {
				parts := strings.Split(val, ":")
				if len(parts) > 0 && parts[0] != sender.NxId {
					log.Printf("[Hub] Security rejection: Redis idemp check: user %s not original sender of %s (%s)",
						sender.NxId, targetPacketId, parts[0])
					return
				}
			}
		}

		// Forward to group or recipient
		if packet.GroupId != "" || strings.HasPrefix(packet.RecipientNxId, "GRP-") {
			groupId := packet.GroupId
			if groupId == "" {
				groupId = packet.RecipientNxId
			}
			h.routeGroupPacket(sender, groupId, packet, rawBytes)
			return
		}

		// Immediate Server Acceptance ACK back to sender
		serverAck := &wire.NexaWirePacket{
			PacketId:      packet.PacketId + "_srv_ack",
			Type:          wire.PacketType_PACKET_ACK,
			SenderNxId:    "SERVER",
			RecipientNxId: sender.NxId,
			Timestamp:     time.Now().UnixMilli(),
			Ack: &wire.DeliveryAckPayload{
				OriginalPacketId: packet.PacketId,
				Status:           wire.AckStatus_ACK_SERVER_RECEIVED,
			},
		}
		if ackBytes, err := serverAck.Encode(); err == nil {
			sender.SafeSend(ackBytes)
		}

		// 1-to-1 forward
		h.sessionsMu.RLock()
		recipientDevices, isOnline := h.sessions[packet.RecipientNxId]
		var targets []*ClientSession
		if isOnline {
			for _, s := range recipientDevices {
				targets = append(targets, s)
			}
		}
		h.sessionsMu.RUnlock()

		if len(targets) > 0 {
			delivered := 0
			for _, s := range targets {
				if s.SafeSend(rawBytes) {
					delivered++
				}
			}
			if delivered == 0 {
				h.bufferOffline(packet.RecipientNxId, rawBytes)
			}
		} else {
			h.bufferOffline(packet.RecipientNxId, rawBytes)
		}
		return
	}

	// Wire-level Connection & Blocklist Authorization (Phase E)
	if h.connValidator != nil && packet.RecipientNxId != "" && packet.RecipientNxId != "SERVER" && packet.RecipientNxId != sender.NxId {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()

		// A. Blocklist Check: Drop packet if communication is blocked in either direction
		blocked, err := h.connValidator.IsBlocked(ctx, sender.NxId, packet.RecipientNxId)
		if err == nil && blocked {
			log.Printf("[Hub] Wire Policy: Packet %s dropped (communication blocked between %s and %s)", packet.PacketId, sender.NxId, packet.RecipientNxId)
			return
		}

		// B. Mutual Connection Check: Reject messages, typing, or peer sync between unverified strangers
		if packet.Type == wire.PacketType_PACKET_MESSAGE || packet.Type == wire.PacketType_PACKET_TYPING || packet.Type == wire.PacketType_PACKET_PEER_SYNC {
			connected, err := h.connValidator.IsConnected(ctx, sender.NxId, packet.RecipientNxId)
			if err != nil || !connected {
				log.Printf("[Hub] Wire Policy: Packet %s dropped (%s is not actively connected with %s)", packet.PacketId, sender.NxId, packet.RecipientNxId)
				return
			}
		}
	}

	// Recipient Delivery ACK Reconciliation (Phase I Cloud Gateway Queue Reconciliation)
	if packet.Type == wire.PacketType_PACKET_ACK && packet.Ack != nil {
		if packet.Ack.Status == wire.AckStatus_ACK_DEVICE_DELIVERED {
			if sender.Authenticated && sender.NxId == packet.SenderNxId {
				// Anti-spoofing: if RecipientNxId is provided, it must match the authenticated session
				if packet.Ack.RecipientNxId != "" && packet.Ack.RecipientNxId != sender.NxId {
					log.Printf("[Hub] Security rejection: Delivery ACK recipient mismatch (claimed: %s, authenticated: %s)",
						packet.Ack.RecipientNxId, sender.NxId)
					return
				}
				if packet.Ack.OriginalPacketId == "" {
					log.Printf("[Hub] Rejected Delivery ACK with empty original_packet_id from %s", sender.NxId)
					return
				}
				if h.buffer != nil {
					ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
					removed, err := h.buffer.Acknowledge(ctx, sender.NxId, packet.Ack.OriginalPacketId)
					if err == nil && removed {
						atomic.AddUint64(&h.acknowledgedDeliveryCount, 1)
						log.Printf("[Hub] Delivery ACK reconciled: message %s removed from queue of %s", packet.Ack.OriginalPacketId, sender.NxId)
					} else {
						// Also check if it was buffered under a DTN token mapping
						h.blindTokensMu.RLock()
						var matchingTokens []string
						for tok, s := range h.blindTokens {
							if s == sender || s.NxId == sender.NxId {
								matchingTokens = append(matchingTokens, tok)
							}
						}
						h.blindTokensMu.RUnlock()

						for _, tok := range matchingTokens {
							dtnRemoved, _ := h.buffer.Acknowledge(ctx, "dtn_"+tok, packet.Ack.OriginalPacketId)
							if dtnRemoved {
								atomic.AddUint64(&h.acknowledgedDeliveryCount, 1)
								log.Printf("[Hub] Delivery ACK reconciled: DTN envelope %s removed from queue dtn_%s", packet.Ack.OriginalPacketId, tok[:min(len(tok), 8)])
								break
							}
						}
					}
					cancel()
				}
			} else {
				log.Printf("[Hub] Security warning: unauthenticated or spoofed delivery ACK from %s for %s", sender.NxId, packet.SenderNxId)
				return
			}
		}
	}

	// Strict Zero-Knowledge Idempotency & Conflict Guard (Zero Disk Plaintext)
	if packet.Type == wire.PacketType_PACKET_MESSAGE && packet.PacketId != "" {
		var payloadDigest string
		if packet.Message != nil {
			hSum := sha256.New()
			hSum.Write(packet.Message.Ciphertext)
			hSum.Write(packet.Message.AuthTag)
			hSum.Write(packet.Message.AeadNonce)
			payloadDigest = hex.EncodeToString(hSum.Sum(nil))
		}

		if val, exists := h.idempotency.Load(packet.PacketId); exists {
			entry := val.(idempotencyEntry)
			if entry.senderNxId != packet.SenderNxId || entry.payloadSha256 != payloadDigest {
				log.Printf("[Hub] SECURITY REJECTION: Collision/Conflict detected for packet %s! Existing (sender=%s, hash=%s) vs incoming (sender=%s, hash=%s)",
					packet.PacketId, entry.senderNxId, entry.payloadSha256, packet.SenderNxId, payloadDigest)
				return
			}

			// Exact duplicate: return early, acknowledge to sender
			atomic.AddUint64(&h.duplicateSuppressedCount, 1)
			serverAck := &wire.NexaWirePacket{
				PacketId:      packet.PacketId + "_srv_ack",
				Type:          wire.PacketType_PACKET_ACK,
				SenderNxId:    "SERVER",
				RecipientNxId: sender.NxId,
				Timestamp:     time.Now().UnixMilli(),
				Ack: &wire.DeliveryAckPayload{
					OriginalPacketId: packet.PacketId,
					Status:           wire.AckStatus_ACK_SERVER_RECEIVED,
				},
			}
			if ackBytes, err := serverAck.Encode(); err == nil {
				sender.SafeSend(ackBytes)
			}
			log.Printf("[Hub] Idempotency: Suppressed duplicate packet %s from %s", packet.PacketId, sender.NxId)
			return
		}

		h.idempotency.Store(packet.PacketId, idempotencyEntry{
			senderNxId:    packet.SenderNxId,
			payloadSha256: payloadDigest,
			expiresAt:     time.Now().Add(7 * 24 * time.Hour).Unix(),
		})

		if h.redisClient != nil {
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			h.redisClient.Set(ctx, "idemp:"+packet.PacketId, packet.SenderNxId+":"+payloadDigest, 7*24*time.Hour)
			cancel()
		}
	}

	// Group message routing (validates membership BEFORE dispatching server ACK)
	if packet.GroupId != "" || strings.HasPrefix(packet.RecipientNxId, "GRP-") {
		groupId := packet.GroupId
		if groupId == "" {
			groupId = packet.RecipientNxId
		}
		h.routeGroupPacket(sender, groupId, packet, rawBytes)
		return
	}

	// 2. Immediate Server Acknowledgment (ACK_SERVER_RECEIVED) back to sender
	if packet.Type == wire.PacketType_PACKET_MESSAGE {
		serverAck := &wire.NexaWirePacket{
			PacketId:      packet.PacketId + "_srv_ack",
			Type:          wire.PacketType_PACKET_ACK,
			SenderNxId:    "SERVER",
			RecipientNxId: sender.NxId,
			Timestamp:     time.Now().UnixMilli(),
			Ack: &wire.DeliveryAckPayload{
				OriginalPacketId: packet.PacketId,
				Status:           wire.AckStatus_ACK_SERVER_RECEIVED,
			},
		}

		if ackBytes, err := serverAck.Encode(); err == nil {
			if !sender.SafeSend(ackBytes) {
				log.Printf("[Hub] Sender buffer full for ACK to %s", sender.NxId)
			}
		}

		// Multi-device self-sync: Forward outgoing message to sender's other active devices
		h.sessionsMu.RLock()
		if senderDevices, ok := h.sessions[sender.NxId]; ok {
			for devId, devSession := range senderDevices {
				if devId != sender.DeviceId {
					if devSession.SafeSend(rawBytes) {
						log.Printf("[Hub] Synced outgoing packet %s to sibling device %s of sender %s", packet.PacketId, devId, sender.NxId)
					} else {
						log.Printf("[Hub] Sibling device %s buffer full for sender %s", devId, sender.NxId)
					}
				}
			}
		}
		h.sessionsMu.RUnlock()
	}

	// 3. Transient packets (Typing Indicators) are RAM-only and NEVER buffered offline
	if packet.Type == wire.PacketType_PACKET_TYPING {
		// Rate limiting: max 5 typing packets per second per session
		h.typingRateMu.Lock()
		limiter, ok := h.typingRate[sender]
		now := time.Now()
		if !ok || now.Sub(limiter.windowStart) > time.Second {
			h.typingRate[sender] = &typingRateLimiter{count: 1, windowStart: now}
		} else {
			limiter.count++
			if limiter.count > 5 {
				h.typingRateMu.Unlock()
				return
			}
		}
		h.typingRateMu.Unlock()

		atomic.AddUint64(&h.relayedTypingCount, 1)

		if packet.GroupId != "" || strings.HasPrefix(packet.RecipientNxId, "GRP-") {
			groupId := packet.GroupId
			if groupId == "" {
				groupId = packet.RecipientNxId
			}
			h.routeGroupPacket(sender, groupId, packet, rawBytes)
			return
		}

		h.sessionsMu.RLock()
		recipientDevices, isOnline := h.sessions[packet.RecipientNxId]
		var targets []*ClientSession
		if isOnline {
			for _, s := range recipientDevices {
				targets = append(targets, s)
			}
		}
		h.sessionsMu.RUnlock()

		for _, s := range targets {
			s.SafeSend(rawBytes)
		}
		return
	}

	// 4. Ephemeral WebRTC Signaling (OP_SIGNALING): Zero server cost, zero disk, RAM-only routing
	if packet.Type == wire.PacketType_PACKET_SIGNALING {
		h.sessionsMu.RLock()
		recipientDevices, isOnline := h.sessions[packet.RecipientNxId]
		var targets []*ClientSession
		if isOnline {
			for _, s := range recipientDevices {
				targets = append(targets, s)
			}
		}
		h.sessionsMu.RUnlock()

		if isOnline && len(targets) > 0 {
			for _, s := range targets {
				if !s.SafeSend(rawBytes) {
					log.Printf("[Hub] Recipient %s signaling buffer full", packet.RecipientNxId)
				}
			}
			log.Printf("[Hub] Relayed OP_SIGNALING packet %s from %s to %s", packet.PacketId, sender.NxId, packet.RecipientNxId)
		} else {
			log.Printf("[Hub] Dropped ephemeral OP_SIGNALING packet %s (Recipient %s offline)", packet.PacketId, packet.RecipientNxId)
		}
		return
	}

	// 5. Peer Synchronization Routing (PACKET_PEER_SYNC)
	if packet.Type == wire.PacketType_PACKET_PEER_SYNC {
		recipientId := packet.RecipientNxId
		h.sessionsMu.RLock()
		recipientDevices, isOnline := h.sessions[recipientId]
		var targets []*ClientSession
		if isOnline {
			for _, s := range recipientDevices {
				targets = append(targets, s)
			}
		}
		h.sessionsMu.RUnlock()

		if isOnline && len(targets) > 0 {
			for _, s := range targets {
				if !s.SafeSend(rawBytes) {
					log.Printf("[Hub] Recipient %s peer_sync buffer full", recipientId)
				}
			}
			log.Printf("[Hub] Relayed PACKET_PEER_SYNC %s from %s to %s", packet.PacketId, sender.NxId, recipientId)
		} else {
			log.Printf("[Hub] Recipient %s offline for PACKET_PEER_SYNC %s (anti-entropy requires active connection)", recipientId, packet.PacketId)
		}
		return
	}

	// 6. Relay Custody ACK Routing (PACKET_CUSTODY_ACK): RAM-only, NEVER buffered offline, NEVER reconciles recipient queue
	if packet.Type == wire.PacketType_PACKET_CUSTODY_ACK {
		recipientId := packet.RecipientNxId
		h.sessionsMu.RLock()
		recipientDevices, isOnline := h.sessions[recipientId]
		var targets []*ClientSession
		if isOnline {
			for _, s := range recipientDevices {
				targets = append(targets, s)
			}
		}
		h.sessionsMu.RUnlock()

		if isOnline && len(targets) > 0 {
			for _, s := range targets {
				if !s.SafeSend(rawBytes) {
					log.Printf("[Hub] Recipient %s custody_ack buffer full", recipientId)
				}
			}
			log.Printf("[Hub] Relayed PACKET_CUSTODY_ACK %s from %s to %s (RAM-only)", packet.PacketId, sender.NxId, recipientId)
		} else {
			log.Printf("[Hub] Dropped PACKET_CUSTODY_ACK %s (Recipient %s offline)", packet.PacketId, recipientId)
		}
		return
	}

	// 4. Standard message / ack routing
	recipientId := packet.RecipientNxId
	h.sessionsMu.RLock()
	recipientDevices, isOnline := h.sessions[recipientId]
	var activeSessions []*ClientSession
	if isOnline {
		for _, s := range recipientDevices {
			activeSessions = append(activeSessions, s)
		}
	}
	h.sessionsMu.RUnlock()

	if len(activeSessions) > 0 {
		deliveredCount := 0
		for _, s := range activeSessions {
			if s.SafeSend(rawBytes) {
				deliveredCount++
				atomic.AddUint64(&h.relayedMessagesCount, 1)
				log.Printf("[Hub] Relayed packet %s: %s -> %s (Device: %s, RAM-only)", packet.PacketId, packet.SenderNxId, recipientId, s.DeviceId)
			} else {
				log.Printf("[Hub] Recipient %s (Device: %s) buffer full", recipientId, s.DeviceId)
			}
		}
		if deliveredCount == 0 {
			h.bufferOffline(recipientId, rawBytes)
		}
	} else {
		// Offline: Store into ephemeral Redis queue (24h TTL) and trigger background FCM wake-up
		h.bufferOffline(recipientId, rawBytes)
	}
}

// RouteDtnEnvelope routes an opportunistic envelope from the Cloud Bridge
func (h *Hub) RouteDtnEnvelope(blindTokenHex string, rawBytes []byte) {
	h.blindTokensMu.RLock()
	session, isOnline := h.blindTokens[blindTokenHex]
	h.blindTokensMu.RUnlock()

	if isOnline {
		if session.SafeSend(rawBytes) {
			log.Printf("[Hub] Relayed DTN envelope [token=%s] directly to online client (RAM-only)", blindTokenHex[:min(len(blindTokenHex), 8)])
		} else {
			h.bufferOfflineDtn(blindTokenHex, rawBytes)
		}
	} else {
		h.bufferOfflineDtn(blindTokenHex, rawBytes)
	}
}

func (h *Hub) bufferOffline(recipientId string, rawBytes []byte) {
	if h.buffer == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	msgID := queue.ExtractMessageID(rawBytes)
	if err := h.buffer.Enqueue(ctx, recipientId, msgID, rawBytes, 0); err != nil {
		log.Printf("[Hub] Failed to buffer offline packet for %s: %v", recipientId, err)
		return
	}

	log.Printf("[Hub] Buffered offline packet %s for %s in reliable queue (TTL: 24h)", msgID, recipientId)
	atomic.AddUint64(&h.offlineBufferedCount, 1)
	atomic.AddUint64(&h.queuedMessagesCount, 1)
	go h.triggerFcmWakeup(recipientId)
}

func (h *Hub) bufferOfflineDtn(blindTokenHex string, rawBytes []byte) {
	if h.buffer == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	dtnKey := "dtn_" + blindTokenHex
	msgID := queue.ExtractMessageID(rawBytes)
	var expiresAt int64
	if len(rawBytes) >= 20 && string(rawBytes[0:4]) == "NXDT" {
		expiresAt = int64(binary.BigEndian.Uint64(rawBytes[12:20]))
	}

	if err := h.buffer.Enqueue(ctx, dtnKey, msgID, rawBytes, expiresAt); err != nil {
		log.Printf("[Hub] Failed to buffer offline DTN envelope for %s: %v", blindTokenHex[:min(len(blindTokenHex), 8)], err)
		return
	}

	log.Printf("[Hub] Buffered offline DTN envelope %s [token=%s] in reliable queue", msgID, blindTokenHex[:min(len(blindTokenHex), 8)])
	atomic.AddUint64(&h.offlineBufferedCount, 1)
	atomic.AddUint64(&h.queuedMessagesCount, 1)
	go h.triggerFcmWakeup(blindTokenHex)
}

func (h *Hub) flushPendingQueue(recipientId string, session *ClientSession) {
	if h.buffer == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	const maxBatches = 10
	for batch := 0; batch < maxBatches; batch++ {
		claimedEntries, err := h.buffer.ClaimPending(ctx, recipientId, 50, 30*time.Second)
		if err != nil {
			log.Printf("[Hub] Error claiming pending packets for %s: %v", recipientId, err)
			break
		}

		if len(claimedEntries) == 0 {
			if batch == 0 {
				// Fallback check for any legacy packets in pending:<recipientId>
				legacyPackets, _ := h.buffer.PopAllPending(ctx, recipientId)
				for _, raw := range legacyPackets {
					session.SafeSend(raw)
				}
			}
			break
		}

		log.Printf("[Hub] Claimed %d pending packets in-flight (batch %d) for freshly connected NX-ID: %s (Device: %s)", len(claimedEntries), batch+1, recipientId, session.DeviceId)
		failed := false
		for i, entry := range claimedEntries {
			if !session.SafeSend(entry.Payload) {
				log.Printf("[Hub] Session send failed for entry %s to %s. Reverting in-flight.", entry.MessageID, recipientId)
				for j := len(claimedEntries) - 1; j >= i; j-- {
					_ = h.buffer.RevertInFlight(ctx, recipientId, claimedEntries[j].MessageID)
					atomic.AddUint64(&h.revertedDeliveryCount, 1)
				}
				failed = true
				break
			}
			atomic.AddUint64(&h.claimedInFlightCount, 1)
			atomic.AddUint64(&h.offlineFlushedCount, 1)
		}

		if failed || len(claimedEntries) < 50 {
			break
		}
	}
}

func (h *Hub) routeGroupPacket(sender *ClientSession, groupId string, packet *wire.NexaWirePacket, rawBytes []byte) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	// 1. Group membership validation: sender must be an active member
	if h.groupProvider != nil {
		isMember, err := h.groupProvider.IsMember(ctx, groupId, sender.NxId)
		if err != nil || !isMember {
			log.Printf("[Hub] Group security rejection: %s is not an active member of group %s", sender.NxId, groupId)
			return
		}
	}

	// 2. Server acceptance ACK to sender (for messages, edits, deletes)
	if packet.Type == wire.PacketType_PACKET_MESSAGE || packet.Type == wire.PacketType_PACKET_MESSAGE_EDIT || packet.Type == wire.PacketType_PACKET_MESSAGE_DELETE {
		serverAck := &wire.NexaWirePacket{
			PacketId:      packet.PacketId + "_srv_ack",
			Type:          wire.PacketType_PACKET_ACK,
			SenderNxId:    "SERVER",
			RecipientNxId: sender.NxId,
			Timestamp:     time.Now().UnixMilli(),
			Ack: &wire.DeliveryAckPayload{
				OriginalPacketId: packet.PacketId,
				Status:           wire.AckStatus_ACK_SERVER_RECEIVED,
			},
		}
		if ackBytes, err := serverAck.Encode(); err == nil {
			sender.SafeSend(ackBytes)
		}
	}

	// 3. Look up group members
	var members []string
	if h.groupProvider != nil {
		var err error
		members, err = h.groupProvider.GetGroupMembers(ctx, groupId)
		if err != nil {
			log.Printf("[Hub] Error fetching members for group %s: %v", groupId, err)
			return
		}
	}

	// 4. Server-side fan-out (up to 400 members)
	for _, memberNxId := range members {
		if memberNxId == sender.NxId {
			continue // Do not reflect back to sender
		}

		h.sessionsMu.RLock()
		devices, isOnline := h.sessions[memberNxId]
		var targets []*ClientSession
		if isOnline {
			for _, s := range devices {
				targets = append(targets, s)
			}
		}
		h.sessionsMu.RUnlock()

		if len(targets) > 0 {
			delivered := 0
			for _, s := range targets {
				if s.SafeSend(rawBytes) {
					delivered++
					atomic.AddUint64(&h.relayedMessagesCount, 1)
				}
			}
			if delivered == 0 && packet.Type != wire.PacketType_PACKET_TYPING {
				h.bufferOffline(memberNxId, rawBytes)
			}
		} else if packet.Type != wire.PacketType_PACKET_TYPING {
			// Offline: Buffer into recipient's queue
			h.bufferOffline(memberNxId, rawBytes)
		}
	}
	log.Printf("[Hub] Group fan-out completed for %s in %s (total targets: %d)", packet.PacketId, groupId, len(members)-1)
}

// FlushTokensQueue flushes any buffered DTN envelopes for the specified routing tokens from ephemeral Redis.
func (h *Hub) FlushTokensQueue(tokenHexes []string, session *ClientSession) {
	if h.buffer == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	for _, token := range tokenHexes {
		if token == "" {
			continue
		}
		dtnKey := "dtn_" + token
		claimedEntries, err := h.buffer.ClaimPending(ctx, dtnKey, 50, 30*time.Second)
		if err != nil {
			log.Printf("[Hub] Error claiming pending DTN envelopes for token %s: %v", token[:min(len(token), 8)], err)
			continue
		}

		if len(claimedEntries) == 0 {
			legacyPackets, _ := h.buffer.PopAllPending(ctx, dtnKey)
			for _, raw := range legacyPackets {
				session.SafeSend(raw)
			}
			continue
		}

		log.Printf("[Hub] Claimed %d pending DTN envelopes [token=%s] for NX-ID: %s", len(claimedEntries), token[:min(len(token), 8)], session.NxId)
		for i, entry := range claimedEntries {
			if !session.SafeSend(entry.Payload) {
				log.Printf("[Hub] Buffer full or session closed streaming DTN envelope to %s. Reverting in-flight.", session.NxId)
				for j := len(claimedEntries) - 1; j >= i; j-- {
					_ = h.buffer.RevertInFlight(ctx, dtnKey, claimedEntries[j].MessageID)
					atomic.AddUint64(&h.revertedDeliveryCount, 1)
				}
				break
			}
			atomic.AddUint64(&h.claimedInFlightCount, 1)
			atomic.AddUint64(&h.offlineFlushedCount, 1)
		}
	}
}

// GetTelemetryMetrics returns live in-memory operational metrics with zero DB load.
func (h *Hub) GetTelemetryMetrics() map[string]interface{} {
	h.sessionsMu.RLock()
	activeUsers := len(h.sessions)
	activeDevices := 0
	for _, devMap := range h.sessions {
		activeDevices += len(devMap)
	}
	h.sessionsMu.RUnlock()

	return map[string]interface{}{
		"active_users":                activeUsers,
		"active_device_connections":   activeDevices,
		"relayed_messages_count":      atomic.LoadUint64(&h.relayedMessagesCount),
		"relayed_typing_events":       atomic.LoadUint64(&h.relayedTypingCount),
		"offline_buffered_packets":    atomic.LoadUint64(&h.offlineBufferedCount),
		"offline_flushed_packets":     atomic.LoadUint64(&h.offlineFlushedCount),
		"ingested_telemetry_batches":  atomic.LoadUint64(&h.ingestedTelemetryBatches),
		"queued_messages_count":       atomic.LoadUint64(&h.queuedMessagesCount),
		"claimed_in_flight_count":     atomic.LoadUint64(&h.claimedInFlightCount),
		"acknowledged_delivery_count": atomic.LoadUint64(&h.acknowledgedDeliveryCount),
		"reverted_delivery_count":     atomic.LoadUint64(&h.revertedDeliveryCount),
		"coordinator_model":           "Zero-Disk RAM Traffic Police",
		"database_write_iops_cost":     0,
		"server_storage_bytes":         0,
	}
}

// RecordTelemetryBatch records an ingested telemetry batch event atomically.
func (h *Hub) RecordTelemetryBatch(count int) {
	atomic.AddUint64(&h.ingestedTelemetryBatches, 1)
}

func (h *Hub) triggerFcmWakeup(recipientNxId string) {
	if h.fcmService == nil {
		log.Printf("[FCM] Dispatching background wake-up ping for recipient %s (Local Mode, Cost: ₹0)", recipientNxId)
		return
	}

	var tokens []string
	if h.identityStore != nil {
		if devices, err := h.identityStore.GetDevices(recipientNxId); err == nil {
			for _, dev := range devices {
				if dev.FcmToken != "" {
					tokens = append(tokens, dev.FcmToken)
				}
			}
		}
	}

	if len(tokens) > 0 {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		h.fcmService.SendWakeUpBatch(ctx, tokens)
	} else {
		log.Printf("[FCM] Wake-up ping triggered for %s (no registered FCM device tokens found)", recipientNxId)
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

// BroadcastEventToNxID dispatches a real-time JSON notification to all active authenticated sessions of a user,
// delivering to local sessions and broadcasting across the Redis cluster.
func (h *Hub) BroadcastEventToNxID(nxId string, eventType string, payload interface{}) {
	if h == nil || nxId == "" {
		return
	}

	// 1. Deliver to local active sessions on this instance
	h.broadcastLocal(nxId, eventType, payload)

	// 2. Broadcast to cluster if Redis is configured
	if h.redisClient != nil {
		event := ClusterEvent{
			EventID:          generateID("evt_bcast"),
			Type:             eventType,
			OriginInstanceID: h.instanceID,
			NxID:             nxId,
			Payload:          payload,
			Timestamp:        time.Now().UnixMilli(),
		}
		if data, err := json.Marshal(event); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			_ = h.redisClient.Publish(ctx, ClusterEventsChannel, data).Err()
		}
	}
}

func (h *Hub) broadcastLocal(nxId string, eventType string, payload interface{}) {
	if h == nil || nxId == "" {
		return
	}

	eventData, err := json.Marshal(map[string]interface{}{
		"type":      eventType,
		"payload":   payload,
		"timestamp": time.Now().UnixMilli(),
	})
	if err != nil {
		return
	}

	h.sessionsMu.RLock()
	defer h.sessionsMu.RUnlock()

	devices, ok := h.sessions[nxId]
	if !ok || len(devices) == 0 {
		return
	}

	for _, session := range devices {
		if session.Authenticated {
			select {
			case session.Send <- eventData:
			default:
				log.Printf("[Hub] Warning: session buffer full for %s, dropped event %s", nxId, eventType)
			}
		}
	}
}
