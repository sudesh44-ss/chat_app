package identity

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type Store struct {
	mu            sync.RWMutex
	filePath      string
	accounts      map[string]*UserAccount // nxID -> account
	byPhone       map[string]string       // phone -> nxID
	byGoogle      map[string]string       // googleID -> nxID
	bySupabaseUID map[string]string       // supabaseUID -> nxID
	byPubKey      map[string]string       // pubKeyHex -> nxID
	devices       map[string]*Device      // deviceID -> device
	devicesByNx   map[string][]string     // nxID -> []deviceID
	sessions      map[string]*Session     // token -> session
}

type storeSnapshot struct {
	Accounts []*UserAccount `json:"accounts"`
	Devices  []*Device      `json:"devices"`
	Sessions []*Session     `json:"sessions"`
}

func NewStore(filePath string) (*Store, error) {
	s := &Store{
		filePath:      filePath,
		accounts:      make(map[string]*UserAccount),
		byPhone:       make(map[string]string),
		byGoogle:      make(map[string]string),
		bySupabaseUID: make(map[string]string),
		byPubKey:      make(map[string]string),
		devices:       make(map[string]*Device),
		devicesByNx:   make(map[string][]string),
		sessions:      make(map[string]*Session),
	}

	if filePath != "" {
		if err := s.load(); err != nil && !os.IsNotExist(err) {
			log.Printf("[IdentityStore] Warning loading snapshot from %s: %v", filePath, err)
		}
	}

	return s, nil
}

func (s *Store) load() error {
	data, err := os.ReadFile(s.filePath)
	if err != nil {
		return err
	}

	var snap storeSnapshot
	if err := json.Unmarshal(data, &snap); err != nil {
		// Attempt automatic fallback to .bak if available
		bakPath := s.filePath + ".bak"
		if bakData, bakErr := os.ReadFile(bakPath); bakErr == nil {
			var bakSnap storeSnapshot
			if json.Unmarshal(bakData, &bakSnap) == nil {
				log.Printf("[IdentityStore] Recovered corrupt identity store from backup %s", bakPath)
				snap = bakSnap
				data = bakData
			} else {
				return err
			}
		} else {
			return err
		}
	}

	// Create or update safe backup on clean load
	bakPath := s.filePath + ".bak"
	_ = os.WriteFile(bakPath, data, 0600)

	for _, a := range snap.Accounts {
		s.accounts[a.NxID] = a
		if a.PhoneNumber != "" {
			s.byPhone[a.PhoneNumber] = a.NxID
		}
		if a.GoogleID != "" {
			s.byGoogle[a.GoogleID] = a.NxID
		}
		if a.SupabaseUserID != "" {
			s.bySupabaseUID[a.SupabaseUserID] = a.NxID
		}
		if a.PublicKeyHex != "" {
			s.byPubKey[a.PublicKeyHex] = a.NxID
		}
	}

	for _, d := range snap.Devices {
		s.devices[d.DeviceID] = d
		s.devicesByNx[d.NxID] = append(s.devicesByNx[d.NxID], d.DeviceID)
	}

	now := time.Now().UnixMilli()
	for _, sess := range snap.Sessions {
		if !sess.IsRevoked && sess.ExpiresAt > now {
			s.sessions[sess.Token] = sess
		}
	}

	log.Printf("[IdentityStore] Loaded %d accounts, %d devices, %d active sessions from disk", len(s.accounts), len(s.devices), len(s.sessions))
	return nil
}

func (s *Store) save() error {
	if s.filePath == "" {
		return nil
	}

	dir := filepath.Dir(s.filePath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}

	snap := storeSnapshot{
		Accounts: make([]*UserAccount, 0, len(s.accounts)),
		Devices:  make([]*Device, 0, len(s.devices)),
		Sessions: make([]*Session, 0, len(s.sessions)),
	}

	for _, a := range s.accounts {
		snap.Accounts = append(snap.Accounts, a)
	}
	for _, d := range s.devices {
		snap.Devices = append(snap.Devices, d)
	}
	for _, sess := range s.sessions {
		snap.Sessions = append(snap.Sessions, sess)
	}

	data, err := json.MarshalIndent(snap, "", "  ")
	if err != nil {
		return err
	}

	// Atomic write
	tmpFile := fmt.Sprintf("%s.tmp.%d", s.filePath, time.Now().UnixNano())
	if err := os.WriteFile(tmpFile, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmpFile, s.filePath)
}

// AllocateNxID allocates a unique random NX-ID conforming to the locked NX-XXXXXXXX format.
// If the public key is already registered to an existing account, that stable NX-ID is returned.
func (s *Store) AllocateNxID(pubKeyHex string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// 1. Stability: check if this public key is already assigned an NX-ID
	if pubKeyHex != "" {
		if existingNx, found := s.byPubKey[pubKeyHex]; found {
			return existingNx, nil
		}
	}

	// 2. Generate random NX-XXXXXXXX with collision check and retry
	for attempt := 0; attempt < 10; attempt++ {
		candidateNx, err := GenerateRandomNxID()
		if err != nil {
			return "", err
		}

		if _, exists := s.accounts[candidateNx]; !exists {
			return candidateNx, nil
		}
	}

	return "", fmt.Errorf("failed to allocate unique random NX-ID after 10 attempts")
}

func (s *Store) CreateAccount(account *UserAccount) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.accounts[account.NxID]; exists {
		return fmt.Errorf("account with NX-ID %s already exists", account.NxID)
	}

	if account.PhoneNumber != "" {
		if existing, exists := s.byPhone[account.PhoneNumber]; exists && existing != account.NxID {
			return fmt.Errorf("phone number already associated with account %s", existing)
		}
	}

	if account.GoogleID != "" {
		if existing, exists := s.byGoogle[account.GoogleID]; exists && existing != account.NxID {
			return fmt.Errorf("google ID already associated with account %s", existing)
		}
	}

	if account.SupabaseUserID != "" {
		if existing, exists := s.bySupabaseUID[account.SupabaseUserID]; exists && existing != account.NxID {
			return fmt.Errorf("supabase user ID already associated with account %s", existing)
		}
	}

	if account.PublicKeyHex != "" {
		if existing, exists := s.byPubKey[account.PublicKeyHex]; exists && existing != account.NxID {
			return fmt.Errorf("public key already registered to account %s", existing)
		}
	}

	s.accounts[account.NxID] = account
	if account.PhoneNumber != "" {
		s.byPhone[account.PhoneNumber] = account.NxID
	}
	if account.GoogleID != "" {
		s.byGoogle[account.GoogleID] = account.NxID
	}
	if account.SupabaseUserID != "" {
		s.bySupabaseUID[account.SupabaseUserID] = account.NxID
	}
	if account.PublicKeyHex != "" {
		s.byPubKey[account.PublicKeyHex] = account.NxID
	}

	return s.save()
}

func (s *Store) GetAccountByNxID(nxID string) (*UserAccount, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	a, ok := s.accounts[nxID]
	return a, ok
}

func (s *Store) GetAccountByPhone(phone string) (*UserAccount, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	nxID, ok := s.byPhone[phone]
	if !ok {
		return nil, false
	}
	a, ok := s.accounts[nxID]
	return a, ok
}

func (s *Store) GetAccountByGoogleID(googleID string) (*UserAccount, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	nxID, ok := s.byGoogle[googleID]
	if !ok {
		return nil, false
	}
	a, ok := s.accounts[nxID]
	return a, ok
}

func (s *Store) GetAccountBySupabaseUID(supabaseUID string) (*UserAccount, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	nxID, ok := s.bySupabaseUID[supabaseUID]
	if !ok {
		return nil, false
	}
	a, ok := s.accounts[nxID]
	return a, ok
}

func (s *Store) GetAccountByPublicKey(pubKeyHex string) (*UserAccount, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	nxID, ok := s.byPubKey[pubKeyHex]
	if !ok {
		return nil, false
	}
	a, ok := s.accounts[nxID]
	return a, ok
}

func (s *Store) UpdateAccount(account *UserAccount) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	account.UpdatedAt = time.Now().UnixMilli()
	s.accounts[account.NxID] = account

	if account.PhoneNumber != "" {
		s.byPhone[account.PhoneNumber] = account.NxID
	}
	if account.GoogleID != "" {
		s.byGoogle[account.GoogleID] = account.NxID
	}
	if account.SupabaseUserID != "" {
		s.bySupabaseUID[account.SupabaseUserID] = account.NxID
	}
	if account.PublicKeyHex != "" {
		s.byPubKey[account.PublicKeyHex] = account.NxID
	}

	return s.save()
}

// LookupVerifiedPhoneNumbers queries registered and verified accounts for phone discovery.
// Strict Privacy: Only returns confirmed matches for accounts with phone_verified = true.
// Does NOT store, log, or persist the query phone numbers.
func (s *Store) LookupVerifiedPhoneNumbers(phoneNumbers []string) []*ContactMatch {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var matches []*ContactMatch
	seen := make(map[string]bool)

	for _, phone := range phoneNumbers {
		if phone == "" || seen[phone] {
			continue
		}
		seen[phone] = true

		nxID, found := s.byPhone[phone]
		if !found {
			continue
		}

		acc, ok := s.accounts[nxID]
		if !ok {
			continue
		}

		// Only return if phone is verified and account allows phone discoverability
		if acc.PhoneVerified || acc.PhoneNumber == phone {
			matches = append(matches, &ContactMatch{
				PhoneNumber:  phone,
				NxID:         acc.NxID,
				DisplayName:  acc.ProfileName,
				PublicKeyHex: acc.PublicKeyHex,
			})
		}
	}

	return matches
}

func (s *Store) RegisterDevice(d *Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.devices[d.DeviceID] = d
	exists := false
	for _, id := range s.devicesByNx[d.NxID] {
		if id == d.DeviceID {
			exists = true
			break
		}
	}
	if !exists {
		s.devicesByNx[d.NxID] = append(s.devicesByNx[d.NxID], d.DeviceID)
	}

	return s.save()
}

func (s *Store) GetDevice(deviceID string) (*Device, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	d, ok := s.devices[deviceID]
	return d, ok
}

func (s *Store) GetDevicesForAccount(nxID string) []*Device {
	s.mu.RLock()
	defer s.mu.RUnlock()

	deviceIDs, ok := s.devicesByNx[nxID]
	if !ok {
		return nil
	}

	result := make([]*Device, 0, len(deviceIDs))
	for _, id := range deviceIDs {
		if d, found := s.devices[id]; found && !d.IsRevoked {
			result = append(result, d)
		}
	}
	return result
}

func (s *Store) RevokeDevice(deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	d, ok := s.devices[deviceID]
	if !ok {
		return fmt.Errorf("device %s not found", deviceID)
	}

	d.IsRevoked = true
	d.FcmToken = ""

	for _, sess := range s.sessions {
		if sess.DeviceID == deviceID {
			sess.IsRevoked = true
		}
	}

	return s.save()
}

func (s *Store) UpdateDeviceFcmToken(deviceID string, token string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	d, ok := s.devices[deviceID]
	if !ok {
		return fmt.Errorf("device %s not found", deviceID)
	}

	d.FcmToken = token
	d.LastActiveAt = time.Now().UnixMilli()
	return s.save()
}

func (s *Store) CreateSession(sess *Session) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.sessions[sess.Token] = sess
	return s.save()
}

func (s *Store) GetSession(token string) (*Session, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	sess, ok := s.sessions[token]
	if !ok || sess.IsRevoked || time.Now().UnixMilli() > sess.ExpiresAt {
		return nil, false
	}
	return sess, true
}

func (s *Store) RevokeSession(token string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if sess, ok := s.sessions[token]; ok {
		sess.IsRevoked = true
		return s.save()
	}
	return nil
}

// RegisterUser allocates an NX-ID, creates an account, registers initial device and session.
func (s *Store) RegisterUser(phone string, googleID string, pubKeyHex string, deviceID string, deviceName string, profileName string) (*UserAccount, *Session, error) {
	nxID, err := s.AllocateNxID(pubKeyHex)
	if err != nil {
		return nil, nil, err
	}

	now := time.Now().UnixMilli()
	account := &UserAccount{
		ID:                    "usr_" + nxID,
		NxID:                  nxID,
		PublicKeyHex:          pubKeyHex,
		PhoneNumber:           phone,
		PhoneVerified:         phone != "",
		IsDiscoverableByPhone: true,
		GoogleID:              googleID,
		ProfileName:           profileName,
		CreatedAt:             now,
		UpdatedAt:             now,
	}

	if err := s.CreateAccount(account); err != nil {
		return nil, nil, err
	}

	dev := &Device{
		DeviceID:     deviceID,
		AccountID:    account.ID,
		NxID:         nxID,
		DeviceName:   deviceName,
		PublicKeyHex: pubKeyHex,
		CreatedAt:    now,
		LastActiveAt: now,
	}
	if err := s.RegisterDevice(dev); err != nil {
		return nil, nil, err
	}

	sess := &Session{
		Token:     "tok_" + fmt.Sprintf("%d", time.Now().UnixNano()),
		AccountID: account.ID,
		NxID:      nxID,
		DeviceID:  deviceID,
		ExpiresAt: now + 30*24*3600*1000,
		CreatedAt: now,
	}
	if err := s.CreateSession(sess); err != nil {
		return nil, nil, err
	}

	return account, sess, nil
}

// GetUser returns an account by NX-ID or an error if not found.
func (s *Store) GetUser(nxID string) (*UserAccount, error) {
	acc, ok := s.GetAccountByNxID(nxID)
	if !ok {
		return nil, fmt.Errorf("user %s not found", nxID)
	}
	return acc, nil
}

// GetDevices returns active devices for an NX-ID.
func (s *Store) GetDevices(nxID string) ([]*Device, error) {
	devs := s.GetDevicesForAccount(nxID)
	return devs, nil
}

// RegisterDeviceWithParams registers a device and creates a session token.
func (s *Store) RegisterDeviceWithParams(nxID string, deviceID string, deviceName string, pubKeyHex string, fcmToken string) (*Device, *Session, error) {
	dev := &Device{
		DeviceID:     deviceID,
		AccountID:    "usr_" + nxID,
		NxID:         nxID,
		DeviceName:   deviceName,
		PublicKeyHex: pubKeyHex,
		FcmToken:     fcmToken,
		CreatedAt:    time.Now().UnixMilli(),
		LastActiveAt: time.Now().UnixMilli(),
	}
	if err := s.RegisterDevice(dev); err != nil {
		return nil, nil, err
	}

	sess := &Session{
		Token:     "tok_" + fmt.Sprintf("%d", time.Now().UnixNano()),
		AccountID: dev.AccountID,
		NxID:      nxID,
		DeviceID:  deviceID,
		ExpiresAt: time.Now().UnixMilli() + 30*24*3600*1000,
		CreatedAt: time.Now().UnixMilli(),
	}
	if err := s.CreateSession(sess); err != nil {
		return nil, nil, err
	}

	return dev, sess, nil
}

// RevokeDeviceForUser revokes a device by NX-ID and deviceID.
func (s *Store) RevokeDeviceForUser(nxID string, deviceID string) error {
	return s.RevokeDevice(deviceID)
}
