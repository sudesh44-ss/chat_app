package connections

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"nexa/wire/internal/identity"
	"nexa/wire/internal/ratelimit"
)

// HubBroadcaster represents the minimal notification contract exposed by the connection Hub.
type HubBroadcaster interface {
	BroadcastEventToNxID(nxId string, eventType string, payload interface{})
}

type Handler struct {
	repo         ConnectionRepository
	identityRepo identity.IdentityRepository
	hub          HubBroadcaster
	limiter      ratelimit.Limiter

	// Rate limiting maps
	rateMu        sync.Mutex
	discoveryRate map[string][]int64 // ip or nxID -> timestamps
	requestRate   map[string][]int64 // nxID -> timestamps
}

func NewHandler(repo ConnectionRepository, identityRepo identity.IdentityRepository, hub HubBroadcaster) *Handler {
	return &Handler{
		repo:          repo,
		identityRepo:  identityRepo,
		hub:           hub,
		limiter:       ratelimit.NewDistributedLimiter(nil, false),
		discoveryRate: make(map[string][]int64),
		requestRate:   make(map[string][]int64),
	}
}

func (h *Handler) SetRateLimiter(limiter ratelimit.Limiter) {
	if limiter != nil {
		h.limiter = limiter
	}
}

func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/users/discover", h.handleDiscover)
	mux.HandleFunc("/api/v1/connections/request", h.handleSendRequest)
	mux.HandleFunc("/api/v1/connections/requests/incoming", h.handleIncomingRequests)
	mux.HandleFunc("/api/v1/connections/requests/outgoing", h.handleOutgoingRequests)
	mux.HandleFunc("/api/v1/connections/requests/accept", h.handleAcceptRequest)
	mux.HandleFunc("/api/v1/connections/requests/reject", h.handleRejectRequest)
	mux.HandleFunc("/api/v1/connections/requests/cancel", h.handleCancelRequest)
	mux.HandleFunc("/api/v1/connections", h.handleConnections)
	mux.HandleFunc("/api/v1/connections/remove", h.handleRemoveConnection)
	mux.HandleFunc("/api/v1/users/block", h.handleBlockUser)
	mux.HandleFunc("/api/v1/users/unblock", h.handleUnblockUser)
	mux.HandleFunc("/api/v1/users/blocked", h.handleListBlocked)
	mux.HandleFunc("/api/v1/contacts/sync-qr", h.handleSyncQr)
	mux.HandleFunc("/api/v1/users/phone-visibility", h.handlePhoneVisibility)
	mux.HandleFunc("/api/v1/connections/mutual", h.handleMutualConnections)
}

func (h *Handler) authenticate(r *http.Request) (*identity.Session, *identity.UserAccount, error) {
	authHeader := r.Header.Get("Authorization")
	if authHeader == "" {
		return nil, nil, errors.New("missing Authorization header")
	}

	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	sessionToken = strings.TrimSpace(sessionToken)
	if sessionToken == "" {
		return nil, nil, errors.New("invalid session token format")
	}

	session, ok := h.identityRepo.GetSession(sessionToken)
	if !ok || session == nil || session.IsRevoked {
		return nil, nil, errors.New("session is invalid, revoked, or expired")
	}

	// Verify device status
	if session.DeviceID != "" {
		if dev, found := h.identityRepo.GetDevice(session.DeviceID); found && (dev.IsRevoked || dev.NxID != session.NxID) {
			return nil, nil, errors.New("session device revoked or mismatched")
		}
	}

	account, ok := h.identityRepo.GetAccountByNxID(session.NxID)
	if !ok || account == nil {
		return nil, nil, errors.New("account not found")
	}

	return session, account, nil
}

func (h *Handler) checkRateLimit(key string, tracker map[string][]int64, windowMs int64, maxAttempts int) bool {
	h.rateMu.Lock()
	defer h.rateMu.Unlock()

	now := time.Now().UnixMilli()
	threshold := now - windowMs

	timestamps := tracker[key]
	var valid []int64
	for _, ts := range timestamps {
		if ts > threshold {
			valid = append(valid, ts)
		}
	}

	if len(valid) >= maxAttempts {
		tracker[key] = valid
		return false
	}

	tracker[key] = append(valid, now)
	return true
}

func getClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		return strings.TrimSpace(parts[0])
	}
	return r.RemoteAddr
}

// GET /api/v1/users/discover?nx_id=NX-XXXXXXXX
func (h *Handler) handleDiscover(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	// Rate limit: 30 searches / minute per account/IP
	rateKey := "ratelimit:discover:" + session.NxID + ":" + getClientIP(r)
	allowed, rErr := h.limiter.Allow(r.Context(), rateKey, 30, time.Minute)
	if rErr != nil {
		http.Error(w, "Rate limiter unavailable", http.StatusServiceUnavailable)
		return
	}
	if !allowed {
		http.Error(w, "Rate limit exceeded for user discovery (maximum 30 per minute)", http.StatusTooManyRequests)
		return
	}

	targetNxID := strings.TrimSpace(r.URL.Query().Get("nx_id"))
	if targetNxID == "" {
		http.Error(w, "Missing nx_id parameter", http.StatusBadRequest)
		return
	}

	profile, err := h.repo.SearchUserByNxID(r.Context(), session.NxID, targetNxID)
	if err != nil {
		if errors.Is(err, ErrInvalidNxID) {
			http.Error(w, "Invalid NX-ID format. Expected locked NX-XXXXXXXX format.", http.StatusBadRequest)
			return
		}
		if errors.Is(err, ErrUserNotFound) {
			http.Error(w, "User not found", http.StatusNotFound)
			return
		}
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(profile)
}

// POST /api/v1/connections/request
func (h *Handler) handleSendRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	// Rate limit: 10 requests / minute per account
	allowed, rErr := h.limiter.Allow(r.Context(), "ratelimit:conn_req:"+session.NxID, 10, time.Minute)
	if rErr != nil {
		http.Error(w, "Rate limiter unavailable", http.StatusServiceUnavailable)
		return
	}
	if !allowed {
		http.Error(w, "Rate limit exceeded for connection requests (maximum 10 per minute)", http.StatusTooManyRequests)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, 64*1024)

	var reqBody struct {
		RecipientNxID string `json:"recipient_nx_id"`
		IntroMessage  string `json:"intro_message,omitempty"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body or size exceeded", http.StatusBadRequest)
		return
	}

	if len(reqBody.IntroMessage) > 256 {
		http.Error(w, "Intro message too long (maximum 256 characters)", http.StatusBadRequest)
		return
	}

	reqBody.RecipientNxID = strings.TrimSpace(reqBody.RecipientNxID)
	connReq, err := h.repo.SendRequest(r.Context(), session.NxID, reqBody.RecipientNxID, reqBody.IntroMessage, DefaultRequestTTL)
	if err != nil {
		switch {
		case errors.Is(err, ErrSelfRequest):
			http.Error(w, err.Error(), http.StatusBadRequest)
		case errors.Is(err, ErrInvalidNxID):
			http.Error(w, err.Error(), http.StatusBadRequest)
		case errors.Is(err, ErrUserNotFound):
			http.Error(w, "Recipient user not found", http.StatusNotFound)
		case errors.Is(err, ErrBlockedByUser):
			http.Error(w, "Cannot send request to this user", http.StatusForbidden)
		case errors.Is(err, ErrAlreadyConnected):
			http.Error(w, "Already connected with this user", http.StatusConflict)
		case errors.Is(err, ErrPendingRequestExists):
			http.Error(w, "Pending connection request already exists", http.StatusConflict)
		default:
			http.Error(w, "Internal server error", http.StatusInternalServerError)
		}
		return
	}

	// Real-time event notification to recipient
	if h.hub != nil {
		h.hub.BroadcastEventToNxID(reqBody.RecipientNxID, "CONNECTION_REQUEST_RECEIVED", connReq)
		if connReq.Status == RequestStatusAccepted {
			h.hub.BroadcastEventToNxID(session.NxID, "CONNECTION_REQUEST_ACCEPTED", connReq)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(connReq)
}

// GET /api/v1/connections/requests/incoming
func (h *Handler) handleIncomingRequests(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))

	requests, err := h.repo.GetIncomingRequests(r.Context(), session.NxID, limit, offset)
	if err != nil {
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	if requests == nil {
		requests = []*ConnectionRequestWithProfile{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"requests": requests,
		"total":    len(requests),
	})
}

// GET /api/v1/connections/requests/outgoing
func (h *Handler) handleOutgoingRequests(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))

	requests, err := h.repo.GetOutgoingRequests(r.Context(), session.NxID, limit, offset)
	if err != nil {
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	if requests == nil {
		requests = []*ConnectionRequestWithProfile{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"requests": requests,
		"total":    len(requests),
	})
}

// POST /api/v1/connections/requests/accept
func (h *Handler) handleAcceptRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	var reqBody struct {
		RequestID string `json:"request_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	conn, err := h.repo.AcceptRequest(r.Context(), session.NxID, reqBody.RequestID)
	if err != nil {
		switch {
		case errors.Is(err, ErrRequestNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, ErrUnauthorizedAction):
			http.Error(w, err.Error(), http.StatusForbidden)
		case errors.Is(err, ErrRequestNotPending):
			http.Error(w, err.Error(), http.StatusConflict)
		case errors.Is(err, ErrRequestExpired):
			http.Error(w, err.Error(), http.StatusGone)
		case errors.Is(err, ErrBlockedByUser):
			http.Error(w, err.Error(), http.StatusForbidden)
		default:
			http.Error(w, "Internal server error", http.StatusInternalServerError)
		}
		return
	}

	// Real-time event notification to the sender
	otherUser := conn.UserANxID
	if otherUser == session.NxID {
		otherUser = conn.UserBNxID
	}
	if h.hub != nil {
		h.hub.BroadcastEventToNxID(otherUser, "CONNECTION_REQUEST_ACCEPTED", conn)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(conn)
}

// POST /api/v1/connections/requests/reject
func (h *Handler) handleRejectRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	var reqBody struct {
		RequestID string `json:"request_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	err = h.repo.RejectRequest(r.Context(), session.NxID, reqBody.RequestID)
	if err != nil {
		switch {
		case errors.Is(err, ErrRequestNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, ErrUnauthorizedAction):
			http.Error(w, err.Error(), http.StatusForbidden)
		case errors.Is(err, ErrRequestNotPending):
			http.Error(w, err.Error(), http.StatusConflict)
		default:
			http.Error(w, "Internal server error", http.StatusInternalServerError)
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "REJECTED"})
}

// POST /api/v1/connections/requests/cancel
func (h *Handler) handleCancelRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	var reqBody struct {
		RequestID string `json:"request_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	err = h.repo.CancelRequest(r.Context(), session.NxID, reqBody.RequestID)
	if err != nil {
		switch {
		case errors.Is(err, ErrRequestNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, ErrUnauthorizedAction):
			http.Error(w, err.Error(), http.StatusForbidden)
		case errors.Is(err, ErrRequestNotPending):
			http.Error(w, err.Error(), http.StatusConflict)
		default:
			http.Error(w, "Internal server error", http.StatusInternalServerError)
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "CANCELLED"})
}

// GET /api/v1/connections
func (h *Handler) handleConnections(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))

	contacts, err := h.repo.ListConnections(r.Context(), session.NxID, limit, offset)
	if err != nil {
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	if contacts == nil {
		contacts = []*PublicProfile{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"contacts": contacts,
		"total":    len(contacts),
	})
}

// POST /api/v1/connections/remove
func (h *Handler) handleRemoveConnection(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	var reqBody struct {
		TargetNxID string `json:"target_nx_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	err = h.repo.RemoveConnection(r.Context(), session.NxID, reqBody.TargetNxID)
	if err != nil {
		if errors.Is(err, ErrConnectionNotFound) {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	// Real-time event to counterpart
	if h.hub != nil {
		h.hub.BroadcastEventToNxID(reqBody.TargetNxID, "CONNECTION_REMOVED", map[string]string{
			"nx_id": session.NxID,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "REMOVED"})
}

// POST /api/v1/users/block
func (h *Handler) handleBlockUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	var reqBody struct {
		TargetNxID string `json:"target_nx_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	err = h.repo.BlockUser(r.Context(), session.NxID, reqBody.TargetNxID)
	if err != nil {
		if errors.Is(err, ErrSelfBlock) {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "BLOCKED"})
}

// POST /api/v1/users/unblock
func (h *Handler) handleUnblockUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	var reqBody struct {
		TargetNxID string `json:"target_nx_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	err = h.repo.UnblockUser(r.Context(), session.NxID, reqBody.TargetNxID)
	if err != nil {
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "UNBLOCKED"})
}

// GET /api/v1/users/blocked
func (h *Handler) handleListBlocked(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	blockedList, err := h.repo.ListBlockedUsers(r.Context(), session.NxID)
	if err != nil {
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	if blockedList == nil {
		blockedList = []*PublicProfile{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"blocked_users": blockedList,
		"total":         len(blockedList),
	})
}

// POST /api/v1/contacts/sync-qr
func (h *Handler) handleSyncQr(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var reqBody struct {
		ScannerNxID      string `json:"scanner_nx_id"`
		ScannedNxID      string `json:"scanned_nx_id"`
		ScannedSignature string `json:"scanned_signature,omitempty"`
	}
	if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	reqBody.ScannerNxID = strings.TrimSpace(reqBody.ScannerNxID)
	reqBody.ScannedNxID = strings.TrimSpace(reqBody.ScannedNxID)

	if reqBody.ScannerNxID == "" || reqBody.ScannedNxID == "" {
		http.Error(w, "Both scanner_nx_id and scanned_nx_id are required", http.StatusBadRequest)
		return
	}

	// Verify authenticated session if Authorization header is present
	if r.Header.Get("Authorization") != "" {
		session, _, err := h.authenticate(r)
		if err != nil {
			http.Error(w, err.Error(), http.StatusUnauthorized)
			return
		}
		if session.NxID != reqBody.ScannerNxID && session.NxID != reqBody.ScannedNxID {
			http.Error(w, "Authenticated user mismatch with QR participants", http.StatusForbidden)
			return
		}
	}

	conn, err := h.repo.SyncQrHandshake(r.Context(), reqBody.ScannerNxID, reqBody.ScannedNxID)
	if err != nil {
		switch {
		case errors.Is(err, ErrSelfRequest):
			http.Error(w, err.Error(), http.StatusBadRequest)
		case errors.Is(err, ErrInvalidNxID):
			http.Error(w, err.Error(), http.StatusBadRequest)
		case errors.Is(err, ErrUserNotFound):
			http.Error(w, "User not found", http.StatusNotFound)
		case errors.Is(err, ErrBlockedByUser):
			http.Error(w, "Cannot connect with blocked user", http.StatusForbidden)
		default:
			http.Error(w, "Internal server error", http.StatusInternalServerError)
		}
		return
	}

	if h.hub != nil {
		h.hub.BroadcastEventToNxID(reqBody.ScannerNxID, "CONNECTION_ESTABLISHED", conn)
		h.hub.BroadcastEventToNxID(reqBody.ScannedNxID, "CONNECTION_ESTABLISHED", conn)
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":     "SUCCESS",
		"connection": conn,
	})
}

// POST or GET /api/v1/users/phone-visibility
func (h *Handler) handlePhoneVisibility(w http.ResponseWriter, r *http.Request) {
	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	switch r.Method {
	case http.MethodPost:
		var reqBody struct {
			GranteeNxID string `json:"grantee_nx_id"`
			IsActive    bool   `json:"is_active"`
		}
		if err := json.NewDecoder(r.Body).Decode(&reqBody); err != nil {
			http.Error(w, "Invalid request body", http.StatusBadRequest)
			return
		}

		reqBody.GranteeNxID = strings.TrimSpace(reqBody.GranteeNxID)
		if !identity.IsValidNxID(reqBody.GranteeNxID) {
			http.Error(w, "Invalid grantee NX-ID format", http.StatusBadRequest)
			return
		}

		if err := h.repo.SetPhoneVisibility(r.Context(), session.NxID, reqBody.GranteeNxID, reqBody.IsActive); err != nil {
			http.Error(w, "Internal server error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":        "SUCCESS",
			"granter_nx_id": session.NxID,
			"grantee_nx_id": reqBody.GranteeNxID,
			"is_active":     reqBody.IsActive,
		})

	case http.MethodGet:
		targetNxID := strings.TrimSpace(r.URL.Query().Get("target_nx_id"))
		if targetNxID != "" {
			hasVisibility, err := h.repo.HasPhoneVisibility(r.Context(), session.NxID, targetNxID)
			if err != nil {
				http.Error(w, "Internal server error", http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(map[string]interface{}{
				"granter_nx_id": session.NxID,
				"grantee_nx_id": targetNxID,
				"is_active":     hasVisibility,
			})
			return
		}

		grantees, err := h.repo.ListPhoneVisibilityGrants(r.Context(), session.NxID)
		if err != nil {
			http.Error(w, "Internal server error", http.StatusInternalServerError)
			return
		}
		if grantees == nil {
			grantees = []string{}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"granter_nx_id": session.NxID,
			"grantees":      grantees,
			"total":         len(grantees),
		})

	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}

// GET /api/v1/connections/mutual
func (h *Handler) handleMutualConnections(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusUnauthorized)
		return
	}

	targetNxID := strings.TrimSpace(r.URL.Query().Get("target_nx_id"))
	if !identity.IsValidNxID(targetNxID) {
		http.Error(w, "Invalid target NX-ID", http.StatusBadRequest)
		return
	}

	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))

	mutuals, total, err := h.repo.GetMutualConnections(r.Context(), session.NxID, targetNxID, limit, offset)
	if err != nil {
		http.Error(w, "Internal server error", http.StatusInternalServerError)
		return
	}

	if mutuals == nil {
		mutuals = []*PublicProfile{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"target_nx_id":       targetNxID,
		"mutual_connections": mutuals,
		"total":              total,
	})
}
