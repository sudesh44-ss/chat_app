package auth

import (
	"log"
	"os"
	"strings"
	"sync"
)

// TrustedPhoneRegistry manages the authoritative server-controlled allowlist of trusted phone numbers.
// Configuration is loaded strictly on the backend from the NEXA_TRUSTED_PHONE_NUMBERS environment variable.
// Trusted numbers bypass Client-Initiated Reverse SMS verification during account registration.
// Non-trusted numbers continue to execute the normal Reverse SMS challenge.
type TrustedPhoneRegistry struct {
	mu             sync.RWMutex
	trustedNumbers map[string]bool
	isProduction   bool
}

// NewTrustedPhoneRegistry initializes the registry by reading the NEXA_TRUSTED_PHONE_NUMBERS environment variable.
func NewTrustedPhoneRegistry() *TrustedPhoneRegistry {
	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	isProduction := env == "production"

	reg := &TrustedPhoneRegistry{
		trustedNumbers: make(map[string]bool),
		isProduction:   isProduction,
	}

	rawConfig := os.Getenv("NEXA_TRUSTED_PHONE_NUMBERS")
	reg.loadConfig(rawConfig)
	return reg
}

// NewTrustedPhoneRegistryWithConfig initializes a registry with an explicit configuration string and environment flag.
// This allows clean, isolated unit and integration testing without polluting process-level environment variables.
func NewTrustedPhoneRegistryWithConfig(rawConfig string, isProduction bool) *TrustedPhoneRegistry {
	reg := &TrustedPhoneRegistry{
		trustedNumbers: make(map[string]bool),
		isProduction:   isProduction,
	}
	reg.loadConfig(rawConfig)
	return reg
}

// loadConfig parses, sanitizes, and canonicalizes trusted phone numbers using E.164 format.
// Rules:
// - Supports multiple comma-separated numbers.
// - Trims leading and trailing whitespace.
// - Skips empty tokens (e.g., from consecutive commas).
// - Normalizes entries to E.164 standard (+[1-9]\d{7,14}).
// - Never logs raw phone numbers to prevent privacy or secret leaks.
// - In production: fails closed if any entry is malformed, ensuring no unauthorized bypass.
// - In non-production: ignores malformed entries with a sanitized security warning.
func (r *TrustedPhoneRegistry) loadConfig(raw string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.trustedNumbers = make(map[string]bool)

	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return
	}

	entries := strings.Split(trimmed, ",")
	tempMap := make(map[string]bool)
	hasMalformed := false

	for _, entry := range entries {
		item := strings.TrimSpace(entry)
		if item == "" {
			continue // skip empty tokens resulting from multiple commas
		}

		canonical, err := CanonicalizePhone(item)
		if err != nil {
			hasMalformed = true
			log.Printf("[TrustedPhone] WARNING: Rejected malformed trusted number configuration entry (length: %d)", len(item))
			continue
		}

		tempMap[canonical] = true
	}

	// Fail-closed rule for production: if integrity of allowlist cannot be established, fail closed (empty allowlist)
	if r.isProduction && hasMalformed {
		log.Println("[TrustedPhone] CRITICAL: Malformed entry detected in NEXA_TRUSTED_PHONE_NUMBERS under production mode! Failing closed (allowlist cleared).")
		r.trustedNumbers = make(map[string]bool)
		return
	}

	r.trustedNumbers = tempMap
	if len(r.trustedNumbers) > 0 {
		log.Printf("[TrustedPhone] Initialized allowlist with %d trusted phone number(s).", len(r.trustedNumbers))
	}
}

// IsTrusted checks whether a submitted phone number matches any entry in the server allowlist.
// The input number is normalized to E.164 before lookup.
// Returns false if number is empty, malformed, or not in the allowlist.
func (r *TrustedPhoneRegistry) IsTrusted(phoneNumber string) bool {
	if phoneNumber == "" {
		return false
	}

	canonical, err := CanonicalizePhone(phoneNumber)
	if err != nil {
		return false
	}

	r.mu.RLock()
	defer r.mu.RUnlock()

	return r.trustedNumbers[canonical]
}

// Reload re-reads the NEXA_TRUSTED_PHONE_NUMBERS environment variable at runtime.
func (r *TrustedPhoneRegistry) Reload() {
	rawConfig := os.Getenv("NEXA_TRUSTED_PHONE_NUMBERS")
	r.loadConfig(rawConfig)
}

// SetConfig dynamically updates configuration for testing or administrative updates.
func (r *TrustedPhoneRegistry) SetConfig(rawConfig string, isProduction bool) {
	r.isProduction = isProduction
	r.loadConfig(rawConfig)
}

// Count returns the number of active trusted phone numbers in the registry.
// Does NOT expose phone numbers to callers or logs.
func (r *TrustedPhoneRegistry) Count() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.trustedNumbers)
}
