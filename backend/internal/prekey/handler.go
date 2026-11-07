package prekey

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"nexa/wire/internal/identity"
	"nexa/wire/internal/ratelimit"
)

// ConnectionValidator verifies mutual connection and blocklist status before prekey disclosure.
type ConnectionValidator interface {
	IsBlocked(ctx context.Context, blockerNxId, blockedNxId string) (bool, error)
	IsConnected(ctx context.Context, userANxId, userBNxId string) (bool, error)
}

type Handler struct {
	repo         PrekeyRepository
	identityRepo identity.IdentityRepository
	validator    ConnectionValidator
	limiter      ratelimit.Limiter
}

func NewHandler(repo PrekeyRepository, identityRepo identity.IdentityRepository) *Handler {
	return &Handler{
		repo:         repo,
		identityRepo: identityRepo,
		limiter:      ratelimit.NewDistributedLimiter(nil, false),
	}
}

func (h *Handler) SetConnectionValidator(v ConnectionValidator) {
	h.validator = v
}

func (h *Handler) SetRateLimiter(l ratelimit.Limiter) {
	if l != nil {
		h.limiter = l
	}
}

func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/users/identity-key", h.handleUploadIdentityKey)
	mux.HandleFunc("/api/v1/users/prekeys/signed", h.handleUploadSignedPrekey)
	mux.HandleFunc("/api/v1/users/prekeys/one-time", h.handleUploadOneTimePrekeys)
	mux.HandleFunc("/api/v1/users/prekey", h.handleCombinedPrekey)
	mux.HandleFunc("/api/v1/prekeys/claim", h.handleClaimPrekey)

	// Subtree handler for /api/v1/users/{nxId}/prekey and /api/v1/users/{nxId}/prekey-bundle
	mux.HandleFunc("/api/v1/users/", h.handleUserRoutes)
}

func (h *Handler) authenticate(r *http.Request) (*identity.Session, *identity.UserAccount, error) {
	if h.identityRepo == nil {
		return nil, nil, errors.New("identity repository unavailable")
	}

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

	if session.DeviceID != "" && session.DeviceID != "default" {
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

func (h *Handler) handleUploadIdentityKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, account, err := h.authenticate(r)
	if err != nil {
		http.Error(w, "Unauthorized: "+err.Error(), http.StatusUnauthorized)
		return
	}

	var req UploadIdentityKeyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
		return
	}

	req.DhPublicKey = strings.TrimSpace(req.DhPublicKey)
	req.Signature = strings.TrimSpace(req.Signature)
	if len(req.DhPublicKey) != 64 {
		http.Error(w, "Invalid dh_public_key: must be 32 bytes (64 hex characters)", http.StatusBadRequest)
		return
	}
	if len(req.Signature) != 128 {
		http.Error(w, "Invalid signature: must be 64 bytes (128 hex characters)", http.StatusBadRequest)
		return
	}

	ts := req.Timestamp
	if ts == 0 {
		ts = time.Now().UnixMilli()
	}

	// Verify Ed25519 signature of the DH identity key
	valid, err := VerifyIdentityBindingSignature(account.PublicKeyHex, req.DhPublicKey, req.Signature, session.NxID, session.DeviceID, ts)
	if err != nil || !valid {
		http.Error(w, "Cryptographic Ed25519 signature verification failed for identity key binding", http.StatusBadRequest)
		return
	}

	binding := &IdentityKeyBinding{
		AccountID:           account.ID,
		NxID:                session.NxID,
		DeviceID:            session.DeviceID,
		SigningPublicKeyHex: account.PublicKeyHex,
		DhPublicKeyHex:      req.DhPublicKey,
		SignatureHex:        req.Signature,
		Verified:            true,
		CreatedAt:           ts,
	}

	if err := h.repo.UploadIdentityKeyBinding(r.Context(), binding); err != nil {
		log.Printf("[Prekey] Failed to commit identity key binding: %v", err)
		http.Error(w, "Internal database error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "SUCCESS",
		"message": "Identity key binding authenticated and registered successfully",
		"nx_id":   session.NxID,
		"device_id": session.DeviceID,
	})
}

func (h *Handler) handleUploadSignedPrekey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, account, err := h.authenticate(r)
	if err != nil {
		http.Error(w, "Unauthorized: "+err.Error(), http.StatusUnauthorized)
		return
	}

	var req UploadSignedPrekeyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
		return
	}

	req.PublicKey = strings.TrimSpace(req.PublicKey)
	req.Signature = strings.TrimSpace(req.Signature)
	if len(req.PublicKey) != 64 {
		http.Error(w, "Invalid public_key: must be 32 bytes (64 hex characters)", http.StatusBadRequest)
		return
	}
	if len(req.Signature) != 128 {
		http.Error(w, "Invalid signature: must be 64 bytes (128 hex characters)", http.StatusBadRequest)
		return
	}

	ts := req.Timestamp
	if ts == 0 {
		ts = time.Now().UnixMilli()
	}

	// Verify Ed25519 signature of the signed prekey
	valid, err := VerifySignedPrekeySignature(account.PublicKeyHex, req.PublicKey, req.Signature, uint32(req.KeyID), session.NxID, session.DeviceID, ts)
	if err != nil || !valid {
		http.Error(w, "Cryptographic Ed25519 signature verification failed for signed prekey", http.StatusBadRequest)
		return
	}

	spk := &SignedPrekey{
		AccountID:    account.ID,
		NxID:         session.NxID,
		DeviceID:     session.DeviceID,
		KeyID:        req.KeyID,
		PublicKeyHex: req.PublicKey,
		SignatureHex: req.Signature,
		IsActive:     true,
		CreatedAt:    ts,
		ExpiresAt:    ts + (14 * 24 * 3600 * 1000),
	}

	if err := h.repo.UploadSignedPrekey(r.Context(), spk); err != nil {
		log.Printf("[Prekey] Failed to commit signed prekey: %v", err)
		http.Error(w, "Internal database error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":     "SUCCESS",
		"message":    "Signed prekey authenticated and registered successfully",
		"key_id":     req.KeyID,
		"expires_at": spk.ExpiresAt,
	})
}

func (h *Handler) handleUploadOneTimePrekeys(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, account, err := h.authenticate(r)
	if err != nil {
		http.Error(w, "Unauthorized: "+err.Error(), http.StatusUnauthorized)
		return
	}

	var req UploadOneTimePrekeysRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
		return
	}

	if len(req.Prekeys) == 0 {
		http.Error(w, "Missing prekeys array", http.StatusBadRequest)
		return
	}
	if len(req.Prekeys) > 100 {
		http.Error(w, "Batch limit exceeded (maximum 100 one-time prekeys per upload)", http.StatusBadRequest)
		return
	}

	now := time.Now().UnixMilli()
	var opks []*OneTimePrekey
	for _, item := range req.Prekeys {
		pub := strings.TrimSpace(item.PublicKey)
		if len(pub) != 64 {
			http.Error(w, "Invalid public_key length in batch: must be 32 bytes (64 hex characters)", http.StatusBadRequest)
			return
		}

		opks = append(opks, &OneTimePrekey{
			AccountID:    account.ID,
			NxID:         session.NxID,
			DeviceID:     session.DeviceID,
			KeyID:        item.KeyID,
			PublicKeyHex: pub,
			Status:       "AVAILABLE",
			CreatedAt:    now,
		})
	}

	if err := h.repo.UploadOneTimePrekeys(r.Context(), opks); err != nil {
		log.Printf("[Prekey] Failed to batch insert OPKs: %v", err)
		http.Error(w, "Internal database error", http.StatusInternalServerError)
		return
	}

	count, _ := h.repo.GetAvailableOpkCount(r.Context(), session.NxID, session.DeviceID)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":          "SUCCESS",
		"uploaded_count": len(opks),
		"available_count": count,
	})
}

func (h *Handler) handleCombinedPrekey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, account, err := h.authenticate(r)
	if err != nil {
		http.Error(w, "Unauthorized: "+err.Error(), http.StatusUnauthorized)
		return
	}

	var req CombinedPrekeyUploadRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
		return
	}

	now := req.Timestamp
	if now == 0 {
		now = time.Now().UnixMilli()
	}

	// 1. Process identity DH key binding if provided
	if req.IdentityKey != "" && req.IdentityKeySig != "" {
		valid, err := VerifyIdentityBindingSignature(account.PublicKeyHex, req.IdentityKey, req.IdentityKeySig, session.NxID, session.DeviceID, now)
		if err != nil || !valid {
			http.Error(w, "Cryptographic Ed25519 signature verification failed for identity key binding", http.StatusBadRequest)
			return
		}
		_ = h.repo.UploadIdentityKeyBinding(r.Context(), &IdentityKeyBinding{
			AccountID:           account.ID,
			NxID:                session.NxID,
			DeviceID:            session.DeviceID,
			SigningPublicKeyHex: account.PublicKeyHex,
			DhPublicKeyHex:      req.IdentityKey,
			SignatureHex:        req.IdentityKeySig,
			Verified:            true,
			CreatedAt:           now,
		})
	}

	// 2. Process signed prekey
	if req.SignedPrekey != "" && req.SignedPrekeySig != "" {
		valid, err := VerifySignedPrekeySignature(account.PublicKeyHex, req.SignedPrekey, req.SignedPrekeySig, uint32(req.SignedPrekeyID), session.NxID, session.DeviceID, now)
		if err != nil || !valid {
			http.Error(w, "Cryptographic Ed25519 signature verification failed for signed prekey", http.StatusBadRequest)
			return
		}
		_ = h.repo.UploadSignedPrekey(r.Context(), &SignedPrekey{
			AccountID:    account.ID,
			NxID:         session.NxID,
			DeviceID:     session.DeviceID,
			KeyID:        req.SignedPrekeyID,
			PublicKeyHex: req.SignedPrekey,
			SignatureHex: req.SignedPrekeySig,
			IsActive:     true,
			CreatedAt:    now,
			ExpiresAt:    now + (14 * 24 * 3600 * 1000),
		})
	}

	// 3. Process OPKs if present
	if len(req.OneTimePrekeys) > 0 {
		var opks []*OneTimePrekey
		for _, item := range req.OneTimePrekeys {
			opks = append(opks, &OneTimePrekey{
				AccountID:    account.ID,
				NxID:         session.NxID,
				DeviceID:     session.DeviceID,
				KeyID:        item.KeyID,
				PublicKeyHex: strings.TrimSpace(item.PublicKey),
				Status:       "AVAILABLE",
				CreatedAt:    now,
			})
		}
		_ = h.repo.UploadOneTimePrekeys(r.Context(), opks)
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "SUCCESS",
		"message": "Prekey bundle registered successfully",
	})
}

func (h *Handler) handleUserRoutes(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/users/")
	if r.Method == http.MethodGet && (strings.HasSuffix(path, "/prekey") || strings.HasSuffix(path, "/prekey-bundle")) {
		h.handleGetPrekeyBundle(w, r, path)
		return
	}
	http.NotFound(w, r)
}

func (h *Handler) handleGetPrekeyBundle(w http.ResponseWriter, r *http.Request, path string) {
	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, "Unauthorized: "+err.Error(), http.StatusUnauthorized)
		return
	}

	targetNxId := strings.TrimSuffix(path, "/prekey-bundle")
	targetNxId = strings.TrimSuffix(targetNxId, "/prekey")
	targetNxId = strings.TrimSpace(targetNxId)

	if targetNxId == "" {
		http.Error(w, "Missing target NX-ID in URL path", http.StatusBadRequest)
		return
	}

	// Blocklist verification
	if h.validator != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		blocked, _ := h.validator.IsBlocked(ctx, session.NxID, targetNxId)
		if blocked {
			http.Error(w, "Communication is blocked between these accounts", http.StatusForbidden)
			return
		}
	}

	targetDeviceId := r.URL.Query().Get("device_id")
	bundle, err := h.repo.GetPrekeyBundle(r.Context(), targetNxId, targetDeviceId)
	if err != nil {
		if errors.Is(err, ErrPrekeyNotFound) || errors.Is(err, ErrNoActiveSignedPrekey) {
			http.Error(w, "Prekey bundle not registered or inactive for target NX-ID", http.StatusNotFound)
			return
		}
		log.Printf("[Prekey] Error fetching prekey bundle: %v", err)
		http.Error(w, "Internal database error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(bundle)
}

func (h *Handler) handleClaimPrekey(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, _, err := h.authenticate(r)
	if err != nil {
		http.Error(w, "Unauthorized: "+err.Error(), http.StatusUnauthorized)
		return
	}

	var req ClaimRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON payload", http.StatusBadRequest)
		return
	}

	targetNxId := req.RecipientNxID
	if targetNxId == "" {
		targetNxId = req.TargetNxID
	}
	if targetNxId == "" {
		http.Error(w, "Missing recipient_nx_id or target_nx_id", http.StatusBadRequest)
		return
	}
	if req.ClaimID == "" {
		http.Error(w, "Missing claim_id: must be a client-generated UUID", http.StatusBadRequest)
		return
	}

	// Blocklist check
	if h.validator != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		blocked, _ := h.validator.IsBlocked(ctx, session.NxID, targetNxId)
		if blocked {
			http.Error(w, "Communication is blocked between these accounts", http.StatusForbidden)
			return
		}
	}

	// Distributed rate limit check (max 60 claims / minute)
	if h.limiter != nil {
		allowed, _ := h.limiter.Allow(r.Context(), "prekey_claim:"+session.NxID, 60, time.Minute)
		if !allowed {
			http.Error(w, "Rate limit exceeded (Max 60 prekey claims/minute)", http.StatusTooManyRequests)
			return
		}
	}

	claimResp, err := h.repo.ClaimPrekey(r.Context(), session.NxID, session.DeviceID, &req)
	if err != nil {
		switch {
		case errors.Is(err, ErrClaimExpired):
			http.Error(w, "Prekey claim has expired and its one-time prekey is burned. Request a new claim with a fresh claim_id.", http.StatusGone)
		case errors.Is(err, ErrClaimOwnershipViolation):
			http.Error(w, "Forbidden: claim_id belongs to a different initiator or target", http.StatusForbidden)
		case errors.Is(err, ErrClaimAlreadyConsumed):
			http.Error(w, "Conflict: claim has already been consumed and cannot be reused", http.StatusConflict)
		case errors.Is(err, ErrNoActiveSignedPrekey) || errors.Is(err, ErrPrekeyNotFound):
			http.Error(w, "Target device has no active signed prekey or identity key", http.StatusNotFound)
		default:
			log.Printf("[Prekey] Error claiming prekey: %v", err)
			http.Error(w, "Internal error processing prekey claim: "+err.Error(), http.StatusInternalServerError)
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(claimResp)
}
