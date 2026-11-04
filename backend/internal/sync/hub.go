package sync

import (
	"sync"
	"time"
)

/**
 * QrSyncEvent captures a cryptographically verified Day-0 QR contact exchange.
 */
type QrSyncEvent struct {
	ScannerNxID      string `json:"scanner_nx_id"`
	ScannedNxID      string `json:"scanned_nx_id"`
	ScannedSignature string `json:"scanned_signature"`
	Timestamp        int64  `json:"timestamp"`
}

/**
 * SyncHub provides an in-memory, zero-disk peer verification relay.
 * It stores recent handshake events per user in bounded memory rings.
 */
type SyncHub struct {
	mu      sync.RWMutex
	events  map[string][]*QrSyncEvent
	maxSize int
}

func NewSyncHub(maxEventsPerUser int) *SyncHub {
	if maxEventsPerUser <= 0 {
		maxEventsPerUser = 50
	}
	return &SyncHub{
		events:  make(map[string][]*QrSyncEvent),
		maxSize: maxEventsPerUser,
	}
}

func (h *SyncHub) RecordHandshake(scannerNxID, scannedNxID, signature string) *QrSyncEvent {
	h.mu.Lock()
	defer h.mu.Unlock()

	event := &QrSyncEvent{
		ScannerNxID:      scannerNxID,
		ScannedNxID:      scannedNxID,
		ScannedSignature: signature,
		Timestamp:        time.Now().Unix(),
	}

	h.appendEvent(scannerNxID, event)
	h.appendEvent(scannedNxID, event)

	return event
}

func (h *SyncHub) appendEvent(nxID string, event *QrSyncEvent) {
	list := h.events[nxID]
	if len(list) >= h.maxSize {
		// Evict oldest event to bound memory
		list = list[1:]
	}
	h.events[nxID] = append(list, event)
}

func (h *SyncHub) GetEvents(nxID string) []*QrSyncEvent {
	h.mu.RLock()
	defer h.mu.RUnlock()

	list, ok := h.events[nxID]
	if !ok || len(list) == 0 {
		return []*QrSyncEvent{}
	}
	out := make([]*QrSyncEvent, len(list))
	copy(out, list)
	return out
}
