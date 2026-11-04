package handshake

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"

	"github.com/gorilla/websocket"
)

type DispatchRequest struct {
	RecipientNxID     string `json:"recipient_nx_id"`
	EncryptedEnvelope string `json:"encrypted_envelope"`
	PowTimestamp      int64  `json:"pow_timestamp"`
	PowNonce          int64  `json:"pow_nonce"`
}

type HandshakeHandler struct {
	store        *MemoryStore
	mu           sync.RWMutex
	activeWs     map[string]*websocket.Conn
	upgrader     websocket.Upgrader
}

func NewHandshakeHandler(store *MemoryStore) *HandshakeHandler {
	return &HandshakeHandler{
		store:    store,
		activeWs: make(map[string]*websocket.Conn),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
}

func (h *HandshakeHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/users/", h.handleUsers)
	mux.HandleFunc("/api/v1/requests/dispatch", h.handleDispatch)
	mux.HandleFunc("/api/v1/requests/pending", h.handlePending)
	mux.HandleFunc("/ws/handshake", h.handleWebSocket)
}

func (h *HandshakeHandler) handleUsers(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/users/")
	if r.Method == http.MethodPost && path == "prekey" {
		var bundle PrekeyBundle
		if err := json.NewDecoder(r.Body).Decode(&bundle); err != nil {
			http.Error(w, "Invalid prekey payload", http.StatusBadRequest)
			return
		}
		h.store.PutPrekey(&bundle)
		w.WriteHeader(http.StatusOK)
		return
	}

	if r.Method == http.MethodGet && strings.HasSuffix(path, "/prekey") {
		nxID := strings.TrimSuffix(path, "/prekey")
		bundle, ok := h.store.GetPrekey(nxID)
		if !ok {
			http.Error(w, "Prekey bundle not registered for NX-ID: "+nxID, http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(bundle)
		return
	}

	http.NotFound(w, r)
}

func (h *HandshakeHandler) handleDispatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	clientIP := r.RemoteAddr
	if colon := strings.LastIndex(clientIP, ":"); colon != -1 {
		clientIP = clientIP[:colon]
	}

	// 1. Rate Limit Check (<= 15 requests / hour)
	if !h.store.AllowRequest(clientIP) {
		http.Error(w, "Rate limit exceeded (Max 15 friend requests/hour)", http.StatusTooManyRequests)
		return
	}

	var req DispatchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	// 2. Micro-Proof-of-Work Verification (< 1 microsecond)
	if !VerifyProofOfWork(req.RecipientNxID, req.PowTimestamp, req.PowNonce) {
		http.Error(w, "Invalid Proof-of-Work nonce or expired challenge timestamp", http.StatusBadRequest)
		return
	}

	// 3. Routing: Active WebSocket or Ephemeral Queue
	h.mu.RLock()
	wsConn, isOnline := h.activeWs[req.RecipientNxID]
	h.mu.RUnlock()

	if isOnline && wsConn != nil {
		// Online: Dispatch directly over WebSocket
		if err := wsConn.WriteMessage(websocket.TextMessage, []byte(req.EncryptedEnvelope)); err == nil {
			w.WriteHeader(http.StatusOK)
			return
		}
	}

	// Offline: Store in Ephemeral Queue (7-Day TTL) and trigger simulated FCM Wake-up
	h.store.Enqueue(req.RecipientNxID, req.EncryptedEnvelope)
	log.Printf("[FCM TRIGGER] High-priority wake-up data ping dispatched to recipient %s", req.RecipientNxID)

	w.WriteHeader(http.StatusAccepted)
}

func (h *HandshakeHandler) handlePending(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	nxID := r.URL.Query().Get("nx_id")
	if nxID == "" {
		http.Error(w, "Missing nx_id parameter", http.StatusBadRequest)
		return
	}

	envelopes := h.store.DrainPending(nxID)
	if envelopes == nil {
		envelopes = []string{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"requests": envelopes,
	})
}

func (h *HandshakeHandler) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	nxID := r.URL.Query().Get("nx_id")
	if nxID == "" {
		http.Error(w, "Missing nx_id", http.StatusBadRequest)
		return
	}

	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	h.mu.Lock()
	h.activeWs[nxID] = conn
	h.mu.Unlock()

	defer func() {
		h.mu.Lock()
		delete(h.activeWs, nxID)
		h.mu.Unlock()
		conn.Close()
	}()

	for {
		_, _, err := conn.ReadMessage()
		if err != nil {
			break
		}
	}
}
