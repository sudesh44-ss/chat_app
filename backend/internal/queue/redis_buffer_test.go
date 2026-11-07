package queue

import (
	"context"
	"testing"
)

func TestInMemoryFallbackQueue(t *testing.T) {
	// Dev mode with localhost fallback
	rb, err := NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Unexpected error creating dev buffer: %v", err)
	}
	defer rb.Close()

	if !rb.IsDegraded() {
		t.Errorf("Expected IsDegraded() to be true for fallback buffer, got false")
	}
	if rb.IsConnected() {
		t.Errorf("Expected IsConnected() to be false for fallback buffer, got true")
	}

	ctx := context.Background()
	recipient := "nx_test_user_123"

	// Buffer should initially be empty
	packets, err := rb.PopAllPending(ctx, recipient)
	if err != nil {
		t.Fatalf("Unexpected error on empty PopAllPending: %v", err)
	}
	if len(packets) != 0 {
		t.Errorf("Expected 0 packets, got %d", len(packets))
	}

	// Push 3 packets
	p1 := []byte("payload_1")
	p2 := []byte("payload_2")
	p3 := []byte("payload_3")

	if err := rb.PushPending(ctx, recipient, p1); err != nil {
		t.Fatalf("Failed to push p1: %v", err)
	}
	if err := rb.PushPending(ctx, recipient, p2); err != nil {
		t.Fatalf("Failed to push p2: %v", err)
	}
	if err := rb.PushPending(ctx, recipient, p3); err != nil {
		t.Fatalf("Failed to push p3: %v", err)
	}

	// Pop all packets
	packets, err = rb.PopAllPending(ctx, recipient)
	if err != nil {
		t.Fatalf("Failed to pop pending packets: %v", err)
	}
	if len(packets) != 3 {
		t.Fatalf("Expected 3 packets, got %d", len(packets))
	}
	if string(packets[0]) != "payload_1" || string(packets[1]) != "payload_2" || string(packets[2]) != "payload_3" {
		t.Errorf("Packet payload order or content mismatch: %v", packets)
	}

	// Subsequent pop should be empty
	packets, err = rb.PopAllPending(ctx, recipient)
	if err != nil {
		t.Fatalf("Unexpected error on subsequent pop: %v", err)
	}
	if len(packets) != 0 {
		t.Errorf("Expected 0 packets after pop, got %d", len(packets))
	}
}

func TestProductionConfigValidation(t *testing.T) {
	// 1. In production, missing both REDIS_URL and non-local REDIS_ADDR should mark buffer degraded
	rb, err := NewRedisBufferWithConfig("", "localhost:6379", "", true)
	if err != nil {
		t.Fatalf("Expected no hard error but degraded state, got error: %v", err)
	}
	if !rb.IsDegraded() {
		t.Errorf("Expected buffer to be degraded in production without remote redis, got false")
	}

	// 2. In production, an invalid REDIS_URL should return an error
	_, err = NewRedisBufferWithConfig("invalid://not a valid url", "", "", true)
	if err == nil {
		t.Errorf("Expected error for invalid REDIS_URL in production, got nil")
	}
}
