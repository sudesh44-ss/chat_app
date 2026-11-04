package relay

import (
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 512 * 1024
)

// RelayHub manages an ephemeral, zero-knowledge in-memory routing table mapping
// rotating Blind Tokens to active WebSocket connections.
// Persists ZERO plaintext, ZERO metadata, and ZERO disk records.
type RelayHub struct {
	mu      sync.RWMutex
	sockets map[string]*RelaySession
}

func NewRelayHub() *RelayHub {
	return &RelayHub{
		sockets: make(map[string]*RelaySession),
	}
}

func (h *RelayHub) Register(session *RelaySession) {
	h.mu.Lock()
	h.sockets[session.BlindToken] = session
	h.mu.Unlock()
	log.Printf("[RelayHub] Bound session to blind token: %s", session.BlindToken[:min(8, len(session.BlindToken))])
}

func (h *RelayHub) Unregister(session *RelaySession) {
	h.mu.Lock()
	if existing, ok := h.sockets[session.BlindToken]; ok && existing == session {
		delete(h.sockets, session.BlindToken)
		close(session.Send)
		log.Printf("[RelayHub] Unbound session for blind token: %s", session.BlindToken[:min(8, len(session.BlindToken))])
	}
	h.mu.Unlock()
}

func (h *RelayHub) RouteBlindPacket(from *RelaySession, targetToken string, rawBytes []byte) {
	h.mu.RLock()
	target, exists := h.sockets[targetToken]
	h.mu.RUnlock()

	if !exists {
		log.Printf("[RelayHub] Target token offline: %s", targetToken[:min(8, len(targetToken))])
		return
	}

	select {
	case target.Send <- rawBytes:
		log.Printf("[RelayHub] Relayed %d bytes blind ciphertext to token %s", len(rawBytes), targetToken[:min(8, len(targetToken))])
	default:
		log.Printf("[RelayHub] Target buffer full for token: %s", targetToken[:min(8, len(targetToken))])
	}
}

func (h *RelayHub) DispatchToToken(targetToken string, rawBytes []byte) bool {
	h.mu.RLock()
	target, exists := h.sockets[targetToken]
	h.mu.RUnlock()

	if !exists {
		return false
	}

	select {
	case target.Send <- rawBytes:
		return true
	default:
		return false
	}
}

type RelaySession struct {
	Hub        *RelayHub
	Conn       *websocket.Conn
	BlindToken string
	Send       chan []byte
}

func NewRelaySession(hub *RelayHub, conn *websocket.Conn, blindToken string) *RelaySession {
	return &RelaySession{
		Hub:        hub,
		Conn:       conn,
		BlindToken: blindToken,
		Send:       make(chan []byte, 128),
	}
}

func (s *RelaySession) ReadPump() {
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
			break
		}

		if messageType == websocket.BinaryMessage && len(data) >= 16 {
			// First 16 bytes contain recipient rotating blind token (hex or raw)
			targetTokenHex := string(data[:min(32, len(data))])
			s.Hub.RouteBlindPacket(s, targetTokenHex, data)
		}
	}
}

func (s *RelaySession) WritePump() {
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

			w, err := s.Conn.NextWriter(websocket.BinaryMessage)
			if err != nil {
				return
			}
			w.Write(message)

			n := len(s.Send)
			for i := 0; i < n; i++ {
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

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
