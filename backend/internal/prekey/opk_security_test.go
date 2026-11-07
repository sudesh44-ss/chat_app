package prekey

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func setupTestRepo(t *testing.T) (PrekeyRepository, string, string) {
	repo := NewMemoryPrekeyRepository()
	targetNxId := "NX-TARGET99"
	targetDeviceId := "dev_target_01"

	// 1. Upload Identity Key Binding
	signingPub := hex.EncodeToString(make([]byte, 32))
	dhPub := hex.EncodeToString(make([]byte, 32))
	_ = repo.UploadIdentityKeyBinding(context.Background(), &IdentityKeyBinding{
		AccountID:           "acc_target",
		NxID:                targetNxId,
		DeviceID:            targetDeviceId,
		SigningPublicKeyHex: signingPub,
		DhPublicKeyHex:      dhPub,
		SignatureHex:        hex.EncodeToString(make([]byte, 64)),
		Verified:            true,
		CreatedAt:           time.Now().UnixMilli(),
	})

	// 2. Upload Active Signed Prekey
	spkPub := hex.EncodeToString(make([]byte, 32))
	_ = repo.UploadSignedPrekey(context.Background(), &SignedPrekey{
		AccountID:    "acc_target",
		NxID:         targetNxId,
		DeviceID:     targetDeviceId,
		KeyID:        1,
		PublicKeyHex: spkPub,
		SignatureHex: hex.EncodeToString(make([]byte, 64)),
		IsActive:     true,
		CreatedAt:    time.Now().UnixMilli(),
		ExpiresAt:    time.Now().UnixMilli() + 86400000,
	})

	return repo, targetNxId, targetDeviceId
}

func generateOpks(nxId, deviceId string, count int) []*OneTimePrekey {
	var opks []*OneTimePrekey
	for i := 1; i <= count; i++ {
		pub := make([]byte, 32)
		_, _ = rand.Read(pub)
		opks = append(opks, &OneTimePrekey{
			AccountID:    "acc_target",
			NxID:         nxId,
			DeviceID:     deviceId,
			KeyID:        i,
			PublicKeyHex: hex.EncodeToString(pub),
			Status:       "AVAILABLE",
			CreatedAt:    time.Now().UnixMilli(),
		})
	}
	return opks
}

// TestMandatoryOpkSecurityRules tests all mandatory security rules specified by the user.
func TestMandatoryOpkSecurityRules(t *testing.T) {
	ctx := context.Background()
	repo, targetNxId, targetDeviceId := setupTestRepo(t)

	// Upload 3 OPKs
	opks := generateOpks(targetNxId, targetDeviceId, 3)
	if err := repo.UploadOneTimePrekeys(ctx, opks); err != nil {
		t.Fatalf("failed to upload OPKs: %v", err)
	}

	availCount, _ := repo.GetAvailableOpkCount(ctx, targetNxId, targetDeviceId)
	if availCount != 3 {
		t.Fatalf("expected 3 available OPKs, got %d", availCount)
	}

	aliceNxId := "NX-ALICE111"
	aliceDeviceId := "dev_alice_01"
	claimId1 := "claim_uuid_001"

	// 1. Initial Claim: Must allocate and disclose OPK 1
	resp1, err := repo.ClaimPrekey(ctx, aliceNxId, aliceDeviceId, &ClaimRequest{
		RecipientNxID: targetNxId,
		ClaimID:       claimId1,
	})
	if err != nil {
		t.Fatalf("Claim 1 failed: %v", err)
	}
	if resp1.OneTimePrekeyHex == nil || *resp1.OneTimePrekeyID != 1 {
		t.Fatalf("expected OPK 1 to be disclosed, got %v", resp1.OneTimePrekeyID)
	}

	// Rule 1 Verification: Available count must immediately decrease!
	availCount, _ = repo.GetAvailableOpkCount(ctx, targetNxId, targetDeviceId)
	if availCount != 2 {
		t.Errorf("expected 2 remaining available OPKs after claim 1, got %d", availCount)
	}

	// Rule 2: Idempotent Retry with same caller + same claim_id must return identical OPK
	resp1Retry, err := repo.ClaimPrekey(ctx, aliceNxId, aliceDeviceId, &ClaimRequest{
		RecipientNxID: targetNxId,
		ClaimID:       claimId1,
	})
	if err != nil {
		t.Fatalf("Claim 1 retry failed: %v", err)
	}
	if *resp1Retry.OneTimePrekeyID != 1 || *resp1Retry.OneTimePrekeyHex != *resp1.OneTimePrekeyHex {
		t.Errorf("idempotent retry did not return identical OPK")
	}

	// Rule 3: A new claim must receive a DIFFERENT OPK (never reassign disclosed OPK)
	claimId2 := "claim_uuid_002"
	resp2, err := repo.ClaimPrekey(ctx, aliceNxId, aliceDeviceId, &ClaimRequest{
		RecipientNxID: targetNxId,
		ClaimID:       claimId2,
	})
	if err != nil {
		t.Fatalf("Claim 2 failed: %v", err)
	}
	if *resp2.OneTimePrekeyID != 2 {
		t.Errorf("expected fresh claim to get OPK 2, got %d", *resp2.OneTimePrekeyID)
	}
	if *resp2.OneTimePrekeyHex == *resp1.OneTimePrekeyHex {
		t.Errorf("CRITICAL SECURITY VIOLATION: Claim 2 received the same OPK as Claim 1!")
	}

	// Rule 4: Claim ownership violation: Bob cannot use Alice's claim_id
	bobNxId := "NX-BOB22222"
	bobDeviceId := "dev_bob_01"
	_, err = repo.ClaimPrekey(ctx, bobNxId, bobDeviceId, &ClaimRequest{
		RecipientNxID: targetNxId,
		ClaimID:       claimId1, // Trying to steal Alice's claim_id
	})
	if !errors.Is(err, ErrClaimOwnershipViolation) {
		t.Errorf("expected ErrClaimOwnershipViolation, got %v", err)
	}

	// Rule 5: Final Consumption
	err = repo.ConsumeClaim(ctx, claimId1, aliceNxId)
	if err != nil {
		t.Fatalf("ConsumeClaim failed: %v", err)
	}

	// After consumption, re-claiming that claim_id fails with ErrClaimAlreadyConsumed
	_, err = repo.ClaimPrekey(ctx, aliceNxId, aliceDeviceId, &ClaimRequest{
		RecipientNxID: targetNxId,
		ClaimID:       claimId1,
	})
	if !errors.Is(err, ErrClaimAlreadyConsumed) {
		t.Errorf("expected ErrClaimAlreadyConsumed, got %v", err)
	}

	// Rule 6: Pool exhaustion fallback
	// Allocate the 3rd and final OPK
	claimId3 := "claim_uuid_003"
	resp3, err := repo.ClaimPrekey(ctx, aliceNxId, aliceDeviceId, &ClaimRequest{
		RecipientNxID: targetNxId,
		ClaimID:       claimId3,
	})
	if err != nil || resp3.OneTimePrekeyHex == nil || *resp3.OneTimePrekeyID != 3 {
		t.Fatalf("Claim 3 failed: %v", err)
	}

	// All 3 OPKs are now claimed. 4th claim must succeed with OneTimePrekey = nil (Graceful X3DH Fallback)
	claimId4 := "claim_uuid_004"
	resp4, err := repo.ClaimPrekey(ctx, aliceNxId, aliceDeviceId, &ClaimRequest{
		RecipientNxID: targetNxId,
		ClaimID:       claimId4,
	})
	if err != nil {
		t.Fatalf("Claim 4 failed during pool exhaustion: %v", err)
	}
	if resp4.OneTimePrekeyHex != nil {
		t.Errorf("expected OneTimePrekeyHex to be nil when pool is exhausted, got %v", *resp4.OneTimePrekeyHex)
	}
}

// TestConcurrentOpkClaimsGuarantee verifies that under high concurrency,
// NO TWO CLAIMS EVER RECEIVE THE SAME OPK.
func TestConcurrentOpkClaimsGuarantee(t *testing.T) {
	ctx := context.Background()
	repo, targetNxId, targetDeviceId := setupTestRepo(t)

	numOpks := 20
	opks := generateOpks(targetNxId, targetDeviceId, numOpks)
	if err := repo.UploadOneTimePrekeys(ctx, opks); err != nil {
		t.Fatalf("failed to upload OPKs: %v", err)
	}

	numConcurrentCallers := 50
	var wg sync.WaitGroup
	wg.Add(numConcurrentCallers)

	claimedKeys := sync.Map{}
	var successfulOpkClaims int64
	var exhaustedClaims int64

	for i := 0; i < numConcurrentCallers; i++ {
		callerIdx := i
		go func() {
			defer wg.Done()
			claimId := fmt.Sprintf("concurrent_claim_%d", callerIdx)
			callerNxId := fmt.Sprintf("NX-CALLER%03d", callerIdx)
			callerDevId := "dev_01"

			resp, err := repo.ClaimPrekey(ctx, callerNxId, callerDevId, &ClaimRequest{
				RecipientNxID: targetNxId,
				ClaimID:       claimId,
			})
			if err != nil {
				t.Errorf("Claim failed for caller %d: %v", callerIdx, err)
				return
			}

			if resp.OneTimePrekeyHex != nil {
				keyHex := *resp.OneTimePrekeyHex
				// Check for duplicate disclosure
				if _, loaded := claimedKeys.LoadOrStore(keyHex, claimId); loaded {
					t.Errorf("FATAL CONCURRENCY BREACH: OPK %s was disclosed to multiple claims!", keyHex)
				}
				atomic.AddInt64(&successfulOpkClaims, 1)
			} else {
				atomic.AddInt64(&exhaustedClaims, 1)
			}
		}()
	}

	wg.Wait()

	if successfulOpkClaims != int64(numOpks) {
		t.Errorf("expected exactly %d successful OPK claims, got %d", numOpks, successfulOpkClaims)
	}
	if exhaustedClaims != int64(numConcurrentCallers-numOpks) {
		t.Errorf("expected %d exhausted claims, got %d", numConcurrentCallers-numOpks, exhaustedClaims)
	}

	t.Logf("Concurrency test passed: %d OPKs claimed with ZERO collisions across %d concurrent routines (%d exhausted fallbacks).",
		successfulOpkClaims, numConcurrentCallers, exhaustedClaims)
}
