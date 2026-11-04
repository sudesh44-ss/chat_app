package signaling

import (
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 256 * 1024 // 256 KB max for SDP envelopes
)

// SignalingHub coordinates real-time ephemeral WebRTC SDP & ICE candidate exchange.
// It stores ZERO files, ZERO byte payloads, and writes nothing to disk.
type SignalingHub struct {
	mu       sync.RWMutex
	sessions map[string]*SignalingSession
}

func NewSignalingHub() *SignalingHub {
	return &SignalingHub{
		sessions: make(map[string]*SignalingSession),
	}
}

func (h *SignalingHub) Register(session *SignalingSession) {
	h.mu.Lock()
	h.sessions[session.NxId] = session
	h.mu.Unlock()
	log.Printf("[SignalingHub] Registered peer: %s", session.NxId)
}

func (h *SignalingHub) Unregister(session *SignalingSession) {
	h.mu.Lock()
	if existing, ok := h.sessions[session.NxId]; ok && existing == session {
		delete(h.sessions, session.NxId)
		close(session.Send)
		log.Printf("[SignalingHub] Unregistered peer: %s", session.NxId)
	}
	h.mu.Unlock()
}

type SignalingEnvelope struct {
	Action        string `json:"action"`
	TransferId    int64  `json:"transfer_id"`
	SenderNxId   string `json:"sender_nx_id"`
	RecipientNxId string `json:"recipient_nx_id"`
}

func (h *SignalingHub) RouteMessage(from *SignalingSession, rawData []byte) {
	var env SignalingEnvelope
	if err := json.Unmarshal(rawData, &env); err != nil {
		log.Printf("[SignalingHub] Invalid JSON envelope from %s: %v", from.NxId, err)
		return
	}

	if env.RecipientNxId == "" {
		log.Printf("[SignalingHub] Message missing recipient_nx_id from %s", from.NxId)
		return
	}

	h.mu.RLock()
	target, exists := h.sessions[env.RecipientNxId]
	h.mu.RUnlock()

	if !exists {
		log.Printf("[SignalingHub] Recipient %s not connected for action %s (transferId: %d)", env.RecipientNxId, env.Action, env.TransferId)
		return
	}

	if target.SafeSend(rawData) {
		log.Printf("[SignalingHub] Relayed %s for transfer %d: %s -> %s", env.Action, env.TransferId, from.NxId, env.RecipientNxId)
	} else {
		log.Printf("[SignalingHub] Dropped message for %s: send buffer full or session closed", env.RecipientNxId)
	}
}

// SafeSend attempts to enqueue data to Send channel, recovering safely if closed or full.
func (s *SignalingSession) SafeSend(data []byte) (sent bool) {
	defer func() {
		if r := recover(); r != nil {
			sent = false
		}
	}()
	select {
	case s.Send <- data:
		return true
	default:
		return false
	}
}

type SignalingSession struct {
	Hub  *SignalingHub
	Conn *websocket.Conn
	NxId string
	Send chan []byte
}

func NewSignalingSession(hub *SignalingHub, conn *websocket.Conn, nxId string) *SignalingSession {
	return &SignalingSession{
		Hub:  hub,
		Conn: conn,
		NxId: nxId,
		Send: make(chan []byte, 128),
	}
}

func (s *SignalingSession) ReadPump() {
	defer func() {
		s.Hub.Unregister(s)
		s.Conn.Close()
	}()

	s.Conn.SetReadLimit(maxMessageSize)
	s.Conn.SetReadDeadline(time.Now().Add(pongWait))
	s.Conn.SetPongHandler(func(string) error {
		s.Conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		messageType, data, err := s.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("[Signaling %s] Read error: %v", s.NxId, err)
			}
			break
		}

		if messageType == websocket.TextMessage || messageType == websocket.BinaryMessage {
			s.Hub.RouteMessage(s, data)
		}
	}
}

func (s *SignalingSession) WritePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		s.Conn.Close()
	}()

	for {
		select {
		case message, ok := <-s.Send:
			s.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				s.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			w, err := s.Conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			w.Write(message)

			// Drain any additional buffered envelopes
			n := len(s.Send)
			for i := 0; i < n; i++ {
				w.Write([]byte("\n"))
				w.Write(<-s.Send)
			}

			if err := w.Close(); err != nil {
				return
			}

		case <-ticker.C:
			s.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := s.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
