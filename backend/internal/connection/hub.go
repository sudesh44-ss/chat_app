package connection

import (
	"context"
	"log"
	"sync"
	"sync/atomic"
	"time"

	"nexa/wire/internal/fcm"
	"nexa/wire/internal/identity"
	"nexa/wire/internal/queue"
	"nexa/wire/internal/wire"
)

type Hub struct {
	// Concurrent in-memory routing table mapping NX-ID -> DeviceID -> Active WebSocket session
	sessionsMu sync.RWMutex
	sessions   map[string]map[string]*ClientSession

	// Blind Token mapping: blindTokenHex -> ClientSession
	blindTokensMu sync.RWMutex
	blindTokens   map[string]*ClientSession

	// Ephemeral Redis buffer (TTL: 24h)
	buffer queue.Buffer

	// FCM Service for background wake-up pings
	fcmService *fcm.FcmService

	// Identity store to lookup registered device FCM tokens
	identityStore *identity.Store

	// In-memory telemetry & traffic police metrics (Zero DB write IOPS)
	relayedMessagesCount     uint64
	relayedTypingCount       uint64
	offlineBufferedCount     uint64
	offlineFlushedCount      uint64
	ingestedTelemetryBatches uint64
}

func NewHub(buffer queue.Buffer) *Hub {
	return &Hub{
		sessions:    make(map[string]map[string]*ClientSession),
		blindTokens: make(map[string]*ClientSession),
		buffer:      buffer,
	}
}

func (h *Hub) SetFcmService(fcmService *fcm.FcmService) {
	h.fcmService = fcmService
}

func (h *Hub) SetIdentityStore(identityStore *identity.Store) {
	h.identityStore = identityStore
}

func (h *Hub) Register(session *ClientSession) {
	h.sessionsMu.Lock()
	if _, ok := h.sessions[session.NxId]; !ok {
		h.sessions[session.NxId] = make(map[string]*ClientSession)
	}
	h.sessions[session.NxId][session.DeviceId] = session
	h.sessionsMu.Unlock()

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

func (h *Hub) Unregister(session *ClientSession) {
	h.sessionsMu.Lock()
	if devices, ok := h.sessions[session.NxId]; ok {
		if existing, found := devices[session.DeviceId]; found && existing == session {
			delete(devices, session.DeviceId)
			close(session.Send)
			log.Printf("[Hub] Unregistered session for NX-ID: %s (Device: %s)", session.NxId, session.DeviceId)
		}
		if len(devices) == 0 {
			delete(h.sessions, session.NxId)
		}
	}
	h.sessionsMu.Unlock()

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
		atomic.AddUint64(&h.relayedTypingCount, 1)
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
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if err := h.buffer.PushPending(ctx, recipientId, rawBytes); err != nil {
		log.Printf("[Hub] Failed to buffer offline packet for %s: %v", recipientId, err)
		return
	}

	log.Printf("[Hub] Buffered offline packet for %s in ephemeral Redis (TTL: 24h)", recipientId)
	atomic.AddUint64(&h.offlineBufferedCount, 1)
	go h.triggerFcmWakeup(recipientId)
}

func (h *Hub) bufferOfflineDtn(blindTokenHex string, rawBytes []byte) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	dtnKey := "dtn_" + blindTokenHex
	if err := h.buffer.PushPending(ctx, dtnKey, rawBytes); err != nil {
		log.Printf("[Hub] Failed to buffer offline DTN envelope for %s: %v", blindTokenHex[:min(len(blindTokenHex), 8)], err)
		return
	}

	log.Printf("[Hub] Buffered offline DTN envelope [token=%s] in ephemeral Redis (TTL: 24h)", blindTokenHex[:min(len(blindTokenHex), 8)])
	go h.triggerFcmWakeup(blindTokenHex)
}

func (h *Hub) flushPendingQueue(recipientId string, session *ClientSession) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	pendingPackets, err := h.buffer.PopAllPending(ctx, recipientId)
	if err != nil {
		log.Printf("[Hub] Error fetching pending packets for %s: %v", recipientId, err)
		return
	}

	if len(pendingPackets) == 0 {
		return
	}

	log.Printf("[Hub] Flushing %d pending packets to freshly connected NX-ID: %s (Device: %s)", len(pendingPackets), recipientId, session.DeviceId)
	atomic.AddUint64(&h.offlineFlushedCount, uint64(len(pendingPackets)))
	for _, raw := range pendingPackets {
		if !session.SafeSend(raw) {
			log.Printf("[Hub] Buffer full or session closed streaming pending packet to %s", recipientId)
			break
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
		"active_users":              activeUsers,
		"active_device_connections": activeDevices,
		"relayed_messages_count":    atomic.LoadUint64(&h.relayedMessagesCount),
		"relayed_typing_events":     atomic.LoadUint64(&h.relayedTypingCount),
		"offline_buffered_packets":   atomic.LoadUint64(&h.offlineBufferedCount),
		"offline_flushed_packets":    atomic.LoadUint64(&h.offlineFlushedCount),
		"ingested_telemetry_batches": atomic.LoadUint64(&h.ingestedTelemetryBatches),
		"coordinator_model":          "Zero-Disk RAM Traffic Police",
		"database_write_iops_cost":   0,
		"server_storage_bytes":       0,
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
