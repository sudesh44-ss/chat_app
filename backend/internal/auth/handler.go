package auth

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"

	"nexa/wire/internal/identity"
)

type RegisterRequest struct {
	AuthProofTicket string `json:"auth_proof_ticket"`
	AuthType        string `json:"auth_type"` // "PHONE_OTP", "GOOGLE", or "SUPABASE"
	PublicKeyHex    string `json:"public_key_hex"`
	DhPublicKeyHex  string `json:"dh_public_key_hex,omitempty"`
	DeviceID        string `json:"device_id"`
	DeviceName      string `json:"device_name"`
	ProfileName     string `json:"profile_name"`
	SignatureHex    string `json:"signature_hex,omitempty"`
	ProtocolVersion int    `json:"protocol_version"`
}

type AccountLinkRequest struct {
	LinkType        string `json:"link_type"` // "PHONE_OTP", "GOOGLE", or "SUPABASE"
	AuthProofTicket string `json:"auth_proof_ticket,omitempty"`
	GoogleIdToken   string `json:"google_id_token,omitempty"`
	SupabaseToken   string `json:"supabase_token,omitempty"`
}

type FcmTokenUpdateRequest struct {
	DeviceID string `json:"device_id"`
	FcmToken string `json:"fcm_token"`
}

type ContactLookupRequest struct {
	PhoneNumbers []string `json:"phone_numbers"`
}

type ContactLookupResponse struct {
	Matches []*identity.ContactMatch `json:"matches"`
}

type ProfileUpdateRequest struct {
	ProfileName           string `json:"profile_name,omitempty"`
	Bio                   string `json:"bio,omitempty"`
	Gender                string `json:"gender,omitempty"`
	IsDiscoverableByPhone *bool  `json:"is_discoverable_by_phone,omitempty"`
}

type AuthHandler struct {
	store             *identity.Store
	otpService        *OtpService
	reverseSmsService *ReverseSmsService
	googleVerifier    *GoogleVerifier
	supabaseVerifier  *SupabaseVerifier
}

func NewAuthHandler(store *identity.Store, otpService *OtpService, googleVerifier *GoogleVerifier) *AuthHandler {
	return &AuthHandler{
		store:             store,
		otpService:        otpService,
		reverseSmsService: NewReverseSmsService(store),
		googleVerifier:    googleVerifier,
		supabaseVerifier:  NewSupabaseVerifier(),
	}
}

func (h *AuthHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/auth/otp/request", h.handleOtpRequest)
	mux.HandleFunc("/api/v1/auth/otp/verify", h.handleOtpVerify)
	mux.HandleFunc("/api/v1/auth/google", h.handleGoogleAuth)
	mux.HandleFunc("/api/v1/auth/google/verify", h.handleGoogleVerify)
	mux.HandleFunc("/api/v1/auth/supabase/verify", h.handleSupabaseVerify)
	mux.HandleFunc("/api/v1/auth/initiate-reverse-verify", h.handleInitiateReverseVerify)
	mux.HandleFunc("/api/v1/auth/initiate-registration", h.handleInitiateReverseVerify)
	mux.HandleFunc("/api/v1/webhooks/sms-inbound", h.handleInboundSmsWebhook)
	mux.HandleFunc("/api/v1/webhooks/sms-received", h.handleInboundSmsWebhook)
	mux.HandleFunc("/api/v1/auth/verification-status", h.handleVerificationStatus)
	mux.HandleFunc("/api/v1/users/resolve", h.handleUserResolve)
	mux.HandleFunc("/api/v1/auth/register", h.handleRegister)
	mux.HandleFunc("/api/v1/auth/link", h.handleAccountLink)
	mux.HandleFunc("/api/v1/auth/logout", h.handleLogout)
	mux.HandleFunc("/api/v1/profile", h.handleProfile)
	mux.HandleFunc("/api/v1/contacts/lookup", h.handleContactLookup)
	mux.HandleFunc("/api/v1/devices", h.handleDevices)
	mux.HandleFunc("/api/v1/devices/", h.handleDeviceAction)
	mux.HandleFunc("/api/v1/devices/fcm-token", h.handleUpdateFcmToken)
}

func getClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		return strings.TrimSpace(parts[0])
	}
	ip := r.RemoteAddr
	if colon := strings.LastIndex(ip, ":"); colon != -1 {
		ip = ip[:colon]
	}
	return ip
}

func (h *AuthHandler) handleOtpRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		PhoneNumber     string `json:"phone_number"`
		GoogleAuthToken string `json:"google_auth_token,omitempty"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	// Verify and extract bound Google identity if token supplied
	boundUser := ""
	if req.GoogleAuthToken != "" && h.googleVerifier != nil {
		claims, err := h.googleVerifier.VerifyIdToken(req.GoogleAuthToken)
		if err == nil && claims != nil {
			boundUser = claims.Subject
		}
	}

	cooldown, err := h.otpService.RequestOtpWithUser(req.PhoneNumber, getClientIP(r), boundUser)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case errors.Is(err, ErrSmsProviderUnavailable):
			w.WriteHeader(http.StatusServiceUnavailable)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error":   "SMS delivery service is unavailable",
				"code":    "SMS_UNAVAILABLE",
				"details": err.Error(),
			})
		case errors.Is(err, ErrRateLimitExceeded), errors.Is(err, ErrCooldownActive):
			w.WriteHeader(http.StatusTooManyRequests)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error":                   err.Error(),
				"code":                    "RATE_LIMITED",
				"resend_cooldown_seconds": cooldown,
				"retry_after_seconds":     cooldown,
			})
		case errors.Is(err, ErrInvalidPhone):
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
				"code":  "INVALID_PHONE",
			})
		default:
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
			})
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":                  "SENT",
		"success":                 true,
		"resend_cooldown_seconds": cooldown,
		"retry_after_seconds":     cooldown,
		"expires_in_seconds":      300,
	})
}

func (h *AuthHandler) handleOtpVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		PhoneNumber string `json:"phone_number"`
		OtpCode     string `json:"otp_code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	ticketToken, err := h.otpService.VerifyOtp(req.PhoneNumber, req.OtpCode)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case errors.Is(err, ErrOtpExpired):
			w.WriteHeader(http.StatusGone)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
				"code":  "OTP_EXPIRED",
			})
		case errors.Is(err, ErrMaxAttemptsExceeded):
			w.WriteHeader(http.StatusTooManyRequests)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
				"code":  "MAX_ATTEMPTS_EXCEEDED",
			})
		case errors.Is(err, ErrInvalidOtp):
			w.WriteHeader(http.StatusUnauthorized)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
				"code":  "INVALID_OTP",
			})
		default:
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
			})
		}
		return
	}

	canonicalPhone, _ := CanonicalizePhone(req.PhoneNumber)
	existingAcc, isExisting := h.store.GetAccountByPhone(canonicalPhone)

	w.Header().Set("Content-Type", "application/json")
	resp := map[string]interface{}{
		"status":            "VERIFIED",
		"auth_ticket":       ticketToken,
		"auth_proof_ticket": ticketToken, // Dual compatibility
		"is_existing_user":  isExisting,
	}
	if isExisting {
		resp["nx_id"] = existingAcc.NxID
		resp["profile_name"] = existingAcc.ProfileName
	}
	json.NewEncoder(w).Encode(resp)
}

func (h *AuthHandler) handleSupabaseVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	claims, err := h.supabaseVerifier.VerifyToken(req.AccessToken)
	if err != nil {
		http.Error(w, "Supabase token verification failed: "+err.Error(), http.StatusUnauthorized)
		return
	}

	existingAcc, isExisting := h.store.GetAccountBySupabaseUID(claims.Subject)

	// Issue AuthTicket for Supabase authenticated session
	ticketBytes := make([]byte, 32)
	rand.Read(ticketBytes)
	ticketToken := "ticket_sb_" + hex.EncodeToString(ticketBytes)

	now := time.Now().UnixMilli()
	h.otpService.mu.Lock()
	h.otpService.tickets[ticketToken] = &AuthTicket{
		PhoneNumber: claims.Email,
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000),
		TicketToken: ticketToken,
	}
	h.otpService.mu.Unlock()

	suggestedName := ""
	if claims.UserMetadata != nil {
		if name, ok := claims.UserMetadata["full_name"].(string); ok {
			suggestedName = name
		} else if name, ok := claims.UserMetadata["name"].(string); ok {
			suggestedName = name
		}
	}

	w.Header().Set("Content-Type", "application/json")
	resp := map[string]interface{}{
		"status":           "VERIFIED",
		"auth_ticket":      ticketToken,
		"is_existing_user": isExisting,
		"supabase_user_id": claims.Subject,
		"email":            claims.Email,
		"suggested_name":   suggestedName,
	}
	if isExisting {
		resp["nx_id"] = existingAcc.NxID
		resp["profile_name"] = existingAcc.ProfileName
	}
	json.NewEncoder(w).Encode(resp)
}

func (h *AuthHandler) handleGoogleVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		IdToken string `json:"id_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	claims, err := h.googleVerifier.VerifyIdToken(req.IdToken)
	if err != nil {
		http.Error(w, "Google token verification failed: "+err.Error(), http.StatusUnauthorized)
		return
	}

	existingAcc, isExisting := h.store.GetAccountByGoogleID(claims.Subject)

	// Issue AuthTicket for Google identity
	ticketBytes := make([]byte, 32)
	rand.Read(ticketBytes)
	ticketToken := "ticket_g_" + hex.EncodeToString(ticketBytes)

	now := time.Now().UnixMilli()
	h.otpService.mu.Lock()
	h.otpService.tickets[ticketToken] = &AuthTicket{
		PhoneNumber: claims.Email,
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000),
		TicketToken: ticketToken,
	}
	h.otpService.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	resp := map[string]interface{}{
		"status":           "VERIFIED",
		"auth_ticket":      ticketToken,
		"is_existing_user": isExisting,
		"google_id":        claims.Subject,
		"email":            claims.Email,
		"suggested_name":   claims.Name,
	}
	if isExisting {
		resp["nx_id"] = existingAcc.NxID
		resp["profile_name"] = existingAcc.ProfileName
	}
	json.NewEncoder(w).Encode(resp)
}

func (h *AuthHandler) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req RegisterRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	// 1. Consume Auth Ticket (single use)
	ticket, err := h.otpService.ConsumeTicket(req.AuthProofTicket)
	if err != nil {
		http.Error(w, "Invalid or expired authentication proof: "+err.Error(), http.StatusUnauthorized)
		return
	}

	// 2. Validate Public Key format (32 bytes Ed25519)
	pubBytes, err := hex.DecodeString(strings.TrimSpace(req.PublicKeyHex))
	if err != nil || len(pubBytes) != 32 {
		http.Error(w, "Invalid 32-byte Ed25519 public key hex", http.StatusBadRequest)
		return
	}

	// 3. Verify Signature if provided (Cryptographic proof of possession)
	if req.SignatureHex != "" {
		sigBytes, err := hex.DecodeString(req.SignatureHex)
		if err == nil && len(sigBytes) == 64 {
			dataToVerify := []byte(fmt.Sprintf("%s:%s", req.PublicKeyHex, req.DeviceID))
			if !ed25519.Verify(pubBytes, dataToVerify, sigBytes) {
				http.Error(w, "Cryptographic proof of possession signature invalid", http.StatusUnauthorized)
				return
			}
		}
	}

	// 4. Derive or Allocate Unique random NX-ID server-side (Locked NX-XXXXXXXX format)
	nxID, err := h.store.AllocateNxID(req.PublicKeyHex)
	if err != nil {
		http.Error(w, "Failed to allocate NX-ID: "+err.Error(), http.StatusInternalServerError)
		return
	}

	now := time.Now().UnixMilli()

	// 5. Create or Get User Account
	var account *identity.UserAccount
	if existing, found := h.store.GetAccountByNxID(nxID); found {
		account = existing
	} else {
		account = &identity.UserAccount{
			ID:                    fmt.Sprintf("usr_%s", hex.EncodeToString(pubBytes[:8])),
			NxID:                  nxID,
			PublicKeyHex:          req.PublicKeyHex,
			DhKeyHex:              req.DhPublicKeyHex,
			ProfileName:           req.ProfileName,
			IsDiscoverableByPhone: true,
			CreatedAt:             now,
			UpdatedAt:             now,
		}

		if strings.HasPrefix(ticket.PhoneNumber, "+") {
			account.PhoneNumber = ticket.PhoneNumber
			account.PhoneVerified = true
		} else if strings.Contains(ticket.PhoneNumber, "@") {
			account.Email = ticket.PhoneNumber
		}
		if ticket.BoundUser != "" {
			account.GoogleID = ticket.BoundUser
		}

		if err := h.store.CreateAccount(account); err != nil {
			http.Error(w, "Failed to register account: "+err.Error(), http.StatusConflict)
			return
		}
	}

	// 6. Register Device
	deviceID := req.DeviceID
	if deviceID == "" {
		deviceID = fmt.Sprintf("dev_%d", now)
	}

	deviceName := req.DeviceName
	if deviceName == "" {
		deviceName = "Android Device"
	}

	device := &identity.Device{
		DeviceID:     deviceID,
		AccountID:    account.ID,
		NxID:         account.NxID,
		DeviceName:   deviceName,
		PublicKeyHex: req.PublicKeyHex,
		DhKeyHex:     req.DhPublicKeyHex,
		IsRevoked:    false,
		CreatedAt:    now,
		LastActiveAt: now,
	}
	h.store.RegisterDevice(device)

	// 7. Create 30-Day Session Token
	tokenBytes := make([]byte, 32)
	rand.Read(tokenBytes)
	sessionToken := "nexa_sess_" + hex.EncodeToString(tokenBytes)

	session := &identity.Session{
		Token:     sessionToken,
		AccountID: account.ID,
		NxID:      account.NxID,
		DeviceID:  deviceID,
		ExpiresAt: now + (30 * 24 * 60 * 60 * 1000), // 30 days
		IsRevoked: false,
		CreatedAt: now,
	}
	h.store.CreateSession(session)

	log.Printf("[Auth] Successfully registered identity %s (Device: %s)", nxID, deviceID)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":               "SUCCESS",
		"nx_id":                account.NxID,
		"session_auth_token":   sessionToken,
		"session_token":        sessionToken,
		"device_id":            deviceID,
		"registered_timestamp": now,
	})
}

func (h *AuthHandler) handleAccountLink(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	session, ok := h.store.GetSession(sessionToken)
	if !ok {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return
	}

	account, ok := h.store.GetAccountByNxID(session.NxID)
	if !ok {
		http.Error(w, "Account not found", http.StatusNotFound)
		return
	}

	var req AccountLinkRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	switch req.LinkType {
	case "PHONE_OTP":
		ticket, err := h.otpService.ConsumeTicket(req.AuthProofTicket)
		if err != nil {
			http.Error(w, "Invalid phone OTP ticket: "+err.Error(), http.StatusBadRequest)
			return
		}
		// Conflict check: Ensure phone does not belong to another account
		if existing, exists := h.store.GetAccountByPhone(ticket.PhoneNumber); exists && existing.NxID != account.NxID {
			http.Error(w, "Phone number is already associated with another NEXA account", http.StatusConflict)
			return
		}
		account.PhoneNumber = ticket.PhoneNumber
		account.PhoneVerified = true
		h.store.UpdateAccount(account)

	case "GOOGLE":
		claims, err := h.googleVerifier.VerifyIdToken(req.GoogleIdToken)
		if err != nil {
			http.Error(w, "Invalid Google ID token: "+err.Error(), http.StatusBadRequest)
			return
		}
		if existing, exists := h.store.GetAccountByGoogleID(claims.Subject); exists && existing.NxID != account.NxID {
			http.Error(w, "Google account is already associated with another NEXA account", http.StatusConflict)
			return
		}
		account.GoogleID = claims.Subject
		account.Email = claims.Email
		h.store.UpdateAccount(account)

	case "SUPABASE":
		claims, err := h.supabaseVerifier.VerifyToken(req.SupabaseToken)
		if err != nil {
			http.Error(w, "Invalid Supabase token: "+err.Error(), http.StatusBadRequest)
			return
		}
		if existing, exists := h.store.GetAccountBySupabaseUID(claims.Subject); exists && existing.NxID != account.NxID {
			http.Error(w, "Supabase account is already associated with another NEXA account", http.StatusConflict)
			return
		}
		account.SupabaseUserID = claims.Subject
		if claims.Email != "" {
			account.Email = claims.Email
		}
		h.store.UpdateAccount(account)

	default:
		http.Error(w, "Unsupported link type", http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "SUCCESS",
		"message": "Account linked successfully",
		"nx_id":   account.NxID,
	})
}

func (h *AuthHandler) handleProfile(w http.ResponseWriter, r *http.Request) {
	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	session, ok := h.store.GetSession(sessionToken)
	if !ok {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return
	}

	account, ok := h.store.GetAccountByNxID(session.NxID)
	if !ok {
		http.Error(w, "Account not found", http.StatusNotFound)
		return
	}

	switch r.Method {
	case http.MethodGet:
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(account)

	case http.MethodPut:
		var req ProfileUpdateRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, "Malformed request body", http.StatusBadRequest)
			return
		}

		if req.ProfileName != "" {
			account.ProfileName = req.ProfileName
		}
		if req.Bio != "" {
			account.Bio = req.Bio
		}
		if req.Gender != "" {
			account.Gender = req.Gender
		}
		if req.IsDiscoverableByPhone != nil {
			account.IsDiscoverableByPhone = *req.IsDiscoverableByPhone
		}

		if err := h.store.UpdateAccount(account); err != nil {
			http.Error(w, "Failed to update profile: "+err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(account)

	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}

// handleContactLookup handles authenticated phone-number contact discovery.
// Strict Privacy: Only matches against verified accounts. Does NOT store or log submitted numbers.
func (h *AuthHandler) handleContactLookup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	_, ok := h.store.GetSession(sessionToken)
	if !ok {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return
	}

	var req ContactLookupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	// Rate limiting / Batch limit: max 100 numbers per query
	if len(req.PhoneNumbers) > 100 {
		http.Error(w, "Batch limit exceeded: maximum 100 phone numbers per lookup", http.StatusBadRequest)
		return
	}

	// Normalize incoming numbers locally before matching
	var normalized []string
	for _, p := range req.PhoneNumbers {
		clean, err := CanonicalizePhone(p)
		if err == nil {
			normalized = append(normalized, clean)
		}
	}

	matches := h.store.LookupVerifiedPhoneNumbers(normalized)
	if matches == nil {
		matches = []*identity.ContactMatch{}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(ContactLookupResponse{
		Matches: matches,
	})
}

func (h *AuthHandler) handleLogout(w http.ResponseWriter, r *http.Request) {
	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	if sessionToken != "" {
		h.store.RevokeSession(sessionToken)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "SUCCESS",
		"message": "Logged out successfully",
	})
}

func (h *AuthHandler) handleDevices(w http.ResponseWriter, r *http.Request) {
	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	session, ok := h.store.GetSession(sessionToken)
	if !ok {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return
	}

	devices := h.store.GetDevicesForAccount(session.NxID)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"devices": devices,
	})
}

func (h *AuthHandler) handleDeviceAction(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/v1/devices/")
	if strings.HasSuffix(path, "/revoke") && r.Method == http.MethodPost {
		deviceID := strings.TrimSuffix(path, "/revoke")
		authHeader := r.Header.Get("Authorization")
		sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
		session, ok := h.store.GetSession(sessionToken)
		if !ok {
			http.Error(w, "Unauthorized session", http.StatusUnauthorized)
			return
		}

		targetDev, found := h.store.GetDevice(deviceID)
		if !found || targetDev.NxID != session.NxID {
			http.Error(w, "Device not found or not owned by caller", http.StatusNotFound)
			return
		}

		if err := h.store.RevokeDevice(deviceID); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":    "REVOKED",
			"device_id": deviceID,
		})
		return
	}
	http.NotFound(w, r)
}

func (h *AuthHandler) handleUpdateFcmToken(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	session, ok := h.store.GetSession(sessionToken)
	if !ok {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return
	}

	var req FcmTokenUpdateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	targetDev, found := h.store.GetDevice(req.DeviceID)
	if !found || targetDev.NxID != session.NxID {
		http.Error(w, "Device not found or not owned by caller", http.StatusNotFound)
		return
	}

	if err := h.store.UpdateDeviceFcmToken(req.DeviceID, req.FcmToken); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "UPDATED",
	})
}

// handleGoogleAuth implements single-tap Google Credential Manager backend verification.
// If the user already exists and matches the hardware device/public key, returns instant session.
// If the user is registered but from a new device, returns REQUIRE_SIM_VERIFICATION.
// If brand new user, returns NEW_USER with pre-filled Google profile data.
func (h *AuthHandler) handleGoogleAuth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		IdToken      string `json:"id_token"`
		GoogleToken  string `json:"google_token"`
		DeviceID     string `json:"device_id"`
		PublicKeyHex string `json:"public_key_hex"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	token := req.IdToken
	if token == "" {
		token = req.GoogleToken
	}

	claims, err := h.googleVerifier.VerifyIdToken(token)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Google token verification failed: " + err.Error(),
		})
		return
	}

	existingAcc, isExisting := h.store.GetAccountByGoogleID(claims.Subject)
	if !isExisting {
		// New User: proceed to Profile Setup Screen with Google pre-filled metadata
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":         "NEW_USER",
			"google_uid":     claims.Subject,
			"email":          claims.Email,
			"prefill_name":   claims.Name,
			"prefill_avatar": claims.Picture,
		})
		return
	}

	// Existing user: check if this is the same physical device/hardware key
	isSameDevice := false
	if req.DeviceID != "" {
		if dev, found := h.store.GetDevice(req.DeviceID); found && !dev.IsRevoked && dev.NxID == existingAcc.NxID {
			isSameDevice = true
		}
	}
	if !isSameDevice && req.PublicKeyHex != "" {
		if req.PublicKeyHex == existingAcc.PublicKeyHex {
			isSameDevice = true
		}
	}

	if isSameDevice {
		// Scenario 1: Same device, direct instant login (₹0 cost, zero friction)
		now := time.Now().UnixMilli()
		tokenBytes := make([]byte, 32)
		rand.Read(tokenBytes)
		sessionToken := "nexa_sess_" + hex.EncodeToString(tokenBytes)

		devID := req.DeviceID
		if devID == "" {
			devID = fmt.Sprintf("dev_%d", now)
		}

		session := &identity.Session{
			Token:     sessionToken,
			AccountID: existingAcc.ID,
			NxID:      existingAcc.NxID,
			DeviceID:  devID,
			ExpiresAt: now + (30 * 24 * 60 * 60 * 1000), // 30 days
			IsRevoked: false,
			CreatedAt: now,
		}
		h.store.CreateSession(session)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":             "LOGIN_SUCCESS",
			"session_auth_token": sessionToken,
			"session_token":      sessionToken,
			"nx_id":              existingAcc.NxID,
			"user": map[string]interface{}{
				"nx_id":       existingAcc.NxID,
				"name":        existingAcc.ProfileName,
				"phone":       existingAcc.PhoneNumber,
				"avatar_url":  existingAcc.AvatarUrl,
			},
		})
		return
	}

	// Scenario 2: New device / device switch -> Require reverse SMS verification challenge
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":                  "REQUIRE_SIM_VERIFICATION",
		"action":                  "REQUIRE_SIM_REVERIFY",
		"google_uid":              existingAcc.GoogleID,
		"registered_phone_masked": maskPhone(existingAcc.PhoneNumber),
		"phone_number":            existingAcc.PhoneNumber,
		"inbound_receiver_number": h.reverseSmsService.inboundReceiverNum,
	})
}

func (h *AuthHandler) handleInitiateReverseVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req InitiateReverseVerifyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	resp, err := h.reverseSmsService.InitiateVerification(&req, getClientIP(r))
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case errors.Is(err, ErrInvalidDomesticPhone):
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
				"code":  "INVALID_DOMESTIC_PHONE",
			})
		case errors.Is(err, ErrReverseRateLimited):
			w.WriteHeader(http.StatusTooManyRequests)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
				"code":  "RATE_LIMITED",
			})
		default:
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
			})
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func (h *AuthHandler) handleInboundSmsWebhook(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	sender := ""
	body := ""

	// Support JSON payload
	if strings.Contains(r.Header.Get("Content-Type"), "application/json") {
		var req struct {
			SenderNumber string `json:"sender_number"`
			Sender       string `json:"sender"`
			From         string `json:"from"`
			Msisdn       string `json:"msisdn"`
			Body         string `json:"body"`
			Text         string `json:"text"`
			Message      string `json:"message"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err == nil {
			if req.SenderNumber != "" {
				sender = req.SenderNumber
			} else if req.Sender != "" {
				sender = req.Sender
			} else if req.From != "" {
				sender = req.From
			} else if req.Msisdn != "" {
				sender = req.Msisdn
			}

			if req.Body != "" {
				body = req.Body
			} else if req.Text != "" {
				body = req.Text
			} else if req.Message != "" {
				body = req.Message
			}
		}
	} else {
		// Form-urlencoded
		_ = r.ParseForm()
		sender = r.FormValue("sender_number")
		if sender == "" {
			sender = r.FormValue("senderNumber")
		}
		if sender == "" {
			sender = r.FormValue("from")
		}
		if sender == "" {
			sender = r.FormValue("msisdn")
		}

		body = r.FormValue("body")
		if body == "" {
			body = r.FormValue("text")
		}
		if body == "" {
			body = r.FormValue("message")
		}
	}

	if sender == "" || body == "" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Missing sender_number or body in webhook payload",
		})
		return
	}

	record, err := h.reverseSmsService.ProcessInboundWebhook(sender, body)
	if err != nil {
		log.Printf("[InboundWebhook] Failed verification: %v", err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": err.Error(),
		})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "SUCCESS",
		"message": "Inbound reverse SMS verified successfully",
		"nx_id":   record.AllocatedNxID,
	})
}

func (h *AuthHandler) handleVerificationStatus(w http.ResponseWriter, r *http.Request) {
	nonce := r.URL.Query().Get("nonce")
	if nonce == "" {
		http.Error(w, "Missing nonce query parameter", http.StatusBadRequest)
		return
	}

	resp, err := h.reverseSmsService.GetVerificationStatus(nonce)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": err.Error(),
		})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func (h *AuthHandler) handleUserResolve(w http.ResponseWriter, r *http.Request) {
	target := r.URL.Query().Get("target")
	if target == "" {
		http.Error(w, "Missing target query parameter (Nx-ID or phone)", http.StatusBadRequest)
		return
	}

	callerNxID := ""
	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	if sessionToken != "" {
		if sess, ok := h.store.GetSession(sessionToken); ok && sess != nil {
			callerNxID = sess.NxID
		}
	}

	resp, err := h.reverseSmsService.ResolveUser(target, callerNxID)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": err.Error(),
		})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
