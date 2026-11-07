package connection

import (
	"encoding/json"
	"testing"
	"time"
)

func TestHub_LocalDeviceRevocation(t *testing.T) {
	hub := NewHub(nil)

	s1 := &ClientSession{
		Hub:           hub,
		NxId:          "nx_alice",
		DeviceId:      "dev_phone",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	s2 := &ClientSession{
		Hub:           hub,
		NxId:          "nx_alice",
		DeviceId:      "dev_tablet",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}

	hub.Register(s1)
	hub.Register(s2)

	// Verify both registered
	hub.sessionsMu.RLock()
	if len(hub.sessions["nx_alice"]) != 2 {
		t.Fatalf("Expected 2 active sessions for nx_alice, got %d", len(hub.sessions["nx_alice"]))
	}
	hub.sessionsMu.RUnlock()

	// Revoke only dev_phone
	err := hub.RevokeDeviceSession("nx_alice", "dev_phone", "Device stolen")
	if err != nil {
		t.Fatalf("RevokeDeviceSession error: %v", err)
	}

	hub.sessionsMu.RLock()
	if _, ok := hub.sessions["nx_alice"]["dev_phone"]; ok {
		t.Fatalf("dev_phone MUST be evicted from active sessions")
	}
	if _, ok := hub.sessions["nx_alice"]["dev_tablet"]; !ok {
		t.Fatalf("dev_tablet MUST remain active")
	}
	hub.sessionsMu.RUnlock()

	// Wildcard revoke remaining devices
	_ = hub.RevokeDeviceSession("nx_alice", "*", "Account reset")

	hub.sessionsMu.RLock()
	if _, ok := hub.sessions["nx_alice"]; ok {
		t.Fatalf("nx_alice sessions map must be completely removed")
	}
	hub.sessionsMu.RUnlock()
}

func TestHub_ClusterEventEchoPrevention(t *testing.T) {
	hub := NewHub(nil)
	hub.SetInstanceID("inst_alpha")

	session := &ClientSession{
		Hub:           hub,
		NxId:          "nx_bob",
		DeviceId:      "dev_laptop",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	hub.Register(session)

	// Craft event originating from this same instance
	echoEvent := ClusterEvent{
		EventID:          "evt_1",
		Type:             EventDeviceRevoked,
		OriginInstanceID: "inst_alpha",
		NxID:             "nx_bob",
		DeviceID:         "dev_laptop",
		Payload:          "Test revoke",
		Timestamp:        time.Now().UnixMilli(),
	}
	payloadBytes, _ := json.Marshal(echoEvent)

	// Handle cluster message - should be ignored due to instance ID matching
	hub.handleClusterMessage(string(payloadBytes))

	hub.sessionsMu.RLock()
	_, found := hub.sessions["nx_bob"]["dev_laptop"]
	hub.sessionsMu.RUnlock()

	if !found {
		t.Fatalf("Session should NOT be evicted by echo event from same instance")
	}
}

func TestHub_ClusterRemoteDeviceRevocation(t *testing.T) {
	hub := NewHub(nil)
	hub.SetInstanceID("inst_beta")

	session := &ClientSession{
		Hub:           hub,
		NxId:          "nx_carol",
		DeviceId:      "dev_phone",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	hub.Register(session)

	// Craft event originating from a DIFFERENT instance
	remoteEvent := ClusterEvent{
		EventID:          "evt_2",
		Type:             EventDeviceRevoked,
		OriginInstanceID: "inst_alpha", // different instance!
		NxID:             "nx_carol",
		DeviceID:         "dev_phone",
		Payload:          "Revoked remotely from web console",
		Timestamp:        time.Now().UnixMilli(),
	}
	payloadBytes, _ := json.Marshal(remoteEvent)

	hub.handleClusterMessage(string(payloadBytes))

	hub.sessionsMu.RLock()
	_, found := hub.sessions["nx_carol"]["dev_phone"]
	hub.sessionsMu.RUnlock()

	if found {
		t.Fatalf("Remote revocation event MUST evict session on local instance")
	}
}

func TestHub_ClusterConnectionEventPropagation(t *testing.T) {
	hub := NewHub(nil)
	hub.SetInstanceID("inst_gamma")

	session := &ClientSession{
		Hub:           hub,
		NxId:          "nx_david",
		DeviceId:      "dev_tablet",
		Send:          make(chan []byte, 10),
		Authenticated: true,
	}
	hub.Register(session)

	// Remote connection request notification from another instance
	remoteEvent := ClusterEvent{
		EventID:          "evt_3",
		Type:             "connection:request",
		OriginInstanceID: "inst_alpha",
		NxID:             "nx_david",
		Payload: map[string]interface{}{
			"sender_nx_id": "nx_eve",
			"request_id":   "req_12345",
		},
		Timestamp: time.Now().UnixMilli(),
	}
	payloadBytes, _ := json.Marshal(remoteEvent)

	hub.handleClusterMessage(string(payloadBytes))

	select {
	case msg := <-session.Send:
		var parsed map[string]interface{}
		if err := json.Unmarshal(msg, &parsed); err != nil {
			t.Fatalf("Malformed event payload sent to client: %v", err)
		}
		if parsed["type"] != "connection:request" {
			t.Fatalf("Expected type 'connection:request', got %v", parsed["type"])
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("Timed out waiting for cluster connection event delivery to client")
	}
}
