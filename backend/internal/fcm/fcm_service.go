package fcm

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

// FcmService manages zero-cost, privacy-first background wake-up notifications.
// CRITICAL PRIVACY RULE: FCM payloads NEVER contain plaintext message contents, sender details,
// or cryptographic material. The payload is strictly a generic wake-up signal:
// { "data": { "type": "WAKE_UP", "timestamp": "..." } }
type FcmService struct {
	projectId   string
	accessToken string
	tokenExpiry time.Time
	httpClient  *http.Client
	mu          sync.RWMutex
	isMock      bool
}

func NewFcmService() *FcmService {
	projectId := os.Getenv("FCM_PROJECT_ID")
	isMock := projectId == ""

	if isMock {
		log.Println("[FCM] Initialized in local/test mode (FCM_PROJECT_ID not set; logging wake-ups without external HTTP calls)")
	} else {
		log.Printf("[FCM] Initialized with Google Cloud project: %s", projectId)
	}

	return &FcmService{
		projectId:  projectId,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		isMock:     isMock,
	}
}

// SendWakeUp sends a silent, high-priority background wake-up ping to the specified device FCM token.
func (s *FcmService) SendWakeUp(ctx context.Context, fcmToken string) error {
	if fcmToken == "" {
		return nil
	}

	if s.isMock {
		log.Printf("[FCM] Simulated wake-up ping dispatched to device token: %s... (Payload: {type: 'WAKE_UP'})", fcmToken[:min(len(fcmToken), 12)])
		return nil
	}

	url := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", s.projectId)
	
	payload := map[string]interface{}{
		"message": map[string]interface{}{
			"token": fcmToken,
			"data": map[string]string{
				"type":      "WAKE_UP",
				"timestamp": fmt.Sprintf("%d", time.Now().UnixMilli()),
			},
			"android": map[string]interface{}{
				"priority": "HIGH",
			},
		},
	}

	bodyBytes, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to marshal FCM wake-up payload: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewBuffer(bodyBytes))
	if err != nil {
		return fmt.Errorf("failed to create FCM HTTP request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	if token := s.getAccessToken(); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	resp, err := s.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("FCM dispatch request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return fmt.Errorf("FCM API returned error status: %d", resp.StatusCode)
	}

	log.Printf("[FCM] Successfully delivered wake-up ping to device token: %s...", fcmToken[:min(len(fcmToken), 12)])
	return nil
}

// SendWakeUpBatch sends wake-up pings to multiple device tokens concurrently.
func (s *FcmService) SendWakeUpBatch(ctx context.Context, fcmTokens []string) {
	for _, token := range fcmTokens {
		go func(t string) {
			if err := s.SendWakeUp(ctx, t); err != nil {
				log.Printf("[FCM] Error dispatching wake-up to %s: %v", t[:min(len(t), 12)], err)
			}
		}(token)
	}
}

func (s *FcmService) getAccessToken() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.accessToken
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
