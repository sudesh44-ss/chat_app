package auth

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"nexa/wire/internal/identity"
)

// Bound-user prefixes for OTPs that authorise sensitive account operations.
// Binding an OTP to the account prevents an OTP issued for one purpose (for
// example registration) from being replayed against another.
const (
	phoneChangeBinding = "phone_change:"
	deletionBinding    = "account_deletion:"
)

type phoneChangeRequest struct {
	NewPhone string `json:"new_phone"`
}

type otpOnlyRequest struct {
	OtpCode string `json:"otp_code"`
}

// requireSession resolves the bearer token to a live session and account.
// It writes an error response and returns nil when authentication fails.
func (h *AuthHandler) requireSession(w http.ResponseWriter, r *http.Request) (*identity.Session, *identity.UserAccount) {
	authHeader := r.Header.Get("Authorization")
	if !strings.HasPrefix(authHeader, "Bearer ") {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return nil, nil
	}

	sessionToken := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
	if sessionToken == "" {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return nil, nil
	}

	session, ok := h.store.GetSession(sessionToken)
	if !ok || session == nil || session.IsRevoked || time.Now().UnixMilli() > session.ExpiresAt {
		http.Error(w, "Unauthorized session", http.StatusUnauthorized)
		return nil, nil
	}

	account, ok := h.store.GetAccountByNxID(session.NxID)
	if !ok || account == nil {
		http.Error(w, "Account not found", http.StatusNotFound)
		return nil, nil
	}

	return session, account
}

func jsonError(w http.ResponseWriter, message string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]interface{}{"error": message})
}

// handlePhoneChangeRequest sends an OTP to the requested new number, bound to
// the authenticated account. The number is not reserved until verification.
func (h *AuthHandler) handlePhoneChangeRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	_, account := h.requireSession(w, r)
	if account == nil {
		return
	}

	var req phoneChangeRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	newPhone, err := CanonicalizePhone(req.NewPhone)
	if err != nil {
		jsonError(w, "Invalid phone number", http.StatusBadRequest)
		return
	}

	if newPhone == account.PhoneNumber {
		jsonError(w, "New phone number must differ from the current number", http.StatusBadRequest)
		return
	}

	if existing, found := h.store.GetAccountByPhone(newPhone); found && existing.NxID != account.NxID {
		jsonError(w, "This phone number is already linked to another account", http.StatusConflict)
		return
	}

	cooldown, err := h.otpService.RequestOtpWithUser(newPhone, getClientIP(r), phoneChangeBinding+account.NxID)
	if err != nil {
		writeOtpServiceError(w, err, cooldown)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":                  "SENT",
		"success":                 true,
		"resend_cooldown_seconds": cooldown,
		"expires_in_seconds":      300,
	})
}

// handlePhoneChangeVerify verifies the OTP, enforces uniqueness and commits the
// new number server-side. The client only updates local state after this succeeds.
func (h *AuthHandler) handlePhoneChangeVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	_, account := h.requireSession(w, r)
	if account == nil {
		return
	}

	var req struct {
		NewPhone string `json:"new_phone"`
		OtpCode  string `json:"otp_code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	newPhone, err := CanonicalizePhone(req.NewPhone)
	if err != nil {
		jsonError(w, "Invalid phone number", http.StatusBadRequest)
		return
	}

	ticketToken, err := h.otpService.VerifyOtp(newPhone, req.OtpCode)
	if err != nil {
		jsonError(w, err.Error(), http.StatusUnauthorized)
		return
	}

	ticket, err := h.otpService.ConsumeTicket(ticketToken)
	if err != nil {
		jsonError(w, "Invalid or expired verification ticket", http.StatusUnauthorized)
		return
	}

	if ticket.BoundUser != phoneChangeBinding+account.NxID || ticket.PhoneNumber != newPhone {
		jsonError(w, "Verification ticket does not match this account", http.StatusUnauthorized)
		return
	}

	if existing, found := h.store.GetAccountByPhone(newPhone); found && existing.NxID != account.NxID {
		jsonError(w, "This phone number is already linked to another account", http.StatusConflict)
		return
	}

	account.PhoneNumber = newPhone
	account.PhoneVerified = true
	if err := h.store.UpdateAccount(account); err != nil {
		jsonError(w, "Failed to update phone number", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":       "SUCCESS",
		"phone_masked": maskPhone(newPhone),
	})
}

// handleAccountDeletionRequest sends an ownership OTP to the account's current
// verified number, bound to the deletion-decision purpose.
func (h *AuthHandler) handleAccountDeletionRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	_, account := h.requireSession(w, r)
	if account == nil {
		return
	}

	if account.PhoneNumber == "" || !account.PhoneVerified {
		jsonError(w, "A verified phone number is required to delete this account", http.StatusBadRequest)
		return
	}

	cooldown, err := h.otpService.RequestOtpWithUser(account.PhoneNumber, getClientIP(r), deletionBinding+account.NxID)
	if err != nil {
		writeOtpServiceError(w, err, cooldown)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":                  "SENT",
		"success":                 true,
		"phone_masked":            maskPhone(account.PhoneNumber),
		"resend_cooldown_seconds": cooldown,
		"expires_in_seconds":      300,
	})
}

// handleAccountDeletionVerify confirms ownership. It does not destroy data by
// itself; it returns a short-lived confirmation that the destructive step may
// proceed. This keeps irreversible deletion out of an OTP request handler.
func (h *AuthHandler) handleAccountDeletionVerify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	session, account := h.requireSession(w, r)
	if account == nil {
		return
	}

	var req otpOnlyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonError(w, "Malformed request body", http.StatusBadRequest)
		return
	}

	if account.PhoneNumber == "" || !account.PhoneVerified {
		jsonError(w, "A verified phone number is required to delete this account", http.StatusBadRequest)
		return
	}

	ticketToken, err := h.otpService.VerifyOtp(account.PhoneNumber, req.OtpCode)
	if err != nil {
		jsonError(w, err.Error(), http.StatusUnauthorized)
		return
	}

	ticket, err := h.otpService.ConsumeTicket(ticketToken)
	if err != nil {
		jsonError(w, "Invalid or expired verification ticket", http.StatusUnauthorized)
		return
	}

	if ticket.BoundUser != deletionBinding+account.NxID || ticket.PhoneNumber != account.PhoneNumber {
		jsonError(w, "Verification ticket does not match this account", http.StatusUnauthorized)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":                  "IDENTITY_VERIFIED",
		"nx_id":                   account.NxID,
		"device_id":               session.DeviceID,
		"deletion_window_seconds": 600,
	})
}

// writeOtpServiceError maps OTP service failures onto HTTP responses.
func writeOtpServiceError(w http.ResponseWriter, err error, cooldown int) {
	w.Header().Set("Content-Type", "application/json")
	switch {
	case errors.Is(err, ErrSmsProviderUnavailable):
		w.WriteHeader(http.StatusServiceUnavailable)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "SMS delivery service is unavailable",
			"code":  "SMS_UNAVAILABLE",
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
	case errors.Is(err, ErrOtpSecretMissing):
		w.WriteHeader(http.StatusServiceUnavailable)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"error": "Verification service is not configured",
			"code":  "OTP_NOT_CONFIGURED",
		})
	default:
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]interface{}{"error": err.Error()})
	}
}
