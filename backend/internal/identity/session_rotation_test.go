package identity

import (
	"testing"
	"time"
)

func seedSession(t *testing.T, store *Store, token, nxID, deviceID string) {
	t.Helper()
	now := time.Now().UnixMilli()
	if err := store.CreateSession(&Session{
		Token:     token,
		AccountID: "acc_" + nxID,
		NxID:      nxID,
		DeviceID:  deviceID,
		ExpiresAt: now + time.Hour.Milliseconds(),
		CreatedAt: now,
	}); err != nil {
		t.Fatalf("CreateSession(%s): %v", token, err)
	}
}

func TestStore_RevokeSessionsForDevice(t *testing.T) {
	store := newTestStore(t)

	seedSession(t, store, "a1", "NX-A", "dev_1")
	seedSession(t, store, "a2", "NX-A", "dev_1")
	seedSession(t, store, "a3", "NX-A", "dev_2")
	seedSession(t, store, "b1", "NX-B", "dev_1")

	revoked, err := store.RevokeSessionsForDevice("NX-A", "dev_1", "", 0)
	if err != nil {
		t.Fatalf("RevokeSessionsForDevice: %v", err)
	}
	if revoked != 2 {
		t.Fatalf("expected 2 revoked sessions, got %d", revoked)
	}

	if _, ok := store.GetSession("a1"); ok {
		t.Fatal("a1 should be revoked")
	}
	if _, ok := store.GetSession("a2"); ok {
		t.Fatal("a2 should be revoked")
	}
	if _, ok := store.GetSession("a3"); !ok {
		t.Fatal("a3 belongs to another device and must stay active")
	}
	if _, ok := store.GetSession("b1"); !ok {
		t.Fatal("b1 belongs to another account and must stay active")
	}
}

func TestStore_RevokeSessionsForDeviceKeepsExceptToken(t *testing.T) {
	store := newTestStore(t)

	seedSession(t, store, "keep", "NX-C", "dev_1")
	seedSession(t, store, "drop", "NX-C", "dev_1")

	revoked, err := store.RevokeSessionsForDevice("NX-C", "dev_1", "keep", 0)
	if err != nil {
		t.Fatalf("RevokeSessionsForDevice: %v", err)
	}
	if revoked != 1 {
		t.Fatalf("expected 1 revoked session, got %d", revoked)
	}

	if _, ok := store.GetSession("keep"); !ok {
		t.Fatal("exceptToken must remain active")
	}
	if _, ok := store.GetSession("drop"); ok {
		t.Fatal("sibling token should be revoked")
	}
}

func TestStore_RevokeSessionsForDeviceAllDevices(t *testing.T) {
	store := newTestStore(t)

	seedSession(t, store, "d1", "NX-D", "dev_1")
	seedSession(t, store, "d2", "NX-D", "dev_2")

	revoked, err := store.RevokeSessionsForDevice("NX-D", "", "", 0)
	if err != nil {
		t.Fatalf("RevokeSessionsForDevice: %v", err)
	}
	if revoked != 2 {
		t.Fatalf("expected 2 revoked sessions, got %d", revoked)
	}

	if _, ok := store.GetSession("d1"); ok {
		t.Fatal("d1 should be revoked")
	}
	if _, ok := store.GetSession("d2"); ok {
		t.Fatal("d2 should be revoked")
	}
}

func TestStore_RevokeSessionsForUnknownAccountIsNoop(t *testing.T) {
	store := newTestStore(t)

	seedSession(t, store, "keep-me", "NX-E", "dev_1")

	revoked, err := store.RevokeSessionsForDevice("NX-UNKNOWN", "dev_1", "", 0)
	if err != nil {
		t.Fatalf("RevokeSessionsForDevice: %v", err)
	}
	if revoked != 0 {
		t.Fatalf("expected 0 revoked sessions, got %d", revoked)
	}
	if _, ok := store.GetSession("keep-me"); !ok {
		t.Fatal("unrelated session must stay active")
	}
}

func TestStore_RevokeSessionsForDeviceRespectsCreatedBefore(t *testing.T) {
	store := newTestStore(t)

	base := time.Now().UnixMilli()
	create := func(token string, createdAt int64) {
		t.Helper()
		if err := store.CreateSession(&Session{
			Token:     token,
			AccountID: "acc_NX-F",
			NxID:      "NX-F",
			DeviceID:  "dev_1",
			ExpiresAt: base + time.Hour.Milliseconds(),
			CreatedAt: createdAt,
		}); err != nil {
			t.Fatalf("CreateSession(%s): %v", token, err)
		}
	}

	create("older_1", base)
	create("older_2", base+10)
	create("replacement", base+20)
	// Same-millisecond sibling: a concurrent rotation must not be able to
	// revoke a token created at the same instant as the replacement.
	create("same_ms_sibling", base+20)

	revoked, err := store.RevokeSessionsForDevice("NX-F", "dev_1", "replacement", base+20)
	if err != nil {
		t.Fatalf("RevokeSessionsForDevice: %v", err)
	}
	if revoked != 2 {
		t.Fatalf("expected 2 older sessions revoked, got %d", revoked)
	}

	if _, ok := store.GetSession("older_1"); ok {
		t.Fatal("older_1 should be revoked")
	}
	if _, ok := store.GetSession("older_2"); ok {
		t.Fatal("older_2 should be revoked")
	}
	if _, ok := store.GetSession("replacement"); !ok {
		t.Fatal("replacement token must remain active")
	}
	if _, ok := store.GetSession("same_ms_sibling"); !ok {
		t.Fatal("a token created at the cutoff instant must not be revoked")
	}
}
