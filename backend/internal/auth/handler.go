package auth

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
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
	store            *identity.Store
	otpService       *OtpService
	googleVerifier   *GoogleVerifier
	supabaseVerifier *SupabaseVerifier
}

func NewAuthHandler(store *identity.Store, otpService *OtpService, googleVerifier *GoogleVerifier) *AuthHandler {
	return &AuthHandler{
		store:            store,
		otpService:       otpService,
		googleVerifier:   googleVerifier,
		supabaseVerifier: NewSupabaseVerifier(),
	}
}

func (h *AuthHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/auth/otp/request", h.handleOtpRequest)
	mux.HandleFunc("/api/v1/auth/otp/verify", h.handleOtpVerify)
	mux.HandleFunc("/api/v1/auth/google/verify", h.handleGoogleVerify)
	mux.HandleFunc("/api/v1/auth/supabase/verify", h.handleSupabaseVerify)
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
		PhoneNumber string `json:"phone_number"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	cooldown, err := h.otpService.RequestOtp(req.PhoneNumber, getClientIP(r))
	if err != nil {
		http.Error(w, err.Error(), http.StatusTooManyRequests)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":                  "SENT",
		"resend_cooldown_seconds": cooldown,
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
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	canonicalPhone, _ := CanonicalizePhone(req.PhoneNumber)
	existingAcc, isExisting := h.store.GetAccountByPhone(canonicalPhone)

	w.Header().Set("Content-Type", "application/json")
	resp := map[string]interface{}{
		"status":           "VERIFIED",
		"auth_ticket":      ticketToken,
		"is_existing_user": isExisting,
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
