package connections

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nexa/wire/internal/identity"
)

type mockHub struct {
	dispatched []map[string]interface{}
}

func (m *mockHub) BroadcastEventToNxID(nxId string, eventType string, payload interface{}) {
	m.dispatched = append(m.dispatched, map[string]interface{}{
		"nx_id":   nxId,
		"type":    eventType,
		"payload": payload,
	})
}

func setupTestEnvironment(t *testing.T) (*identity.Store, *MemoryRepository, *Handler, *mockHub) {
	tempFile := t.TempDir() + "/identities.json"
	identStore, err := identity.NewStore(tempFile)
	if err != nil {
		t.Fatalf("failed to create test identity store: %v", err)
	}

	repo := NewMemoryRepository(identStore)
	hub := &mockHub{}
	handler := NewHandler(repo, identStore, hub)

	return identStore, repo, handler, hub
}

func createTestUser(t *testing.T, store *identity.Store, nxID, name string) (*identity.UserAccount, string) {
	now := time.Now().UnixMilli()
	acc := &identity.UserAccount{
		ID:           "usr_" + nxID,
		NxID:         nxID,
		ProfileName:  name,
		PublicKeyHex: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"[:64-len(nxID)] + nxID,
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	if err := store.CreateAccount(acc); err != nil {
		t.Fatalf("failed to create test account %s: %v", nxID, err)
	}

	dev := &identity.Device{
		DeviceID:     "dev_" + nxID,
		AccountID:    acc.ID,
		NxID:         nxID,
		PublicKeyHex: acc.PublicKeyHex,
		CreatedAt:    now,
		LastActiveAt: now,
	}
	if err := store.RegisterDevice(dev); err != nil {
		t.Fatalf("failed to register device for %s: %v", nxID, err)
	}

	token := "tok_" + nxID
	sess := &identity.Session{
		Token:     token,
		AccountID: acc.ID,
		NxID:      nxID,
		DeviceID:  dev.DeviceID,
		ExpiresAt: now + 3600*1000,
		CreatedAt: now,
	}
	if err := store.CreateSession(sess); err != nil {
		t.Fatalf("failed to create session for %s: %v", nxID, err)
	}

	return acc, token
}

func TestUnauthenticatedDiscovery(t *testing.T) {
	_, _, handler, _ := setupTestEnvironment(t)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/discover?nx_id=NX-29AB731Q", nil)
	w := httptest.NewRecorder()
	handler.handleDiscover(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 Unauthorized, got %d", w.Code)
	}
}

func TestDiscoveryInvalidNxID(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, token := createTestUser(t, store, "NX-29AB731Q", "Alice")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/discover?nx_id=INVALID-FORMAT", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	handler.handleDiscover(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request for invalid NX-ID, got %d", w.Code)
	}
}

func TestDiscoveryNonexistentAccount(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, token := createTestUser(t, store, "NX-29AB731Q", "Alice")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/discover?nx_id=NX-99999999", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	handler.handleDiscover(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404 Not Found for nonexistent account, got %d", w.Code)
	}
}

func TestSelfConnectionRejected(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")

	body, _ := json.Marshal(map[string]string{
		"recipient_nx_id": "NX-29AB731Q",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tokenAlice)
	w := httptest.NewRecorder()
	handler.handleSendRequest(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request for self connection, got %d", w.Code)
	}
}

func TestConnectionRequestLifecycle(t *testing.T) {
	store, _, handler, hub := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, tokenBob := createTestUser(t, store, "NX-A7K92B4M", "Bob")

	// 1. Alice sends request to Bob
	body, _ := json.Marshal(map[string]string{
		"recipient_nx_id": "NX-A7K92B4M",
		"intro_message":  "Hey Bob, let's connect!",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tokenAlice)
	w := httptest.NewRecorder()
	handler.handleSendRequest(w, req)

	if w.Code != http.StatusCreated {
		t.Fatalf("expected 201 Created for send request, got %d (body: %s)", w.Code, w.Body.String())
	}

	var sentReq ConnectionRequest
	_ = json.Unmarshal(w.Body.Bytes(), &sentReq)
	if sentReq.Status != RequestStatusPending {
		t.Fatalf("expected status PENDING, got %s", sentReq.Status)
	}

	// Verify WebSocket notification dispatched to Bob
	if len(hub.dispatched) == 0 || hub.dispatched[0]["nx_id"] != "NX-A7K92B4M" {
		t.Errorf("expected real-time notification to Bob, got %+v", hub.dispatched)
	}

	// 2. Duplicate pending request from Alice should be rejected with 409 Conflict
	wDup := httptest.NewRecorder()
	reqDup := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader(body))
	reqDup.Header.Set("Authorization", "Bearer "+tokenAlice)
	handler.handleSendRequest(wDup, reqDup)
	if wDup.Code != http.StatusConflict {
		t.Fatalf("expected 409 Conflict for duplicate pending request, got %d", wDup.Code)
	}

	// 3. Bob queries incoming requests
	reqInc := httptest.NewRequest(http.MethodGet, "/api/v1/connections/requests/incoming", nil)
	reqInc.Header.Set("Authorization", "Bearer "+tokenBob)
	wInc := httptest.NewRecorder()
	handler.handleIncomingRequests(wInc, reqInc)
	if wInc.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for incoming requests, got %d", wInc.Code)
	}

	var incResp struct {
		Requests []*ConnectionRequestWithProfile `json:"requests"`
		Total    int                             `json:"total"`
	}
	_ = json.Unmarshal(wInc.Body.Bytes(), &incResp)
	if incResp.Total != 1 || incResp.Requests[0].SenderNxID != "NX-29AB731Q" {
		t.Fatalf("expected 1 incoming request from Alice, got %+v", incResp)
	}

	// 4. Unauthorized acceptance: Alice cannot accept her own request
	acceptBody, _ := json.Marshal(map[string]string{"request_id": sentReq.ID})
	reqUnauthAccept := httptest.NewRequest(http.MethodPost, "/api/v1/connections/requests/accept", bytes.NewReader(acceptBody))
	reqUnauthAccept.Header.Set("Authorization", "Bearer "+tokenAlice)
	wUnauthAccept := httptest.NewRecorder()
	handler.handleAcceptRequest(wUnauthAccept, reqUnauthAccept)
	if wUnauthAccept.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden for unauthorized accept, got %d", wUnauthAccept.Code)
	}

	// 5. Bob accepts the request
	reqBobAccept := httptest.NewRequest(http.MethodPost, "/api/v1/connections/requests/accept", bytes.NewReader(acceptBody))
	reqBobAccept.Header.Set("Authorization", "Bearer "+tokenBob)
	wBobAccept := httptest.NewRecorder()
	handler.handleAcceptRequest(wBobAccept, reqBobAccept)
	if wBobAccept.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for Bob accepting request, got %d", wBobAccept.Code)
	}

	var conn Connection
	_ = json.Unmarshal(wBobAccept.Body.Bytes(), &conn)
	if conn.Status != ConnectionStatusActive {
		t.Fatalf("expected connection status ACTIVE, got %s", conn.Status)
	}

	// Verify Canonical order: "NX-29AB731Q" < "NX-A7K92B4M"
	if conn.UserANxID != "NX-29AB731Q" || conn.UserBNxID != "NX-A7K92B4M" {
		t.Fatalf("expected canonical pair ordering, got %s, %s", conn.UserANxID, conn.UserBNxID)
	}

	// 6. Verify Contacts list for Alice and Bob
	reqAliceContacts := httptest.NewRequest(http.MethodGet, "/api/v1/connections", nil)
	reqAliceContacts.Header.Set("Authorization", "Bearer "+tokenAlice)
	wAliceContacts := httptest.NewRecorder()
	handler.handleConnections(wAliceContacts, reqAliceContacts)

	var contactsResp struct {
		Contacts []*PublicProfile `json:"contacts"`
		Total    int              `json:"total"`
	}
	_ = json.Unmarshal(wAliceContacts.Body.Bytes(), &contactsResp)
	if contactsResp.Total != 1 || contactsResp.Contacts[0].NxID != "NX-A7K92B4M" {
		t.Fatalf("expected Bob in Alice's contacts, got %+v", contactsResp)
	}

	// 7. Remove Connection
	remBody, _ := json.Marshal(map[string]string{"target_nx_id": "NX-A7K92B4M"})
	reqRem := httptest.NewRequest(http.MethodPost, "/api/v1/connections/remove", bytes.NewReader(remBody))
	reqRem.Header.Set("Authorization", "Bearer "+tokenAlice)
	wRem := httptest.NewRecorder()
	handler.handleRemoveConnection(wRem, reqRem)
	if wRem.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for remove connection, got %d", wRem.Code)
	}

	// Contacts list should now be empty
	wAliceAfterRem := httptest.NewRecorder()
	reqAliceContactsAfterRem := httptest.NewRequest(http.MethodGet, "/api/v1/connections", nil)
	reqAliceContactsAfterRem.Header.Set("Authorization", "Bearer "+tokenAlice)
	handler.handleConnections(wAliceAfterRem, reqAliceContactsAfterRem)
	var contactsAfterRem struct {
		Contacts []*PublicProfile `json:"contacts"`
		Total    int              `json:"total"`
	}
	_ = json.Unmarshal(wAliceAfterRem.Body.Bytes(), &contactsAfterRem)
	if contactsAfterRem.Total != 0 {
		t.Fatalf("expected 0 contacts after remove, got %d", contactsAfterRem.Total)
	}
}

func TestSimultaneousReciprocalRequests(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, tokenBob := createTestUser(t, store, "NX-A7K92B4M", "Bob")

	// 1. Alice sends request to Bob
	bodyA, _ := json.Marshal(map[string]string{"recipient_nx_id": "NX-A7K92B4M"})
	reqA := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader(bodyA))
	reqA.Header.Set("Authorization", "Bearer "+tokenAlice)
	wA := httptest.NewRecorder()
	handler.handleSendRequest(wA, reqA)
	if wA.Code != http.StatusCreated {
		t.Fatalf("expected 201 for Alice request, got %d", wA.Code)
	}

	// 2. Bob simultaneously sends request to Alice (Reciprocal)
	bodyB, _ := json.Marshal(map[string]string{"recipient_nx_id": "NX-29AB731Q"})
	reqB := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader(bodyB))
	reqB.Header.Set("Authorization", "Bearer "+tokenBob)
	wB := httptest.NewRecorder()
	handler.handleSendRequest(wB, reqB)
	if wB.Code != http.StatusCreated {
		t.Fatalf("expected 201 for reciprocal request, got %d", wB.Code)
	}

	var recResp ConnectionRequest
	_ = json.Unmarshal(wB.Body.Bytes(), &recResp)
	if recResp.Status != RequestStatusAccepted {
		t.Fatalf("expected reciprocal request to be auto-accepted, got %s", recResp.Status)
	}

	// Verify both now show as connected in /api/v1/connections
	reqCheck := httptest.NewRequest(http.MethodGet, "/api/v1/connections", nil)
	reqCheck.Header.Set("Authorization", "Bearer "+tokenAlice)
	wCheck := httptest.NewRecorder()
	handler.handleConnections(wCheck, reqCheck)
	var aliceConn struct {
		Contacts []*PublicProfile `json:"contacts"`
		Total    int              `json:"total"`
	}
	_ = json.Unmarshal(wCheck.Body.Bytes(), &aliceConn)
	if aliceConn.Total != 1 || aliceConn.Contacts[0].NxID != "NX-A7K92B4M" {
		t.Fatalf("expected bilateral connection established, got %+v", aliceConn)
	}
}

func TestBlockUserAndAntiEnumeration(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, tokenBob := createTestUser(t, store, "NX-A7K92B4M", "Bob")

	// 1. Alice blocks Bob
	blockBody, _ := json.Marshal(map[string]string{"target_nx_id": "NX-A7K92B4M"})
	reqBlock := httptest.NewRequest(http.MethodPost, "/api/v1/users/block", bytes.NewReader(blockBody))
	reqBlock.Header.Set("Authorization", "Bearer "+tokenAlice)
	wBlock := httptest.NewRecorder()
	handler.handleBlockUser(wBlock, reqBlock)
	if wBlock.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for block, got %d", wBlock.Code)
	}

	// 2. Anti-enumeration: Bob searches for Alice -> returns 404 (pretends user doesn't exist)
	reqBobSearch := httptest.NewRequest(http.MethodGet, "/api/v1/users/discover?nx_id=NX-29AB731Q", nil)
	reqBobSearch.Header.Set("Authorization", "Bearer "+tokenBob)
	wBobSearch := httptest.NewRecorder()
	handler.handleDiscover(wBobSearch, reqBobSearch)
	if wBobSearch.Code != http.StatusNotFound {
		t.Fatalf("expected 404 Not Found for blocked discovery search, got %d", wBobSearch.Code)
	}

	// 3. Bob attempts to send request to Alice -> returns 403 Forbidden
	reqBobSend := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader([]byte(`{"recipient_nx_id":"NX-29AB731Q"}`)))
	reqBobSend.Header.Set("Authorization", "Bearer "+tokenBob)
	wBobSend := httptest.NewRecorder()
	handler.handleSendRequest(wBobSend, reqBobSend)
	if wBobSend.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden for request to blocker, got %d", wBobSend.Code)
	}

	// 4. Alice unblocks Bob
	reqUnblock := httptest.NewRequest(http.MethodPost, "/api/v1/users/unblock", bytes.NewReader(blockBody))
	reqUnblock.Header.Set("Authorization", "Bearer "+tokenAlice)
	wUnblock := httptest.NewRecorder()
	handler.handleUnblockUser(wUnblock, reqUnblock)
	if wUnblock.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for unblock, got %d", wUnblock.Code)
	}

	// 5. Discovery now succeeds after unblock
	wBobSearchAfter := httptest.NewRecorder()
	reqBobSearchAfter := httptest.NewRequest(http.MethodGet, "/api/v1/users/discover?nx_id=NX-29AB731Q", nil)
	reqBobSearchAfter.Header.Set("Authorization", "Bearer "+tokenBob)
	handler.handleDiscover(wBobSearchAfter, reqBobSearchAfter)
	if wBobSearchAfter.Code != http.StatusOK {
		t.Fatalf("expected 200 OK after unblock, got %d", wBobSearchAfter.Code)
	}
}

func TestCancelRequestBySender(t *testing.T) {
	store, repo, handler, _ := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, tokenBob := createTestUser(t, store, "NX-A7K92B4M", "Bob")

	req, err := repo.SendRequest(context.Background(), "NX-29AB731Q", "NX-A7K92B4M", "Hi", DefaultRequestTTL)
	if err != nil {
		t.Fatalf("failed to send request: %v", err)
	}

	// Bob cannot cancel Alice's request (Unauthorized)
	cancelBody, _ := json.Marshal(map[string]string{"request_id": req.ID})
	reqBobCancel := httptest.NewRequest(http.MethodPost, "/api/v1/connections/requests/cancel", bytes.NewReader(cancelBody))
	reqBobCancel.Header.Set("Authorization", "Bearer "+tokenBob)
	wBobCancel := httptest.NewRecorder()
	handler.handleCancelRequest(wBobCancel, reqBobCancel)
	if wBobCancel.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden when recipient tries to cancel, got %d", wBobCancel.Code)
	}

	// Alice cancels her request
	reqAliceCancel := httptest.NewRequest(http.MethodPost, "/api/v1/connections/requests/cancel", bytes.NewReader(cancelBody))
	reqAliceCancel.Header.Set("Authorization", "Bearer "+tokenAlice)
	wAliceCancel := httptest.NewRecorder()
	handler.handleCancelRequest(wAliceCancel, reqAliceCancel)
	if wAliceCancel.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for sender cancellation, got %d", wAliceCancel.Code)
	}
}

func TestCanonicalPairOrdering(t *testing.T) {
	uA, uB := CanonicalPair("NX-ZZZZ9999", "NX-AAAA1111")
	if uA != "NX-AAAA1111" || uB != "NX-ZZZZ9999" {
		t.Fatalf("expected canonical pair NX-AAAA1111, NX-ZZZZ9999, got %s, %s", uA, uB)
	}

	uA2, uB2 := CanonicalPair("NX-AAAA1111", "NX-ZZZZ9999")
	if uA2 != "NX-AAAA1111" || uB2 != "NX-ZZZZ9999" {
		t.Fatalf("expected canonical pair NX-AAAA1111, NX-ZZZZ9999, got %s, %s", uA2, uB2)
	}
}

func TestQrConnectionSync(t *testing.T) {
	store, repo, handler, hub := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, _ = createTestUser(t, store, "NX-A7K92B4M", "Bob")

	body, _ := json.Marshal(map[string]string{
		"scanner_nx_id": "NX-29AB731Q",
		"scanned_nx_id": "NX-A7K92B4M",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/contacts/sync-qr", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tokenAlice)
	w := httptest.NewRecorder()
	handler.handleSyncQr(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d: %s", w.Code, w.Body.String())
	}

	var res struct {
		Status     string      `json:"status"`
		Connection *Connection `json:"connection"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if res.Status != "SUCCESS" || res.Connection == nil {
		t.Fatalf("expected SUCCESS and valid connection, got %+v", res)
	}
	if res.Connection.Source != ConnectionSourceQrScan {
		t.Fatalf("expected source %s, got %s", ConnectionSourceQrScan, res.Connection.Source)
	}

	// Verify bilateral connection is active
	connected, err := repo.IsConnected(context.Background(), "NX-29AB731Q", "NX-A7K92B4M")
	if err != nil || !connected {
		t.Fatalf("expected users to be connected, err: %v", err)
	}

	// Verify broadcast event sent
	if len(hub.dispatched) < 2 {
		t.Fatalf("expected 2 broadcast events for QR connect, got %d", len(hub.dispatched))
	}
}

func TestQrConnectionSelfScan(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")

	body, _ := json.Marshal(map[string]string{
		"scanner_nx_id": "NX-29AB731Q",
		"scanned_nx_id": "NX-29AB731Q",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/contacts/sync-qr", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tokenAlice)
	w := httptest.NewRecorder()
	handler.handleSyncQr(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request for self scan, got %d", w.Code)
	}
}

func TestQrConnectionBlockedUser(t *testing.T) {
	store, repo, handler, _ := setupTestEnvironment(t)
	_, _ = createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, tokenBob := createTestUser(t, store, "NX-A7K92B4M", "Bob")

	// Alice blocks Bob
	_ = repo.BlockUser(context.Background(), "NX-29AB731Q", "NX-A7K92B4M")

	body, _ := json.Marshal(map[string]string{
		"scanner_nx_id": "NX-A7K92B4M",
		"scanned_nx_id": "NX-29AB731Q",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/contacts/sync-qr", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+tokenBob)
	w := httptest.NewRecorder()
	handler.handleSyncQr(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden when connecting with blocker, got %d", w.Code)
	}
}

func TestPhoneVisibilityGrants(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, tokenBob := createTestUser(t, store, "NX-A7K92B4M", "Bob")

	// Alice grants Bob phone visibility
	grantBody, _ := json.Marshal(map[string]interface{}{
		"grantee_nx_id": "NX-A7K92B4M",
		"is_active":     true,
	})
	reqGrant := httptest.NewRequest(http.MethodPost, "/api/v1/users/phone-visibility", bytes.NewReader(grantBody))
	reqGrant.Header.Set("Authorization", "Bearer "+tokenAlice)
	wGrant := httptest.NewRecorder()
	handler.handlePhoneVisibility(wGrant, reqGrant)

	if wGrant.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for grant, got %d: %s", wGrant.Code, wGrant.Body.String())
	}

	// Verify Alice granted Bob
	reqCheck := httptest.NewRequest(http.MethodGet, "/api/v1/users/phone-visibility?target_nx_id=NX-A7K92B4M", nil)
	reqCheck.Header.Set("Authorization", "Bearer "+tokenAlice)
	wCheck := httptest.NewRecorder()
	handler.handlePhoneVisibility(wCheck, reqCheck)

	var checkRes struct {
		IsActive bool `json:"is_active"`
	}
	_ = json.Unmarshal(wCheck.Body.Bytes(), &checkRes)
	if !checkRes.IsActive {
		t.Fatalf("expected phone visibility is_active = true")
	}

	// Verify Bob has not granted Alice (directional grant)
	reqCheckBob := httptest.NewRequest(http.MethodGet, "/api/v1/users/phone-visibility?target_nx_id=NX-29AB731Q", nil)
	reqCheckBob.Header.Set("Authorization", "Bearer "+tokenBob)
	wCheckBob := httptest.NewRecorder()
	handler.handlePhoneVisibility(wCheckBob, reqCheckBob)

	var checkResBob struct {
		IsActive bool `json:"is_active"`
	}
	_ = json.Unmarshal(wCheckBob.Body.Bytes(), &checkResBob)
	if checkResBob.IsActive {
		t.Fatalf("expected Bob's phone visibility grant to Alice to be false")
	}

	// Revoke grant
	revokeBody, _ := json.Marshal(map[string]interface{}{
		"grantee_nx_id": "NX-A7K92B4M",
		"is_active":     false,
	})
	reqRevoke := httptest.NewRequest(http.MethodPost, "/api/v1/users/phone-visibility", bytes.NewReader(revokeBody))
	reqRevoke.Header.Set("Authorization", "Bearer "+tokenAlice)
	wRevoke := httptest.NewRecorder()
	handler.handlePhoneVisibility(wRevoke, reqRevoke)

	if wRevoke.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for revoke, got %d", wRevoke.Code)
	}

	// Recheck grant is inactive
	wCheckAfter := httptest.NewRecorder()
	handler.handlePhoneVisibility(wCheckAfter, reqCheck)
	_ = json.Unmarshal(wCheckAfter.Body.Bytes(), &checkRes)
	if checkRes.IsActive {
		t.Fatalf("expected phone visibility to be false after revocation")
	}
}

func TestMutualConnections(t *testing.T) {
	store, repo, handler, _ := setupTestEnvironment(t)
	_, tokenAlice := createTestUser(t, store, "NX-29AB731Q", "Alice")
	_, _ = createTestUser(t, store, "NX-A7K92B4M", "Bob")
	_, _ = createTestUser(t, store, "NX-C9K12B4M", "Charlie")
	_, _ = createTestUser(t, store, "NX-D8M33L2P", "Dave")

	// Alice connects with Charlie
	_, _ = repo.SyncQrHandshake(context.Background(), "NX-29AB731Q", "NX-C9K12B4M")
	// Bob connects with Charlie
	_, _ = repo.SyncQrHandshake(context.Background(), "NX-A7K92B4M", "NX-C9K12B4M")

	// Alice only connects with Dave
	_, _ = repo.SyncQrHandshake(context.Background(), "NX-29AB731Q", "NX-D8M33L2P")

	// Alice queries mutual with Bob
	reqMutual := httptest.NewRequest(http.MethodGet, "/api/v1/connections/mutual?target_nx_id=NX-A7K92B4M", nil)
	reqMutual.Header.Set("Authorization", "Bearer "+tokenAlice)
	wMutual := httptest.NewRecorder()
	handler.handleMutualConnections(wMutual, reqMutual)

	if wMutual.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d: %s", wMutual.Code, wMutual.Body.String())
	}

	var res struct {
		Total              int              `json:"total"`
		MutualConnections []*PublicProfile `json:"mutual_connections"`
	}
	if err := json.Unmarshal(wMutual.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to decode mutual connections: %v", err)
	}

	if res.Total != 1 || len(res.MutualConnections) != 1 {
		t.Fatalf("expected 1 mutual connection (Charlie), got %d (%+v)", res.Total, res.MutualConnections)
	}
	if res.MutualConnections[0].NxID != "NX-C9K12B4M" {
		t.Fatalf("expected Charlie NX-C9K12B4M, got %s", res.MutualConnections[0].NxID)
	}
}

func TestPhoneDiscoveryPrivacyGate(t *testing.T) {
	store, _, _, _ := setupTestEnvironment(t)
	now := time.Now().UnixMilli()

	// Create user with phone verified but discoverability FALSE
	eve := &identity.UserAccount{
		ID:                    "usr_eve",
		NxID:                  "NX-EVE12345",
		ProfileName:           "Eve",
		PhoneNumber:           "+15550001111",
		PhoneVerified:         true,
		IsDiscoverableByPhone: false, // Strict privacy setting
		PublicKeyHex:          "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
		CreatedAt:             now,
		UpdatedAt:             now,
	}
	_ = store.CreateAccount(eve)

	// Lookup phone number
	results := store.LookupVerifiedPhoneNumbers([]string{"+15550001111"})
	if len(results) != 0 {
		t.Fatalf("privacy violation: user with IsDiscoverableByPhone=false was discovered: %+v", results)
	}

	// Update discoverability to TRUE
	eve.IsDiscoverableByPhone = true
	_ = store.UpdateAccount(eve)

	results2 := store.LookupVerifiedPhoneNumbers([]string{"+15550001111"})
	if len(results2) != 1 {
		t.Fatalf("expected user to be discovered after enabling discoverability, got %d", len(results2))
	}
	if results2[0].NxID != "NX-EVE12345" {
		t.Fatalf("expected NX-EVE12345, got %s", results2[0].NxID)
	}
}

func TestHandleSendRequest_IntroMessageLengthLimit(t *testing.T) {
	store, _, handler, _ := setupTestEnvironment(t)
	_, aliceToken := createTestUser(t, store, "NX-ALICE111", "Alice")
	createTestUser(t, store, "NX-BOB22222", "Bob")

	// 1. Intro message > 256 characters should be rejected with 400 Bad Request
	longIntro := string(bytes.Repeat([]byte("A"), 257))
	reqBody := map[string]string{
		"recipient_nx_id": "NX-BOB22222",
		"intro_message":  longIntro,
	}
	bodyBytes, _ := json.Marshal(reqBody)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", "Bearer "+aliceToken)
	rec := httptest.NewRecorder()

	handler.handleSendRequest(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request for intro message > 256 chars, got %d", rec.Code)
	}

	// 2. Intro message within limit (<= 256 characters) should succeed (200 OK)
	validIntro := string(bytes.Repeat([]byte("B"), 256))
	reqBodyValid := map[string]string{
		"recipient_nx_id": "NX-BOB22222",
		"intro_message":  validIntro,
	}
	validBytes, _ := json.Marshal(reqBodyValid)
	reqValid := httptest.NewRequest(http.MethodPost, "/api/v1/connections/request", bytes.NewReader(validBytes))
	reqValid.Header.Set("Authorization", "Bearer "+aliceToken)
	recValid := httptest.NewRecorder()

	handler.handleSendRequest(recValid, reqValid)

	if recValid.Code != http.StatusCreated && recValid.Code != http.StatusOK {
		t.Fatalf("expected 201 Created or 200 OK for intro message <= 256 chars, got %d", recValid.Code)
	}
}
