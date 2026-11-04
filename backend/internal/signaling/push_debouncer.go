package signaling

import (
	"fmt"
	"log"
	"sync"
	"time"
)

type DebounceBatch struct {
	RecipientNxID string
	SenderNames   map[string]bool
	FileCount     int
	Timer         *time.Timer
}

type PushDebouncer struct {
	mu            sync.Mutex
	batches       map[string]*DebounceBatch
	window        time.Duration
	onDispatchPush func(recipientNxID string, title string, body string, fileCount int)
}

func NewPushDebouncer(window time.Duration, onDispatch func(string, string, string, int)) *PushDebouncer {
	if window <= 0 {
		window = 5 * time.Second
	}
	return &PushDebouncer{
		batches:        make(map[string]*DebounceBatch),
		window:        window,
		onDispatchPush: onDispatch,
	}
}

func (pd *PushDebouncer) RecordIncomingFile(recipientNxID string, senderName string) {
	pd.mu.Lock()
	defer pd.mu.Unlock()

	batch, exists := pd.batches[recipientNxID]
	if !exists {
		batch = &DebounceBatch{
			RecipientNxID: recipientNxID,
			SenderNames:   make(map[string]bool),
			FileCount:     0,
		}
		// Arm 5-second sliding debounce timer
		batch.Timer = time.AfterFunc(pd.window, func() {
			pd.flushBatch(recipientNxID)
		})
		pd.batches[recipientNxID] = batch
	}

	batch.SenderNames[senderName] = true
	batch.FileCount++
}

func (pd *PushDebouncer) flushBatch(recipientNxID string) {
	pd.mu.Lock()
	batch, exists := pd.batches[recipientNxID]
	if !exists {
		pd.mu.Unlock()
		return
	}
	delete(pd.batches, recipientNxID)
	pd.mu.Unlock()

	title := "NEXA Transfers"
	var body string
	if batch.FileCount == 1 {
		body = "1 incoming file waiting for your approval"
	} else {
		body = fmt.Sprintf("%d incoming files waiting from your contacts", batch.FileCount)
	}

	log.Printf("[PushDebouncer] Dispatched aggregated push to %s: %s (%d files)", recipientNxID, body, batch.FileCount)
	if pd.onDispatchPush != nil {
		pd.onDispatchPush(recipientNxID, title, body, batch.FileCount)
	}
}
