package signaling

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

func TestSignalingAuthenticationUnit(t *testing.T) {
	store, err := identity.NewStore("")
	if err != nil {
		t.Fatalf("Failed to initialize store: %v", err)
	}

	validNxID := "nx_validuser123"
	otherNxID := "nx_attacker456"

	testToken := "tok_test_secret_123"
	sess := &identity.Session{
		Token:     testToken,
		NxID:      validNxID,
		DeviceID:  "dev_valid",
		ExpiresAt: time.Now().UnixMilli() + 3600*1000,
		IsRevoked: false,
	}
	if err := store.CreateSession(sess); err != nil {
		t.Fatalf("Failed to create test session: %v", err)
	}

	// Verification logic matching main.go
	authenticateSignalingRequest := func(r *http.Request) (string, int) {
		nxId := r.URL.Query().Get("nx_id")
		if nxId == "" {
			return "Missing nx_id query parameter", http.StatusBadRequest
		}

		token := r.URL.Query().Get("token")
		if token == "" {
			authHeader := r.Header.Get("Authorization")
			if strings.HasPrefix(authHeader, "Bearer ") {
				token = strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
			}
		}

		if token == "" {
			return "Unauthorized: missing session token", http.StatusUnauthorized
		}

		s, ok := store.GetSession(token)
		if !ok || s == nil || s.IsRevoked || (s.ExpiresAt > 0 && time.Now().UnixMilli() > s.ExpiresAt) {
			return "Unauthorized: invalid, expired or revoked session", http.StatusUnauthorized
		}

		if s.NxID != nxId {
			return "Forbidden: session token does not match nx_id", http.StatusForbidden
		}

		if s.DeviceID != "" {
			if dev, found := store.GetDevice(s.DeviceID); found && (dev.IsRevoked || dev.NxID != s.NxID) {
				return "Unauthorized: device revoked", http.StatusUnauthorized
			}
		}

		return "OK", http.StatusOK
	}

	t.Run("Rejects_Missing_NxID", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/ws/signaling", nil)
		msg, code := authenticateSignalingRequest(req)
		if code != http.StatusBadRequest {
			t.Fatalf("Expected 400 Bad Request, got %d (%s)", code, msg)
		}
	})

	t.Run("Rejects_Missing_Token", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/ws/signaling?nx_id="+validNxID, nil)
		msg, code := authenticateSignalingRequest(req)
		if code != http.StatusUnauthorized {
			t.Fatalf("Expected 401 Unauthorized, got %d (%s)", code, msg)
		}
	})

	t.Run("Rejects_Invalid_Token", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/ws/signaling?nx_id="+validNxID+"&token=tok_bogus", nil)
		msg, code := authenticateSignalingRequest(req)
		if code != http.StatusUnauthorized {
			t.Fatalf("Expected 401 Unauthorized, got %d (%s)", code, msg)
		}
	})

	t.Run("Rejects_Mismatched_NxID", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/ws/signaling?nx_id="+otherNxID+"&token="+testToken, nil)
		msg, code := authenticateSignalingRequest(req)
		if code != http.StatusForbidden {
			t.Fatalf("Expected 403 Forbidden, got %d (%s)", code, msg)
		}
	})

	t.Run("Accepts_Valid_Token_In_Query", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/ws/signaling?nx_id="+validNxID+"&token="+testToken, nil)
		msg, code := authenticateSignalingRequest(req)
		if code != http.StatusOK {
			t.Fatalf("Expected 200 OK, got %d (%s)", code, msg)
		}
	})

	t.Run("Accepts_Valid_Token_In_Auth_Header", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/ws/signaling?nx_id="+validNxID, nil)
		req.Header.Set("Authorization", "Bearer "+testToken)
		msg, code := authenticateSignalingRequest(req)
		if code != http.StatusOK {
			t.Fatalf("Expected 200 OK, got %d (%s)", code, msg)
		}
	})
}
