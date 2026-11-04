package swarm

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
	maxMessageSize = 256 * 1024
)

type SwarmHub struct {
	mu       sync.RWMutex
	sessions map[string]*SwarmSession
	catalog  *SwarmCatalog
}

func NewSwarmHub() *SwarmHub {
	return &SwarmHub{
		sessions: make(map[string]*SwarmSession),
		catalog:  NewSwarmCatalog(),
	}
}

func (h *SwarmHub) Register(session *SwarmSession) {
	h.mu.Lock()
	h.sessions[session.PeerId] = session
	h.mu.Unlock()
	log.Printf("[SwarmHub] Peer connected: %s", session.PeerId)
}

func (h *SwarmHub) Unregister(session *SwarmSession) {
	h.mu.Lock()
	if existing, ok := h.sessions[session.PeerId]; ok && existing == session {
		delete(h.sessions, session.PeerId)
		close(session.Send)
		h.catalog.RemovePeer(session.PeerId)
		log.Printf("[SwarmHub] Peer disconnected & evicted from catalog: %s", session.PeerId)
	}
	h.mu.Unlock()
}

type SwarmPayload struct {
	Action         string   `json:"action"`
	FileHash       string   `json:"file_hash,omitempty"`
	PeerId         string   `json:"peer_id,omitempty"`
	RequesterId    string   `json:"requester_id,omitempty"`
	ConversationId string   `json:"conversation_id,omitempty"`
	Seeders        []string `json:"seeders,omitempty"`
}

func (h *SwarmHub) RouteMessage(from *SwarmSession, rawData []byte) {
	var payload SwarmPayload
	if err := json.Unmarshal(rawData, &payload); err != nil {
		log.Printf("[SwarmHub] Invalid JSON from %s: %v", from.PeerId, err)
		return
	}

	switch payload.Action {
	case "SWARM_ADVERTISE":
		if payload.FileHash != "" {
			h.catalog.AddSeeder(payload.FileHash, from.PeerId)
			log.Printf("[SwarmHub] Registered seeder %s for hash %s", from.PeerId, payload.FileHash)
		}

	case "SWARM_QUERY":
		if payload.FileHash != "" {
			seeders := h.catalog.GetSeeders(payload.FileHash)
			response := map[string]interface{}{
				"action":    "SWARM_RESPONSE",
				"file_hash": payload.FileHash,
				"seeders":   seeders,
			}
			respBytes, _ := json.Marshal(response)
			select {
			case from.Send <- respBytes:
				log.Printf("[SwarmHub] Returned %d seeders for hash %s to %s", len(seeders), payload.FileHash, from.PeerId)
			default:
			}
		}

	case "METADATA_BROADCAST":
		// Broadcast metadata envelope to all active peers (except sender)
		h.mu.RLock()
		for id, session := range h.sessions {
			if id != from.PeerId {
				select {
				case session.Send <- rawData:
				default:
				}
			}
		}
		h.mu.RUnlock()
		log.Printf("[SwarmHub] Broadcasted ~1 KB metadata envelope from %s", from.PeerId)
	}
}

type SwarmSession struct {
	Hub    *SwarmHub
	Conn   *websocket.Conn
	PeerId string
	Send   chan []byte
}

func NewSwarmSession(hub *SwarmHub, conn *websocket.Conn, peerId string) *SwarmSession {
	return &SwarmSession{
		Hub:    hub,
		Conn:   conn,
		PeerId: peerId,
		Send:   make(chan []byte, 128),
	}
}

func (s *SwarmSession) ReadPump() {
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
		if messageType == websocket.TextMessage || messageType == websocket.BinaryMessage {
			s.Hub.RouteMessage(s, data)
		}
	}
}

func (s *SwarmSession) WritePump() {
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
