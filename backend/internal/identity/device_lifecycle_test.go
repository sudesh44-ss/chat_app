package identity

import (
	"testing"
	"time"
)

func newTestStore(t *testing.T) *Store {
	t.Helper()
	store, err := NewStore("")
	if err != nil {
		t.Fatalf("NewStore: %v", err)
	}
	return store
}

func seedAccount(t *testing.T, store *Store, nxID, phone string) *UserAccount {
	t.Helper()
	account := &UserAccount{
		ID:                    "usr_" + nxID,
		NxID:                  nxID,
		PublicKeyHex:          "pub_" + nxID,
		PhoneNumber:           phone,
		PhoneVerified:         phone != "",
		IsDiscoverableByPhone: true,
		ProfileName:           "Test " + nxID,
		CreatedAt:             time.Now().UnixMilli(),
		UpdatedAt:             time.Now().UnixMilli(),
	}
	if err := store.CreateAccount(account); err != nil {
		t.Fatalf("CreateAccount: %v", err)
	}
	return account
}

func TestStore_SingleActiveDeviceReplacesOthers(t *testing.T) {
	store := newTestStore(t)
	acc := seedAccount(t, store, "NX-AAAA1111", "+919876500001")

	first := &Device{
		DeviceID:     "dev_first",
		AccountID:    acc.ID,
		NxID:         acc.NxID,
		DeviceName:   "Old Phone",
		PublicKeyHex: "devpub1",
		CreatedAt:    time.Now().UnixMilli(),
		LastActiveAt: time.Now().UnixMilli(),
	}
	if _, err := store.RegisterDeviceReplacingOthers(first, "initial"); err != nil {
		t.Fatalf("first register: %v", err)
	}
	if err := store.CreateSession(&Session{
		Token:     "sess_first",
		AccountID: acc.ID,
		NxID:      acc.NxID,
		DeviceID:  first.DeviceID,
		ExpiresAt: time.Now().Add(time.Hour).UnixMilli(),
	}); err != nil {
		t.Fatalf("CreateSession: %v", err)
	}

	second := &Device{
		DeviceID:     "dev_second",
		AccountID:    acc.ID,
		NxID:         acc.NxID,
		DeviceName:   "New Phone",
		PublicKeyHex: "devpub2",
		CreatedAt:    time.Now().UnixMilli(),
		LastActiveAt: time.Now().UnixMilli(),
	}
	revoked, err := store.RegisterDeviceReplacingOthers(second, "replaced by new sign-in")
	if err != nil {
		t.Fatalf("second register: %v", err)
	}
	if len(revoked) != 1 || revoked[0] != first.DeviceID {
		t.Fatalf("expected first device to be revoked, got %v", revoked)
	}

	active := store.GetDevicesForAccount(acc.NxID)
	if len(active) != 1 || active[0].DeviceID != second.DeviceID {
		t.Fatalf("expected only the second device to be active, got %+v", active)
	}

	all := store.GetAllDevicesForAccount(acc.NxID)
	if len(all) != 2 {
		t.Fatalf("expected active + revoked history, got %d", len(all))
	}

	old, _ := store.GetDevice(first.DeviceID)
	if !old.IsRevoked || old.RevokedAt == 0 || old.RevokedReason == "" {
		t.Fatalf("old device missing revocation metadata: %+v", old)
	}

	if sess, ok := store.GetSession("sess_first"); ok && !sess.IsRevoked {
		t.Fatal("old device session must be revoked")
	}

	events, err := store.GetDeviceEvents(acc.NxID, 10)
	if err != nil {
		t.Fatalf("GetDeviceEvents: %v", err)
	}
	var sawReplaced, sawRegistered bool
	for _, e := range events {
		switch e.EventType {
		case "REVOKED_REPLACED":
			sawReplaced = true
		case "REGISTERED":
			sawRegistered = true
		}
	}
	if !sawReplaced || !sawRegistered {
		t.Fatalf("expected revocation and registration audit events, got %+v", events)
	}
}

func TestStore_RevokeDeviceForUserRejectsWrongOwner(t *testing.T) {
	store := newTestStore(t)
	owner := seedAccount(t, store, "NX-BBBB2222", "+919876500002")
	other := seedAccount(t, store, "NX-CCCC3333", "+919876500003")

	device := &Device{
		DeviceID:     "dev_owned",
		AccountID:    owner.ID,
		NxID:         owner.NxID,
		DeviceName:   "Owned",
		PublicKeyHex: "devpub-owned",
		CreatedAt:    time.Now().UnixMilli(),
	}
	if err := store.RegisterDevice(device); err != nil {
		t.Fatalf("RegisterDevice: %v", err)
	}

	if err := store.RevokeDeviceForUser(other.NxID, device.DeviceID); err == nil {
		t.Fatal("expected ownership check to reject a different account")
	}
	if d, _ := store.GetDevice(device.DeviceID); d.IsRevoked {
		t.Fatal("device must not be revoked by a non-owner")
	}

	if err := store.RevokeDeviceForUser(owner.NxID, device.DeviceID); err != nil {
		t.Fatalf("owner revoke failed: %v", err)
	}
	if d, _ := store.GetDevice(device.DeviceID); !d.IsRevoked {
		t.Fatal("owner revoke did not mark device revoked")
	}
}

func TestStore_PhoneLookupUsesProtectedIndex(t *testing.T) {
	store := newTestStore(t)
	phone := "+919876500004"
	acc := seedAccount(t, store, "NX-DDDD4444", phone)

	if acc.PhoneHMAC == "" || acc.PhoneEncrypted == "" {
		t.Fatalf("phone protection fields not populated: %+v", acc)
	}
	if acc.PhoneHMAC == phone {
		t.Fatal("PhoneHMAC must not equal the plaintext phone")
	}

	found, ok := store.GetAccountByPhone(phone)
	if !ok || found.NxID != acc.NxID {
		t.Fatalf("GetAccountByPhone failed for protected account: %v", ok)
	}

	matches := store.LookupVerifiedPhoneNumbers([]string{phone})
	if len(matches) != 1 || matches[0].NxID != acc.NxID {
		t.Fatalf("LookupVerifiedPhoneNumbers failed: %+v", matches)
	}
	if matches[0].PhoneNumber != phone {
		t.Fatalf("lookup should return the caller's input phone, got %q", matches[0].PhoneNumber)
	}
}
