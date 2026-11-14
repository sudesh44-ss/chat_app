package auth

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"nexa/wire/internal/identity"
)

type RegisterRequest struct {
	AuthProofTicket    string `json:"auth_proof_ticket"`
	AuthType           string `json:"auth_type"` // "PHONE_OTP", "GOOGLE", or "SUPABASE"
	PublicKeyHex       string `json:"public_key_hex"`
	DhPublicKeyHex     string `json:"dh_public_key_hex,omitempty"`
	DevicePublicKeyHex string `json:"device_public_key_hex,omitempty"` // EC P-256 hardware keystore DER hex
	DeviceID           string `json:"device_id"`
	DeviceName         string `json:"device_name"`
	ProfileName        string `json:"profile_name"`
	SignatureHex       string `json:"signature_hex,omitempty"`
	ProtocolVersion    int    `json:"protocol_version"`
}

type LoginChallengeRequest struct {
	NxID     string `json:"nx_id"`
	DeviceID string `json:"device_id"`
}

type LoginVerifyRequest struct {
	ChallengeID string `json:"challenge_id,omitempty"`
	Challenge   string `json:"challenge"`
	NxID        string `json:"nx_id"`
	DeviceID    string `json:"device_id"`
	Signature   string `json:"signature"`
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
	AvatarUrl             string `json:"avatar_url,omitempty"`
	Bio                   string `json:"bio,omitempty"`
	Gender                string `json:"gender,omitempty"`
	Age                   *int   `json:"age,omitempty"`
	IsDiscoverableByPhone *bool  `json:"is_discoverable_by_phone,omitempty"`
}

// SessionRevoker defines the contract for actively closing WebSocket sessions upon logout or device revocation.
type SessionRevoker interface {
	RevokeDeviceSession(nxId string, deviceId string, reason string) error
}

type AuthHandler struct {
	store             identity.IdentityRepository
	otpService        *OtpService
	reverseSmsService *ReverseSmsService
	googleVerifier    *GoogleVerifier
	supabaseVerifier  *SupabaseVerifier
	challengeStore    ChallengeStore
	revoker           SessionRevoker
}

func NewAuthHandler(store identity.IdentityRepository, otpService *OtpService, googleVerifier *GoogleVerifier) *AuthHandler {
	revSms := NewReverseSmsService(store)
	if googleVerifier != nil {
		revSms.SetGoogleVerifier(googleVerifier)
	}
	return &AuthHandler{
		store:             store,
		otpService:        otpService,
		reverseSmsService: revSms,
		googleVerifier:    googleVerifier,
		supabaseVerifier:  NewSupabaseVerifier(),
		challengeStore:    NewMemoryChallengeStore(),
	}
}

func (h *AuthHandler) SetChallengeStore(cs ChallengeStore) {
	if cs != nil {
		h.challengeStore = cs
	}
}

func (h *AuthHandler) SetRedisClient(client *redis.Client) {
	if h.reverseSmsService != nil {
		h.reverseSmsService.SetRedisClient(client)
	}
}

func (h *AuthHandler) SetSessionRevoker(revoker SessionRevoker) {
	h.revoker = revoker
}

func (h *AuthHandler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/v1/auth/otp/request", h.handleOtpRequest)
	mux.HandleFunc("/api/v1/auth/otp/verify", h.handleOtpVerify)
	mux.HandleFunc("/api/v1/auth/google", h.handleGoogleAuth)
	mux.HandleFunc("/api/v1/auth/google/verify", h.handleGoogleVerify)
	mux.HandleFunc("/api/v1/auth/supabase/verify", h.handleSupabaseVerify)
	mux.HandleFunc("/api/v1/auth/login-challenge", h.handleLoginChallenge)
	mux.HandleFunc("/api/v1/auth/login-verify", h.handleLoginVerify)
	mux.HandleFunc("/api/v1/auth/initiate-reverse-verify", h.handleInitiateReverseVerify)
	mux.HandleFunc("/api/v1/auth/initiate-reverse-sms", h.handleInitiateReverseVerify)
	mux.HandleFunc("/api/v1/auth/initiate-registration", h.handleInitiateReverseVerify)
	mux.HandleFunc("/api/v1/webhooks/sms-inbound", h.handleInboundSmsWebhook)
	mux.HandleFunc("/api/v1/webhooks/sms-received", h.handleInboundSmsWebhook)
	mux.HandleFunc("/api/v1/auth/verification-status", h.handleVerificationStatus)
	mux.HandleFunc("/api/v1/auth/status", h.handleVerificationStatus)
	mux.HandleFunc("/api/v1/users/resolve", h.handleUserResolve)
	mux.HandleFunc("/api/v1/auth/register", h.handleRegister)
	mux.HandleFunc("/api/v1/auth/link", h.handleAccountLink)
	mux.HandleFunc("/api/v1/auth/logout", h.handleLogout)
	mux.HandleFunc("/api/v1/auth/session/rotate", h.handleSessionRotate)
	mux.HandleFunc("/api/v1/auth/session/refresh", h.handleSessionRotate)
	mux.HandleFunc("/api/v1/profile", h.handleProfile)
	mux.HandleFunc("/api/v1/contacts/lookup", h.handleContactLookup)
	mux.HandleFunc("/api/v1/auth/devices", h.handleDevices)
	mux.HandleFunc("/api/v1/devices/", h.handleDeviceAction)
	mux.HandleFunc("/api/v1/devices/fcm-token", h.handleUpdateFcmToken)
	mux.HandleFunc("/api/v1/auth/phone/change/request", h.handlePhoneChangeRequest)
	mux.HandleFunc("/api/v1/auth/phone/change/verify", h.handlePhoneChangeVerify)
	mux.HandleFunc("/api/v1/auth/account/deletion/request", h.handleAccountDeletionRequest)
	mux.HandleFunc("/api/v1/auth/account/deletion/verify", h.handleAccountDeletionVerify)
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

// writeAuthStateUnavailable reports a distributed OTP/ticket backend outage.
// Callers must not treat this as an authentication failure: retrying later is
// expected to succeed, whereas an invalid ticket will not.
func (h *AuthHandler) writeAuthStateUnavailable(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusServiceUnavailable)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"error": "Verification service is temporarily unavailable",
		"code":  "AUTH_STATE_UNAVAILABLE",
	})
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
		case errors.Is(err, ErrOtpStateUnavailable):
			w.WriteHeader(http.StatusServiceUnavailable)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": "Verification service is temporarily unavailable",
				"code":  "AUTH_STATE_UNAVAILABLE",
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
		case errors.Is(err, ErrOtpStateUnavailable):
			w.WriteHeader(http.StatusServiceUnavailable)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": "Verification service is temporarily unavailable",
				"code":  "AUTH_STATE_UNAVAILABLE",
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
		if errors.Is(err, ErrSupabaseVerifierUnconfigured) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": "Supabase verification is not configured on this server",
				"code":  "AUTH_CONFIG_UNAVAILABLE",
			})
			return
		}
		http.Error(w, "Supabase token verification failed: "+err.Error(), http.StatusUnauthorized)
		return
	}

	existingAcc, isExisting := h.store.GetAccountBySupabaseUID(claims.Subject)

	// Issue AuthTicket for Supabase authenticated session
	ticketToken, err := generateTicketToken("ticket_sb_")
	if err != nil {
		http.Error(w, "Failed to issue auth ticket", http.StatusInternalServerError)
		return
	}

	now := time.Now().UnixMilli()
	if err := h.otpService.IssueTicket(&AuthTicket{
		BoundUser:   claims.Subject,
		Provider:    "SUPABASE",
		Email:       claims.Email,
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000),
		TicketToken: ticketToken,
	}); err != nil {
		http.Error(w, "Failed to persist auth ticket", http.StatusInternalServerError)
		return
	}

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
	ticketToken, err := generateTicketToken("ticket_g_")
	if err != nil {
		http.Error(w, "Failed to issue auth ticket", http.StatusInternalServerError)
		return
	}

	now := time.Now().UnixMilli()
	if err := h.otpService.IssueTicket(&AuthTicket{
		BoundUser:   claims.Subject,
		Provider:    "GOOGLE",
		Email:       claims.Email,
		VerifiedAt:  now,
		ExpiresAt:   now + (10 * 60 * 1000),
		TicketToken: ticketToken,
	}); err != nil {
		http.Error(w, "Failed to persist auth ticket", http.StatusInternalServerError)
		return
	}

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

	// Sessions already present when registration starts are "prior", even if
	// they share a millisecond timestamp with the new session.
	requestStartedAt := time.Now().UnixMilli()

	var req RegisterRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	// 1. Consume Auth Ticket (single use)
	ticket, err := h.otpService.ConsumeTicket(req.AuthProofTicket)
	if err != nil {
		if errors.Is(err, ErrOtpStateUnavailable) {
			h.writeAuthStateUnavailable(w)
			return
		}
		http.Error(w, "Invalid or expired authentication proof: "+err.Error(), http.StatusUnauthorized)
		return
	}

	// 1a. Reject tickets that were issued for a different purpose. Phone-change
	// and account-deletion OTPs are bound to a specific account and may only be
	// redeemed by their own verify handlers; accepting them here would let a
	// sensitive-operation ticket create or hijack a registration.
	if strings.HasPrefix(ticket.BoundUser, phoneChangeBinding) ||
		strings.HasPrefix(ticket.BoundUser, deletionBinding) {
		http.Error(w, "Authentication proof is not valid for registration", http.StatusUnauthorized)
		return
	}
	switch ticket.Provider {
	case "PHONE_OTP", "GOOGLE", "SUPABASE":
	default:
		http.Error(w, "Authentication proof has an unsupported provider", http.StatusUnauthorized)
		return
	}

	// 2. Validate Public Key format (32 bytes Ed25519)
	pubBytes, err := hex.DecodeString(strings.TrimSpace(req.PublicKeyHex))
	if err != nil || len(pubBytes) != 32 {
		http.Error(w, "Invalid 32-byte Ed25519 public key hex", http.StatusBadRequest)
		return
	}

	// 3. Verify Signature (Cryptographic proof of possession)
	if req.SignatureHex != "" {
		sigBytes, err := hex.DecodeString(strings.TrimSpace(req.SignatureHex))
		if err != nil || len(sigBytes) != ed25519.SignatureSize {
			http.Error(w, "Cryptographic proof of possession signature invalid format", http.StatusBadRequest)
			return
		}
		boundData := []byte(fmt.Sprintf("NEXA_REGISTER:%s:%s:%s", req.AuthProofTicket, req.PublicKeyHex, req.DeviceID))
		legacyData := []byte(fmt.Sprintf("%s:%s", req.PublicKeyHex, req.DeviceID))

		if !ed25519.Verify(pubBytes, boundData, sigBytes) && !ed25519.Verify(pubBytes, legacyData, sigBytes) {
			http.Error(w, "Cryptographic proof of possession signature invalid", http.StatusUnauthorized)
			return
		}
	} else {
		env := strings.ToLower(os.Getenv("APP_ENV"))
		if env == "" {
			env = strings.ToLower(os.Getenv("ENV"))
		}
		if env == "production" {
			http.Error(w, "Cryptographic proof of possession signature (signature_hex) is mandatory in production", http.StatusBadRequest)
			return
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

		if ticket.PhoneNumber != "" && strings.HasPrefix(ticket.PhoneNumber, "+") {
			account.PhoneNumber = ticket.PhoneNumber
			account.PhoneVerified = true
		}
		if ticket.Email != "" {
			account.Email = ticket.Email
		}

		// Bind the server-verified external identity to the correct column.
		// Never infer the provider from the shape of a client-supplied value.
		switch ticket.Provider {
		case "GOOGLE":
			account.GoogleID = ticket.BoundUser
		case "SUPABASE":
			account.SupabaseUserID = ticket.BoundUser
		default:
			// Phone OTP tickets may additionally carry a verified Google UID
			// obtained from a server-verified Google token at request time.
			if ticket.BoundUser != "" {
				account.GoogleID = ticket.BoundUser
			}
		}

		if err := h.store.CreateAccount(account); err != nil {
			http.Error(w, "Failed to register account: "+err.Error(), http.StatusConflict)
			return
		}
	}

	// 6. Register Device (single active device per account)
	deviceID := req.DeviceID
	if deviceID == "" {
		deviceID = fmt.Sprintf("dev_%d", now)
	}

	deviceName := req.DeviceName
	if deviceName == "" {
		deviceName = "Android Device"
	}

	devPubKey := req.DevicePublicKeyHex
	if devPubKey == "" {
		devPubKey = req.PublicKeyHex
	}

	device := &identity.Device{
		DeviceID:     deviceID,
		AccountID:    account.ID,
		NxID:         account.NxID,
		DeviceName:   deviceName,
		PublicKeyHex: devPubKey,
		DhKeyHex:     req.DhPublicKeyHex,
		IsRevoked:    false,
		CreatedAt:    now,
		LastActiveAt: now,
	}
	revokedDevices, err := h.store.RegisterDeviceReplacingOthers(device, "replaced by new registration")
	if err != nil {
		http.Error(w, "Failed to register device: "+err.Error(), http.StatusInternalServerError)
		return
	}
	if len(revokedDevices) > 0 {
		log.Printf("[Auth] Single-active-device: revoked %d prior device(s) for %s", len(revokedDevices), account.NxID)
		// Drop any live WebSocket for the replaced devices so the old device
		// cannot keep receiving messages after its session is revoked.
		if h.revoker != nil {
			for _, revokedID := range revokedDevices {
				_ = h.revoker.RevokeDeviceSession(account.NxID, revokedID, "replaced by new registration")
			}
		}
	}

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
	if err := h.store.CreateSession(session); err != nil {
		log.Printf("[Auth] Failed to create registration session for %s: %v", account.NxID, err)
		http.Error(w, "Failed to create session", http.StatusInternalServerError)
		return
	}

	// Session rotation: retire earlier sessions for this device, but never a
	// token created at/after the new one (protects concurrent rotations).
	if _, err := h.store.RevokeSessionsForDevice(account.NxID, deviceID, sessionToken, requestStartedAt+1); err != nil {
		log.Printf("[Auth] WARNING: failed to revoke prior sessions for device %s: %v", deviceID, err)
	}

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
			if errors.Is(err, ErrOtpStateUnavailable) {
				h.writeAuthStateUnavailable(w)
				return
			}
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
			if errors.Is(err, ErrSupabaseVerifierUnconfigured) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusServiceUnavailable)
				json.NewEncoder(w).Encode(map[string]interface{}{
					"error": "Supabase verification is not configured on this server",
					"code":  "AUTH_CONFIG_UNAVAILABLE",
				})
				return
			}
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
		if req.AvatarUrl != "" {
			account.AvatarUrl = req.AvatarUrl
		}
		if req.Bio != "" {
			account.Bio = req.Bio
		}
		if req.Gender != "" {
			account.Gender = req.Gender
		}
		if req.Age != nil {
			account.Age = *req.Age
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
		if session, ok := h.store.GetSession(sessionToken); ok && session != nil {
			if h.revoker != nil {
				_ = h.revoker.RevokeDeviceSession(session.NxID, session.DeviceID, "Session logged out")
			}
		}
		h.store.RevokeSession(sessionToken)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "SUCCESS",
		"message": "Logged out successfully",
	})
}

// handleSessionRotate exchanges a valid session token for a fresh one and
// revokes every earlier session bound to the same account/device. This bounds
// the blast radius of a leaked long-lived token without forcing a re-login.
func (h *AuthHandler) handleSessionRotate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Sessions that existed when this request started are "prior", even if they
	// share the same millisecond timestamp as the replacement.
	requestStartedAt := time.Now().UnixMilli()

	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimPrefix(authHeader, "Bearer ")
	if sessionToken == "" || sessionToken == authHeader {
		http.Error(w, "Unauthorized: missing session token", http.StatusUnauthorized)
		return
	}

	session, ok := h.store.GetSession(sessionToken)
	if !ok || session == nil {
		http.Error(w, "Unauthorized: invalid or expired session", http.StatusUnauthorized)
		return
	}

	if session.DeviceID != "" {
		if dev, found := h.store.GetDevice(session.DeviceID); found && (dev.IsRevoked || dev.NxID != session.NxID) {
			http.Error(w, "Unauthorized: device revoked", http.StatusUnauthorized)
			return
		}
	}

	now := time.Now().UnixMilli()
	tokenBytes := make([]byte, 32)
	rand.Read(tokenBytes)
	newToken := "nexa_sess_" + hex.EncodeToString(tokenBytes)

	newSession := &identity.Session{
		Token:     newToken,
		AccountID: session.AccountID,
		NxID:      session.NxID,
		DeviceID:  session.DeviceID,
		ExpiresAt: now + (30 * 24 * 60 * 60 * 1000),
		IsRevoked: false,
		CreatedAt: now,
	}
	if err := h.store.CreateSession(newSession); err != nil {
		http.Error(w, "Failed to rotate session", http.StatusInternalServerError)
		return
	}

	// Revoke the presented token plus any sibling sessions that existed when the
	// request started. Tokens created later by a concurrent rotation survive.
	if _, err := h.store.RevokeSessionsForDevice(session.NxID, session.DeviceID, newToken, requestStartedAt+1); err != nil {
		log.Printf("[Auth] WARNING: rotation created %s but failed to revoke prior sessions: %v", newToken, err)
	}
	_ = h.store.RevokeSession(sessionToken)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":             "SESSION_ROTATED",
		"session_auth_token": newToken,
		"session_token":      newToken,
		"expires_at":         newSession.ExpiresAt,
		"nx_id":              newSession.NxID,
		"device_id":          newSession.DeviceID,
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

	active := h.store.GetDevicesForAccount(session.NxID)
	all := h.store.GetAllDevicesForAccount(session.NxID)

	revoked := make([]*identity.Device, 0)
	for _, d := range all {
		if d.IsRevoked {
			revoked = append(revoked, d)
		}
	}

	events, err := h.store.GetDeviceEvents(session.NxID, 50)
	if err != nil {
		log.Printf("[Auth] Could not load device events for %s: %v", session.NxID, err)
		events = nil
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"devices":         active,
		"revoked_devices": revoked,
		"events":          events,
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

		if h.revoker != nil {
			_ = h.revoker.RevokeDeviceSession(targetDev.NxID, deviceID, "Device revoked by user")
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
		// Scenario 1: Same device - Issue cryptographic challenge to verify hardware key possession
		chal, err := h.challengeStore.CreateChallenge(r.Context(), existingAcc.NxID, req.DeviceID, 60*time.Second)
		if err != nil {
			http.Error(w, "Failed to create authentication challenge: "+err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":             "REQUIRE_DEVICE_CHALLENGE",
			"challenge_id":       chal.ChallengeID,
			"challenge":          chal.Challenge,
			"nx_id":              existingAcc.NxID,
			"device_id":          req.DeviceID,
			"expires_in_seconds": 60,
			"timestamp":          chal.CreatedAt,
			"user": map[string]interface{}{
				"nx_id":      existingAcc.NxID,
				"name":       existingAcc.ProfileName,
				"phone":      existingAcc.PhoneNumber,
				"avatar_url": existingAcc.AvatarUrl,
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
		case errors.Is(err, ErrPhoneAlreadyRegistered):
			w.WriteHeader(http.StatusConflict)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": err.Error(),
				"code":  "PHONE_ALREADY_REGISTERED",
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

	// 1. Read entire raw request body
	bodyBytes, err := io.ReadAll(r.Body)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Failed to read request body: " + err.Error(),
		})
		return
	}

	// 2. Authenticate webhook HMAC-SHA256 signature (fail-closed if secret missing or invalid signature)
	if err := VerifyGatewaySignature(r, bodyBytes); err != nil {
		log.Printf("[InboundWebhook] Unauthorized request rejected: %v (IP: %s)", err, getClientIP(r))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": err.Error(),
			"code":  "UNAUTHORIZED_WEBHOOK",
		})
		return
	}

	sender := ""
	body := ""

	// 3. Support JSON or form-urlencoded payload from validated raw body
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
		if err := json.Unmarshal(bodyBytes, &req); err == nil {
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
		formValues, err := url.ParseQuery(string(bodyBytes))
		if err == nil {
			sender = formValues.Get("sender_number")
			if sender == "" {
				sender = formValues.Get("senderNumber")
			}
			if sender == "" {
				sender = formValues.Get("from")
			}
			if sender == "" {
				sender = formValues.Get("msisdn")
			}

			body = formValues.Get("body")
			if body == "" {
				body = formValues.Get("text")
			}
			if body == "" {
				body = formValues.Get("message")
			}
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
		nonce = r.URL.Query().Get("sessionId")
	}
	if nonce == "" {
		nonce = r.URL.Query().Get("session_id")
	}
	if nonce == "" {
		http.Error(w, "Missing nonce or sessionId query parameter", http.StatusBadRequest)
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
	// Authentication required to prevent unauthenticated scraping and phone number enumeration
	authHeader := r.Header.Get("Authorization")
	sessionToken := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
	if sessionToken == "" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Authentication required: missing or empty session token",
		})
		return
	}

	sess, ok := h.store.GetSession(sessionToken)
	if !ok || sess == nil || sess.IsRevoked || (sess.ExpiresAt > 0 && time.Now().UnixMilli() > sess.ExpiresAt) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Authentication failed: invalid, revoked, or expired session",
		})
		return
	}

	if sess.DeviceID != "" {
		if dev, found := h.store.GetDevice(sess.DeviceID); found && (dev.IsRevoked || dev.NxID != sess.NxID) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": "Authentication failed: session device is revoked or mismatched",
			})
			return
		}
	}

	target := r.URL.Query().Get("target")
	if target == "" {
		http.Error(w, "Missing target query parameter (Nx-ID or phone)", http.StatusBadRequest)
		return
	}

	callerNxID := sess.NxID
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

func (h *AuthHandler) handleLoginChallenge(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req LoginChallengeRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	if req.NxID == "" || req.DeviceID == "" {
		http.Error(w, "nx_id and device_id are required", http.StatusBadRequest)
		return
	}

	account, found := h.store.GetAccountByNxID(req.NxID)
	if !found {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Account not found",
			"code":  "ACCOUNT_NOT_FOUND",
		})
		return
	}

	device, found := h.store.GetDevice(req.DeviceID)
	if !found || device.IsRevoked || device.NxID != account.NxID {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Device is not registered or revoked",
			"code":  "DEVICE_UNAUTHORIZED",
		})
		return
	}

	chal, err := h.challengeStore.CreateChallenge(r.Context(), req.NxID, req.DeviceID, 60*time.Second)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Failed to create challenge: " + err.Error(),
		})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":             "CHALLENGE_ISSUED",
		"challenge_id":       chal.ChallengeID,
		"challenge":          chal.Challenge,
		"nx_id":              chal.NxID,
		"device_id":          chal.DeviceID,
		"expires_in_seconds": 60,
		"timestamp":          chal.CreatedAt,
	})
}

func (h *AuthHandler) handleLoginVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Sessions already present when login verification starts are "prior", even
	// if they share a millisecond timestamp with the new session.
	requestStartedAt := time.Now().UnixMilli()

	var req LoginVerifyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	challengeKey := req.ChallengeID
	if challengeKey == "" {
		challengeKey = req.Challenge
	}
	if challengeKey == "" {
		http.Error(w, "Missing challenge identifier", http.StatusBadRequest)
		return
	}

	// 1. Atomically consume the challenge (anti-replay single use)
	chal, err := h.challengeStore.ConsumeChallenge(r.Context(), challengeKey)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Authentication challenge invalid or expired: " + err.Error(),
			"code":  "CHALLENGE_INVALID_OR_EXPIRED",
		})
		return
	}

	// 2. Validate Identity & Device binding
	if (req.NxID != "" && req.NxID != chal.NxID) || (req.DeviceID != "" && req.DeviceID != chal.DeviceID) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Challenge parameters mismatch identity or device",
			"code":  "IDENTITY_MISMATCH",
		})
		return
	}

	account, found := h.store.GetAccountByNxID(chal.NxID)
	if !found {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Account not found for challenged identity",
			"code":  "ACCOUNT_NOT_FOUND",
		})
		return
	}

	device, found := h.store.GetDevice(chal.DeviceID)
	if !found || device.IsRevoked || device.NxID != chal.NxID {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Device is not registered or has been revoked",
			"code":  "DEVICE_UNAUTHORIZED",
		})
		return
	}

	// 3. Cryptographic Signature Verification
	// The device public key may be an EC P-256 public key (X.509 DER hex from Keystore) or Ed25519 (32-byte hex)
	registeredKeyHex := device.PublicKeyHex
	if registeredKeyHex == "" {
		registeredKeyHex = account.PublicKeyHex
	}

	pubBytes, err := hex.DecodeString(strings.TrimSpace(registeredKeyHex))
	if err != nil || len(pubBytes) == 0 {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Invalid registered public key in identity store",
		})
		return
	}

	// Decode signature (supports hex and standard base64)
	sigBytes, err := hex.DecodeString(strings.TrimSpace(req.Signature))
	if err != nil || len(sigBytes) == 0 {
		sigBytes, err = base64.StdEncoding.DecodeString(strings.TrimSpace(req.Signature))
		if err != nil || len(sigBytes) == 0 {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"error": "Invalid signature encoding (expected hex or base64)",
				"code":  "INVALID_SIGNATURE_ENCODING",
			})
			return
		}
	}

	// Decode raw challenge bytes
	var challengeBytes []byte
	challengeBytes, err = hex.DecodeString(chal.Challenge)
	if err != nil || len(challengeBytes) == 0 {
		challengeBytes = []byte(chal.Challenge)
	}

	signatureValid := false

	// Attempt 1: EC P-256 (SubjectPublicKeyInfo PKIX DER)
	if pub, err := x509.ParsePKIXPublicKey(pubBytes); err == nil {
		if ecPub, ok := pub.(*ecdsa.PublicKey); ok {
			digest := sha256.Sum256(challengeBytes)
			if ecdsa.VerifyASN1(ecPub, digest[:], sigBytes) {
				signatureValid = true
			}
		}
	}

	// Attempt 2: Uncompressed EC P-256 point (65 bytes: 0x04 || X || Y)
	if !signatureValid && len(pubBytes) == 65 && pubBytes[0] == 0x04 {
		x, y := elliptic.Unmarshal(elliptic.P256(), pubBytes)
		if x != nil && y != nil {
			ecPub := &ecdsa.PublicKey{Curve: elliptic.P256(), X: x, Y: y}
			digest := sha256.Sum256(challengeBytes)
			if ecdsa.VerifyASN1(ecPub, digest[:], sigBytes) {
				signatureValid = true
			}
		}
	}

	// Attempt 3: Ed25519 public key (32 bytes)
	if !signatureValid && len(pubBytes) == 32 && len(sigBytes) == 64 {
		if ed25519.Verify(pubBytes, challengeBytes, sigBytes) {
			signatureValid = true
		}
	}

	if !signatureValid {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Cryptographic signature verification failed: invalid hardware proof of possession",
			"code":  "SIGNATURE_INVALID",
		})
		return
	}

	// 4. Issue authenticated 30-day session token
	now := time.Now().UnixMilli()

	tokenBytes := make([]byte, 32)
	rand.Read(tokenBytes)
	sessionToken := "nexa_sess_" + hex.EncodeToString(tokenBytes)

	session := &identity.Session{
		Token:     sessionToken,
		AccountID: account.ID,
		NxID:      account.NxID,
		DeviceID:  device.DeviceID,
		ExpiresAt: now + (30 * 24 * 60 * 60 * 1000),
		IsRevoked: false,
		CreatedAt: now,
	}
	if err := h.store.CreateSession(session); err != nil {
		log.Printf("[Auth] Failed to create login session for %s: %v", account.NxID, err)
		http.Error(w, "Failed to create session", http.StatusInternalServerError)
		return
	}

	// Session rotation: a fresh re-authentication on this device revokes every
	// earlier token for it, so a stolen old token stops working. Tokens created
	// at/after the new one are left alone to survive concurrent rotations.
	if _, err := h.store.RevokeSessionsForDevice(account.NxID, device.DeviceID, sessionToken, requestStartedAt+1); err != nil {
		log.Printf("[Auth] WARNING: failed to revoke prior sessions for device %s: %v", device.DeviceID, err)
	}

	// Close any WebSocket still authenticated with the device's previous
	// session. The client reconnects with the new token after this response.
	if h.revoker != nil {
		_ = h.revoker.RevokeDeviceSession(account.NxID, device.DeviceID, "session rotated on re-authentication")
	}

	device.LastActiveAt = now

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":             "LOGIN_SUCCESS",
		"session_auth_token": sessionToken,
		"session_token":      sessionToken,
		"nx_id":              account.NxID,
		"device_id":          device.DeviceID,
		"user": map[string]interface{}{
			"nx_id":      account.NxID,
			"name":       account.ProfileName,
			"phone":      account.PhoneNumber,
			"avatar_url": account.AvatarUrl,
		},
	})
}
