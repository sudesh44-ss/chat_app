package queue

import (
	"context"
	"testing"
	"time"
)

func TestReliableQueue_Lifecycle(t *testing.T) {
	rb, err := NewRedisBufferWithConfig("", "localhost:6379", "", false)
	if err != nil {
		t.Fatalf("Failed to initialize dev buffer: %v", err)
	}
	defer rb.Close()

	ctx := context.Background()
	queueKey := "NX-TEST-RECIPIENT"

	// 1. Initial state: queue is empty
	pending, inFlight, err := rb.GetQueueMetrics(ctx, queueKey)
	if err != nil || pending != 0 || inFlight != 0 {
		t.Fatalf("Expected 0 pending, 0 in-flight, got %d, %d (err: %v)", pending, inFlight, err)
	}

	// 2. Enqueue message 1
	msgID1 := "msg_test_001"
	payload1 := []byte("encrypted_payload_1")
	if err := rb.Enqueue(ctx, queueKey, msgID1, payload1, 0); err != nil {
		t.Fatalf("Enqueue failed: %v", err)
	}

	// 3. Idempotent Enqueue: re-enqueueing msgID1 should not duplicate
	if err := rb.Enqueue(ctx, queueKey, msgID1, payload1, 0); err != nil {
		t.Fatalf("Idempotent enqueue failed: %v", err)
	}

	// Metrics should report exactly 1 pending
	pending, inFlight, _ = rb.GetQueueMetrics(ctx, queueKey)
	if pending != 1 || inFlight != 0 {
		t.Fatalf("Expected 1 pending after idempotent enqueue, got %d, %d", pending, inFlight)
	}

	// 4. Enqueue message 2
	msgID2 := "msg_test_002"
	payload2 := []byte("encrypted_payload_2")
	_ = rb.Enqueue(ctx, queueKey, msgID2, payload2, 0)

	pending, _, _ = rb.GetQueueMetrics(ctx, queueKey)
	if pending != 2 {
		t.Fatalf("Expected 2 pending, got %d", pending)
	}

	// 5. Claim messages for delivery (non-destructive!)
	claimed, err := rb.ClaimPending(ctx, queueKey, 10, 30*time.Second)
	if err != nil {
		t.Fatalf("ClaimPending failed: %v", err)
	}
	if len(claimed) != 2 {
		t.Fatalf("Expected 2 claimed messages, got %d", len(claimed))
	}
	if claimed[0].MessageID != msgID1 || claimed[1].MessageID != msgID2 {
		t.Fatalf("Claimed order mismatch: %s, %s", claimed[0].MessageID, claimed[1].MessageID)
	}
	if claimed[0].Status != StatusInFlight || claimed[0].DeliveryAttempts != 1 {
		t.Fatalf("Claimed status mismatch: %v, attempts=%d", claimed[0].Status, claimed[0].DeliveryAttempts)
	}

	// Metrics: pending should now be 0, in-flight should be 2
	pending, inFlight, _ = rb.GetQueueMetrics(ctx, queueKey)
	if pending != 0 || inFlight != 2 {
		t.Fatalf("Expected 0 pending, 2 in-flight, got %d, %d", pending, inFlight)
	}

	// 6. Acknowledge msgID1 (confirmed recipient delivery)
	removed, err := rb.Acknowledge(ctx, queueKey, msgID1)
	if err != nil || !removed {
		t.Fatalf("Acknowledge failed: removed=%v, err=%v", removed, err)
	}

	// Second ACK on msgID1 should be idempotent (removed=false, err=nil)
	removed2, err := rb.Acknowledge(ctx, queueKey, msgID1)
	if err != nil || removed2 {
		t.Fatalf("Repeated ACK should return false without error, got removed=%v, err=%v", removed2, err)
	}

	// In-flight should now be 1 (only msgID2 remains)
	_, inFlight, _ = rb.GetQueueMetrics(ctx, queueKey)
	if inFlight != 1 {
		t.Fatalf("Expected 1 in-flight after ACK, got %d", inFlight)
	}

	// 7. Socket write failure simulation for msgID2 -> RevertInFlight
	if err := rb.RevertInFlight(ctx, queueKey, msgID2); err != nil {
		t.Fatalf("RevertInFlight failed: %v", err)
	}

	// msgID2 is back in pending!
	pending, inFlight, _ = rb.GetQueueMetrics(ctx, queueKey)
	if pending != 1 || inFlight != 0 {
		t.Fatalf("Expected 1 pending, 0 in-flight after RevertInFlight, got %d, %d", pending, inFlight)
	}

	// 8. Re-claim msgID2 and ACK it
	claimedAgain, err := rb.ClaimPending(ctx, queueKey, 10, 30*time.Second)
	if err != nil || len(claimedAgain) != 1 || claimedAgain[0].MessageID != msgID2 {
		t.Fatalf("Re-claim failed: %v, len=%d", err, len(claimedAgain))
	}
	if claimedAgain[0].DeliveryAttempts != 2 {
		t.Fatalf("Expected delivery attempts to be 2, got %d", claimedAgain[0].DeliveryAttempts)
	}

	_, _ = rb.Acknowledge(ctx, queueKey, msgID2)

	// Queue is now completely clean
	pending, inFlight, _ = rb.GetQueueMetrics(ctx, queueKey)
	if pending != 0 || inFlight != 0 {
		t.Fatalf("Expected 0 pending, 0 in-flight after full ACK, got %d, %d", pending, inFlight)
	}
}

func TestReliableQueue_ExpiredMessagesRejection(t *testing.T) {
	rb, _ := NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer rb.Close()

	ctx := context.Background()
	queueKey := "NX-EXPIRE-TEST"

	now := time.Now().Unix()
	expiredTimestamp := now - 60 // Expired 1 minute ago
	validTimestamp := now + 3600  // Valid for 1 hour

	_ = rb.Enqueue(ctx, queueKey, "expired_msg", []byte("old_data"), expiredTimestamp)
	_ = rb.Enqueue(ctx, queueKey, "valid_msg", []byte("fresh_data"), validTimestamp)

	claimed, err := rb.ClaimPending(ctx, queueKey, 10, 30*time.Second)
	if err != nil {
		t.Fatalf("ClaimPending failed: %v", err)
	}

	// Expired message must be purged and NOT returned
	if len(claimed) != 1 {
		t.Fatalf("Expected exactly 1 valid message claimed, got %d", len(claimed))
	}
	if claimed[0].MessageID != "valid_msg" {
		t.Fatalf("Expected valid_msg, got %s", claimed[0].MessageID)
	}
}

func TestReliableQueue_InFlightTimeoutReclamation(t *testing.T) {
	rb, _ := NewRedisBufferWithConfig("", "localhost:6379", "", false)
	defer rb.Close()

	ctx := context.Background()
	queueKey := "NX-TIMEOUT-TEST"

	_ = rb.Enqueue(ctx, queueKey, "timeout_msg", []byte("timeout_data"), 0)

	// Claim with short ACK timeout (20 milliseconds)
	claimed, _ := rb.ClaimPending(ctx, queueKey, 10, 20*time.Millisecond)
	if len(claimed) != 1 {
		t.Fatalf("Setup: expected 1 claimed message")
	}

	// Wait 40ms so in-flight expires
	time.Sleep(40 * time.Millisecond)

	// Next claim should automatically reclaim timeout_msg from in-flight and return it
	reclaimed, err := rb.ClaimPending(ctx, queueKey, 10, 30*time.Second)
	if err != nil {
		t.Fatalf("ClaimPending reclaim failed: %v", err)
	}
	if len(reclaimed) != 1 || reclaimed[0].MessageID != "timeout_msg" {
		t.Fatalf("Expected timeout_msg to be reclaimed and delivered, got %v", reclaimed)
	}
	if reclaimed[0].DeliveryAttempts != 2 {
		t.Fatalf("Expected delivery attempt count to increment to 2, got %d", reclaimed[0].DeliveryAttempts)
	}
}
