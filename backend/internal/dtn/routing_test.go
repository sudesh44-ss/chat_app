package dtn

import (
	"bytes"
	"encoding/hex"
	"testing"
)

func TestRouting_CrossPlatformDeterministicVector(t *testing.T) {
	pairwiseSecret, err := hex.DecodeString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
	if err != nil {
		t.Fatalf("failed to decode secret hex: %v", err)
	}
	senderNxId := "NX-ALICE-1234"
	recipientNxId := "NX-BOB-5678"
	epoch := int64(20000)

	kRoute, err := DeriveRoutingKey(pairwiseSecret, senderNxId, recipientNxId)
	if err != nil {
		t.Fatalf("failed to derive routing key: %v", err)
	}

	expectedKRouteHex := "e13808db205ecb93a50bee48449281582d83471225e65332d61318d4d333f7af"
	if hex.EncodeToString(kRoute) != expectedKRouteHex {
		t.Fatalf("kRoute mismatch:\n got:  %s\n want: %s", hex.EncodeToString(kRoute), expectedKRouteHex)
	}

	routingToken, err := GenerateRoutingToken(kRoute, epoch)
	if err != nil {
		t.Fatalf("failed to generate routing token: %v", err)
	}

	expectedTokenHex := "4d434cacd96a31d95db3d8305e5ac679"
	actualTokenHex := hex.EncodeToString(routingToken[:])
	if actualTokenHex != expectedTokenHex {
		t.Fatalf("routing token mismatch:\n got:  %s\n want: %s", actualTokenHex, expectedTokenHex)
	}

	t.Logf("Deterministic cross-platform vector verified: %s", actualTokenHex)
}

func TestRouting_KeyDerivationAndSeparation(t *testing.T) {
	secretA, _ := hex.DecodeString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
	secretB, _ := hex.DecodeString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")

	kRouteAtoB, err := DeriveRoutingKey(secretA, "NX-ALICE", "NX-BOB")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Directional separation: A -> B != B -> A
	kRouteBtoA, err := DeriveRoutingKey(secretA, "NX-BOB", "NX-ALICE")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if bytes.Equal(kRouteAtoB, kRouteBtoA) {
		t.Fatalf("directional routing keys must not be equal")
	}

	// Secret separation: different pairwise secrets produce different keys
	kRouteDiffSecret, err := DeriveRoutingKey(secretB, "NX-ALICE", "NX-BOB")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if bytes.Equal(kRouteAtoB, kRouteDiffSecret) {
		t.Fatalf("keys with different secrets must not be equal")
	}

	// Peer separation: different recipient produces different key
	kRouteAtoC, err := DeriveRoutingKey(secretA, "NX-ALICE", "NX-CAROL")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if bytes.Equal(kRouteAtoB, kRouteAtoC) {
		t.Fatalf("keys with different recipients must not be equal")
	}
}

func TestRouting_EpochRotationAndWindowTolerance(t *testing.T) {
	secret, _ := hex.DecodeString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
	kRoute, err := DeriveRoutingKey(secret, "NX-ALICE", "NX-BOB")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	epochCurrent := int64(100)
	tokenCurrent, _ := GenerateRoutingToken(kRoute, epochCurrent)
	tokenPrev1, _ := GenerateRoutingToken(kRoute, epochCurrent-1)
	tokenPrev2, _ := GenerateRoutingToken(kRoute, epochCurrent-2)
	tokenNext, _ := GenerateRoutingToken(kRoute, epochCurrent+1)
	tokenExpired, _ := GenerateRoutingToken(kRoute, epochCurrent-3)
	tokenFutureSkew, _ := GenerateRoutingToken(kRoute, epochCurrent+2)

	// Distinct tokens across epochs
	if tokenCurrent == tokenPrev1 || tokenCurrent == tokenNext || tokenPrev1 == tokenNext {
		t.Fatalf("tokens across adjacent epochs must be unique")
	}

	// Within 4-epoch tolerance window: [current-2, current-1, current, current+1]
	// This ensures messages 24h, 36h, and 48h offline across midnight boundaries are successfully matched.
	if !IsTokenInWindow(tokenCurrent, kRoute, epochCurrent) {
		t.Fatalf("current epoch token must be in tolerance window")
	}
	if !IsTokenInWindow(tokenPrev1, kRoute, epochCurrent) {
		t.Fatalf("previous epoch token (epoch-1) must be in tolerance window")
	}
	if !IsTokenInWindow(tokenPrev2, kRoute, epochCurrent) {
		t.Fatalf("2-epoch-old token (epoch-2) must be in tolerance window to support 48h offline delivery")
	}
	if !IsTokenInWindow(tokenNext, kRoute, epochCurrent) {
		t.Fatalf("next epoch token (epoch+1) must be in tolerance window")
	}

	// Outside tolerance window: epoch-3 and epoch+2
	if IsTokenInWindow(tokenExpired, kRoute, epochCurrent) {
		t.Fatalf("expired epoch token (epoch-3) must NOT be in tolerance window")
	}
	if IsTokenInWindow(tokenFutureSkew, kRoute, epochCurrent) {
		t.Fatalf("future skewed token (epoch+2) must NOT be in tolerance window")
	}
}

func TestRouting_OfflineScenarios_Midnight_24h_36h_48h(t *testing.T) {
	secret, _ := hex.DecodeString("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
	kRoute, err := DeriveRoutingKey(secret, "NX-ALICE", "NX-BOB")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Base timestamp: Day 100 at 00:00:00 UTC = 8640000 sec
	tDay100Start := int64(100 * 86400)
	tDay100MidnightMinus1 := tDay100Start + 86399 // 23:59:59 (immediately before midnight)
	tDay101MidnightPlus1 := tDay100Start + 86401  // 00:00:01 (immediately after midnight, Day 101)

	// Scenario 1: Immediately before midnight
	tokenPreMidnight, _ := GenerateRoutingToken(kRoute, CurrentEpoch(tDay100MidnightMinus1))
	// 2 minutes later at Day 101 00:01:00
	tDelivery1 := tDay100MidnightMinus1 + 120
	if !IsTokenInWindow(tokenPreMidnight, kRoute, CurrentEpoch(tDelivery1)) {
		t.Fatalf("pre-midnight token must be recognized immediately after midnight")
	}

	// Scenario 2: Immediately after midnight
	tokenPostMidnight, _ := GenerateRoutingToken(kRoute, CurrentEpoch(tDay101MidnightPlus1))
	if !IsTokenInWindow(tokenPostMidnight, kRoute, CurrentEpoch(tDay101MidnightPlus1)) {
		t.Fatalf("post-midnight token must be recognized on day of creation")
	}

	// Scenario 3: 24 Hours Offline (created at 14:00, received 24h later at 14:00 next day)
	t24hSend := tDay100Start + 14*3600
	token24h, _ := GenerateRoutingToken(kRoute, CurrentEpoch(t24hSend))
	t24hRecv := t24hSend + 24*3600
	if !IsTokenInWindow(token24h, kRoute, CurrentEpoch(t24hRecv)) {
		t.Fatalf("24-hour delayed packet must be in tolerance window")
	}

	// Scenario 4: 36 Hours Offline (created Day 100 at 20:00, received Day 102 at 08:00 -> spans 2 midnight boundaries, epoch 100 -> 102)
	t36hSend := tDay100Start + 20*3600
	token36h, _ := GenerateRoutingToken(kRoute, CurrentEpoch(t36hSend))
	t36hRecv := t36hSend + 36*3600
	if !IsTokenInWindow(token36h, kRoute, CurrentEpoch(t36hRecv)) {
		t.Fatalf("36-hour delayed packet crossing 2 midnights must be in tolerance window")
	}

	// Scenario 5: 48 Hours Offline (created Day 100 at 12:00, received Day 102 at 12:00, epoch 100 -> 102)
	t48hSend := tDay100Start + 12*3600
	token48h, _ := GenerateRoutingToken(kRoute, CurrentEpoch(t48hSend))
	t48hRecv := t48hSend + 48*3600
	if !IsTokenInWindow(token48h, kRoute, CurrentEpoch(t48hRecv)) {
		t.Fatalf("48-hour delayed packet must be recognized within bounded window")
	}

	// Scenario 6: Envelope-driven timestamp verification with bounds checking
	ttl48h := int64(48 * 3600)
	expiresAt := t48hSend + ttl48h

	// Valid within TTL
	if !IsTokenInWindowForEnvelope(token48h, kRoute, t48hSend, expiresAt, t48hRecv) {
		t.Fatalf("IsTokenInWindowForEnvelope must accept unexpired 48h offline envelope")
	}

	// Expired envelope (received after expiresAt)
	if IsTokenInWindowForEnvelope(token48h, kRoute, t48hSend, expiresAt, expiresAt+10) {
		t.Fatalf("IsTokenInWindowForEnvelope must reject expired envelope")
	}

	// Future timestamp skew > 5 minutes
	futureCreatedAt := tDay100Start + 600
	tokenFuture, _ := GenerateRoutingToken(kRoute, CurrentEpoch(futureCreatedAt))
	if IsTokenInWindowForEnvelope(tokenFuture, kRoute, futureCreatedAt, futureCreatedAt+ttl48h, tDay100Start) {
		t.Fatalf("IsTokenInWindowForEnvelope must reject envelope with future skew > 5m")
	}

	// Envelope exceeding maximum 7-day lifetime (8 days old)
	t8DaysSend := tDay100Start
	t8DaysRecv := tDay100Start + 8*86400
	token8Days, _ := GenerateRoutingToken(kRoute, CurrentEpoch(t8DaysSend))
	if IsTokenInWindowForEnvelope(token8Days, kRoute, t8DaysSend, t8DaysSend+10*86400, t8DaysRecv) {
		t.Fatalf("IsTokenInWindowForEnvelope must reject envelope exceeding MaxTtlSeconds (7 days)")
	}
}

func TestRouting_InvalidInputs(t *testing.T) {
	_, err := DeriveRoutingKey([]byte("too-short"), "A", "B")
	if err == nil {
		t.Fatalf("expected error for invalid pairwise secret length")
	}

	secret32 := make([]byte, 32)
	_, err = DeriveRoutingKey(secret32, "", "B")
	if err == nil {
		t.Fatalf("expected error for empty sender")
	}
	_, err = DeriveRoutingKey(secret32, "A", "")
	if err == nil {
		t.Fatalf("expected error for empty recipient")
	}

	_, err = GenerateRoutingToken([]byte("short-key"), 100)
	if err == nil {
		t.Fatalf("expected error for invalid routing key length")
	}
}
