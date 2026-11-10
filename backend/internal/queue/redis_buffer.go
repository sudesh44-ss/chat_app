package queue

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"nexa/wire/internal/wire"

	"github.com/redis/go-redis/v9"
)

const (
	PendingKeyPrefix    = "pending:"
	QueueMsgPrefix      = "nexa:q:msg:"
	QueueListPrefix     = "nexa:q:pending:"
	QueueFlightPrefix   = "nexa:q:inflight:"
	DefaultTTL          = 24 * time.Hour // 86400s ephemeral retention
	DefaultAckTimeout   = 30 * time.Second
	MaxDeliveryAttempts = 5
)

type QueueStatus string

const (
	StatusQueued       QueueStatus = "QUEUED"
	StatusInFlight     QueueStatus = "IN_FLIGHT"
	StatusAcknowledged QueueStatus = "ACKNOWLEDGED"
	StatusExpired      QueueStatus = "EXPIRED"
)

// QueueEntry represents a message in the reliable reconciliation queue.
type QueueEntry struct {
	MessageID        string      `json:"id"`
	QueueKey         string      `json:"queue_key"`
	Payload          []byte      `json:"payload"`
	EnqueuedAt       int64       `json:"enqueued_at"`
	ExpiresAt        int64       `json:"expires_at"`
	DeliveryAttempts int         `json:"attempts"`
	LastAttemptAt    int64       `json:"last_attempt_at"`
	Status           QueueStatus `json:"status"`
}

type Buffer interface {
	PushPending(ctx context.Context, recipientNxId string, packetBytes []byte) error
	PopAllPending(ctx context.Context, recipientNxId string) ([][]byte, error)
	IsConnected() bool
	IsDegraded() bool
	GetClient() *redis.Client
	Close() error

	// Phase I Reliable Queue & Reconciliation Methods
	Enqueue(ctx context.Context, queueKey, messageID string, payload []byte, expiresAt int64) error
	ClaimPending(ctx context.Context, queueKey string, batchSize int, ackTimeout time.Duration) ([]*QueueEntry, error)
	Acknowledge(ctx context.Context, queueKey, messageID string) (bool, error)
	RevertInFlight(ctx context.Context, queueKey, messageID string) error
	GetQueueMetrics(ctx context.Context, queueKey string) (pending int64, inFlight int64, err error)
}

// RedisBuffer manages the zero-disk ephemeral buffer backed by Redis with in-memory fallback.
type RedisBuffer struct {
	client     *redis.Client
	isDegraded bool

	// In-memory fallback if Redis is not configured or offline
	fallbackMu  sync.Mutex
	fallback    map[string][][]byte               // for legacy PopAllPending
	memEntries  map[string]map[string]*QueueEntry // queueKey -> (messageId -> entry)
	memPending  map[string][]string               // queueKey -> []messageId
	memInFlight map[string]map[string]int64       // queueKey -> (messageId -> claimTimestamp)
}

func NewRedisBuffer(redisAddr, password string) (*RedisBuffer, error) {
	redisURL := strings.TrimSpace(os.Getenv("REDIS_URL"))
	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	isProduction := env == "production"
	return NewRedisBufferWithConfig(redisURL, redisAddr, password, isProduction)
}

func NewRedisBufferWithURL(redisURL string) (*RedisBuffer, error) {
	env := strings.ToLower(os.Getenv("APP_ENV"))
	if env == "" {
		env = strings.ToLower(os.Getenv("ENV"))
	}
	isProduction := env == "production"
	return NewRedisBufferWithConfig(redisURL, "", "", isProduction)
}

func NewRedisBufferWithConfig(redisURL, redisAddr, password string, isProduction bool) (*RedisBuffer, error) {
	rb := &RedisBuffer{
		fallback:    make(map[string][][]byte),
		memEntries:  make(map[string]map[string]*QueueEntry),
		memPending:  make(map[string][]string),
		memInFlight: make(map[string]map[string]int64),
		isDegraded:  false,
	}

	var opts *redis.Options
	var targetDesc string

	if redisURL != "" {
		parsedOpts, err := redis.ParseURL(redisURL)
		if err != nil {
			if isProduction {
				log.Printf("[Queue] CRITICAL ERROR: Failed to parse REDIS_URL in production: %v", err)
				rb.isDegraded = true
				return rb, fmt.Errorf("invalid REDIS_URL: %w", err)
			}
			log.Printf("[Queue] Warning: Failed to parse REDIS_URL: %v. Falling back to in-memory RAM queue.", err)
			rb.isDegraded = true
			return rb, nil
		}
		opts = parsedOpts
		targetDesc = opts.Addr
	} else if redisAddr != "" && redisAddr != "localhost:6379" {
		opts = &redis.Options{
			Addr:     redisAddr,
			Password: password,
			DB:       0,
		}
		targetDesc = redisAddr
	} else if redisAddr == "localhost:6379" {
		if isProduction {
			log.Println("[Queue] CRITICAL CONFIG ERROR: Neither REDIS_URL nor a remote REDIS_ADDR is configured in production! Operating in DEGRADED in-memory RAM mode. State WILL NOT persist across restarts.")
			rb.isDegraded = true
			return rb, nil
		}
		opts = &redis.Options{
			Addr:     redisAddr,
			Password: password,
			DB:       0,
		}
		targetDesc = redisAddr
	} else {
		if isProduction {
			log.Println("[Queue] CRITICAL CONFIG ERROR: Running in production with NO Redis configured! Operating in DEGRADED in-memory mode. State WILL NOT persist across restarts.")
			rb.isDegraded = true
		} else {
			log.Println("[Queue] No Redis address configured. Operating with zero-disk RAM fallback.")
		}
		return rb, nil
	}

	client := redis.NewClient(opts)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		if isProduction {
			log.Printf("[Queue] CRITICAL ALERT: Redis connection failed in production at %s (%v). Operating in DEGRADED in-memory mode. Offline state will NOT persist across restarts!", targetDesc, err)
			rb.isDegraded = true
		} else {
			log.Printf("[Queue] Redis connection failed at %s (%v). Falling back to in-memory RAM queue.", targetDesc, err)
			rb.isDegraded = true
		}
		return rb, nil
	}

	rb.client = client
	rb.isDegraded = false
	hasTLS := opts.TLSConfig != nil
	log.Printf("[Queue] Connected to ephemeral Redis at %s (TLS: %v, TTL: %v)", targetDesc, hasTLS, DefaultTTL)
	return rb, nil
}

func ExtractMessageID(packetBytes []byte) string {
	if pkt, err := wire.Decode(packetBytes); err == nil && pkt.PacketId != "" {
		return pkt.PacketId
	}
	if len(packetBytes) >= 36 && string(packetBytes[0:4]) == "NXDT" {
		return hex.EncodeToString(packetBytes[20:36])
	}
	h := sha256.Sum256(packetBytes)
	return hex.EncodeToString(h[:16])
}

func extractMessageID(packetBytes []byte) string {
	return ExtractMessageID(packetBytes)
}

func (r *RedisBuffer) PushPending(ctx context.Context, recipientNxId string, packetBytes []byte) error {
	msgID := extractMessageID(packetBytes)
	_ = r.Enqueue(ctx, recipientNxId, msgID, packetBytes, 0)

	if r.client != nil {
		key := fmt.Sprintf("%s%s", PendingKeyPrefix, recipientNxId)
		pipe := r.client.Pipeline()
		pipe.RPush(ctx, key, packetBytes)
		pipe.Expire(ctx, key, DefaultTTL)
		_, err := pipe.Exec(ctx)
		return err
	}

	r.fallbackMu.Lock()
	defer r.fallbackMu.Unlock()
	r.fallback[recipientNxId] = append(r.fallback[recipientNxId], packetBytes)
	return nil
}

func (r *RedisBuffer) PopAllPending(ctx context.Context, recipientNxId string) ([][]byte, error) {
	if r.client != nil {
		key := fmt.Sprintf("%s%s", PendingKeyPrefix, recipientNxId)
		results, err := r.client.LRange(ctx, key, 0, -1).Result()
		if err != nil {
			return nil, err
		}

		if len(results) > 0 {
			r.client.Del(ctx, key)
		}

		byteResults := make([][]byte, len(results))
		for i, res := range results {
			byteResults[i] = []byte(res)
		}
		return byteResults, nil
	}

	r.fallbackMu.Lock()
	defer r.fallbackMu.Unlock()
	items := r.fallback[recipientNxId]
	delete(r.fallback, recipientNxId)
	return items, nil
}

// Enqueue atomically inserts a message into the reliable queue with idempotency.
func (r *RedisBuffer) Enqueue(ctx context.Context, queueKey, messageID string, payload []byte, expiresAt int64) error {
	if r.client != nil {
		msgKey := QueueMsgPrefix + queueKey
		listKey := QueueListPrefix + queueKey

		exists, err := r.client.HExists(ctx, msgKey, messageID).Result()
		if err == nil && exists {
			log.Printf("[Queue] Idempotent enqueue: message %s already queued in %s", messageID, queueKey)
			return nil
		}

		entry := &QueueEntry{
			MessageID:        messageID,
			QueueKey:         queueKey,
			Payload:          payload,
			EnqueuedAt:       time.Now().Unix(),
			ExpiresAt:        expiresAt,
			DeliveryAttempts: 0,
			LastAttemptAt:    0,
			Status:           StatusQueued,
		}

		data, err := json.Marshal(entry)
		if err != nil {
			return err
		}

		pipe := r.client.Pipeline()
		pipe.HSet(ctx, msgKey, messageID, data)
		pipe.RPush(ctx, listKey, messageID)
		pipe.Expire(ctx, msgKey, DefaultTTL)
		pipe.Expire(ctx, listKey, DefaultTTL)
		_, err = pipe.Exec(ctx)
		return err
	}

	r.fallbackMu.Lock()
	defer r.fallbackMu.Unlock()

	r.ensureMemMaps(queueKey)
	if _, exists := r.memEntries[queueKey][messageID]; exists {
		log.Printf("[Queue] Idempotent enqueue (RAM): message %s already queued in %s", messageID, queueKey)
		return nil
	}

	entry := &QueueEntry{
		MessageID:        messageID,
		QueueKey:         queueKey,
		Payload:          payload,
		EnqueuedAt:       time.Now().Unix(),
		ExpiresAt:        expiresAt,
		DeliveryAttempts: 0,
		LastAttemptAt:    0,
		Status:           StatusQueued,
	}

	r.memEntries[queueKey][messageID] = entry
	r.memPending[queueKey] = append(r.memPending[queueKey], messageID)
	return nil
}

// ClaimPending claims up to batchSize pending messages, moving them to IN_FLIGHT.
// It automatically reclaims any timed-out in-flight entries older than ackTimeout.
func (r *RedisBuffer) ClaimPending(ctx context.Context, queueKey string, batchSize int, ackTimeout time.Duration) ([]*QueueEntry, error) {
	if batchSize <= 0 {
		batchSize = 50
	}
	if ackTimeout <= 0 {
		ackTimeout = DefaultAckTimeout
	}
	now := time.Now().Unix()

	if r.client != nil {
		msgKey := QueueMsgPrefix + queueKey
		listKey := QueueListPrefix + queueKey
		flightKey := QueueFlightPrefix + queueKey

		// Step A: Reclaim timed-out in-flight messages (deadline <= now)
		nowScore := float64(time.Now().UnixNano()) / 1e9
		timedOutIDs, err := r.client.ZRangeByScore(ctx, flightKey, &redis.ZRangeBy{
			Min: "-inf",
			Max: strconv.FormatFloat(nowScore, 'f', 6, 64),
		}).Result()

		if err == nil && len(timedOutIDs) > 0 {
			for _, id := range timedOutIDs {
				r.client.ZRem(ctx, flightKey, id)
				rawJSON, err := r.client.HGet(ctx, msgKey, id).Result()
				if err == nil {
					var entry QueueEntry
					if json.Unmarshal([]byte(rawJSON), &entry) == nil {
						if (entry.ExpiresAt > 0 && now > entry.ExpiresAt) || entry.DeliveryAttempts >= MaxDeliveryAttempts {
							log.Printf("[Queue] Message %s in %s exceeded max attempts (%d) or expired. Purging.", id, queueKey, entry.DeliveryAttempts)
							r.client.HDel(ctx, msgKey, id)
						} else {
							log.Printf("[Queue] Reclaiming timed-out message %s in %s (attempt %d). Re-queueing.", id, queueKey, entry.DeliveryAttempts)
							r.client.LPush(ctx, listKey, id)
						}
					}
				}
			}
		}

		// Step B: Claim up to batchSize pending messages
		var claimed []*QueueEntry
		for len(claimed) < batchSize {
			id, err := r.client.LPop(ctx, listKey).Result()
			if err == redis.Nil || id == "" {
				break
			}
			if err != nil {
				return claimed, err
			}

			rawJSON, err := r.client.HGet(ctx, msgKey, id).Result()
			if err != nil {
				continue
			}

			var entry QueueEntry
			if err := json.Unmarshal([]byte(rawJSON), &entry); err != nil {
				continue
			}

			if entry.ExpiresAt > 0 && now > entry.ExpiresAt {
				log.Printf("[Queue] Dropping expired message %s in %s (expired at %d, now %d)", id, queueKey, entry.ExpiresAt, now)
				r.client.HDel(ctx, msgKey, id)
				continue
			}

			entry.DeliveryAttempts++
			entry.LastAttemptAt = now
			entry.Status = StatusInFlight

			updatedJSON, _ := json.Marshal(&entry)
			deadlineScore := float64(time.Now().Add(ackTimeout).UnixNano()) / 1e9
			pipe := r.client.Pipeline()
			pipe.HSet(ctx, msgKey, id, updatedJSON)
			pipe.ZAdd(ctx, flightKey, redis.Z{Score: deadlineScore, Member: id})
			pipe.Expire(ctx, flightKey, DefaultTTL)
			_, _ = pipe.Exec(ctx)

			claimed = append(claimed, &entry)
		}
		return claimed, nil
	}

	r.fallbackMu.Lock()
	defer r.fallbackMu.Unlock()

	r.ensureMemMaps(queueKey)
	nowMilli := time.Now().UnixMilli()

	flightMap := r.memInFlight[queueKey]
	for id, deadlineMilli := range flightMap {
		if nowMilli >= deadlineMilli {
			delete(flightMap, id)
			entry, ok := r.memEntries[queueKey][id]
			if ok {
				if (entry.ExpiresAt > 0 && now > entry.ExpiresAt) || entry.DeliveryAttempts >= MaxDeliveryAttempts {
					delete(r.memEntries[queueKey], id)
				} else {
					r.memPending[queueKey] = append([]string{id}, r.memPending[queueKey]...)
				}
			}
		}
	}

	var claimed []*QueueEntry
	pendingList := r.memPending[queueKey]
	var remaining []string

	for i := 0; i < len(pendingList); i++ {
		id := pendingList[i]
		if len(claimed) >= batchSize {
			remaining = append(remaining, pendingList[i:]...)
			break
		}

		entry, ok := r.memEntries[queueKey][id]
		if !ok {
			continue
		}

		if entry.ExpiresAt > 0 && now > entry.ExpiresAt {
			delete(r.memEntries[queueKey], id)
			continue
		}

		entry.DeliveryAttempts++
		entry.LastAttemptAt = now
		entry.Status = StatusInFlight
		flightMap[id] = nowMilli + ackTimeout.Milliseconds()
		claimed = append(claimed, entry)
	}

	r.memPending[queueKey] = remaining
	return claimed, nil
}

// Acknowledge removes a successfully delivered message from the queue.
func (r *RedisBuffer) Acknowledge(ctx context.Context, queueKey, messageID string) (bool, error) {
	if r.client != nil {
		msgKey := QueueMsgPrefix + queueKey
		flightKey := QueueFlightPrefix + queueKey
		listKey := QueueListPrefix + queueKey

		exists, err := r.client.HExists(ctx, msgKey, messageID).Result()
		if err != nil {
			return false, err
		}

		pipe := r.client.Pipeline()
		pipe.ZRem(ctx, flightKey, messageID)
		pipe.HDel(ctx, msgKey, messageID)
		pipe.LRem(ctx, listKey, 0, messageID)
		_, err = pipe.Exec(ctx)
		return exists, err
	}

	r.fallbackMu.Lock()
	defer r.fallbackMu.Unlock()

	r.ensureMemMaps(queueKey)
	_, wasInMsg := r.memEntries[queueKey][messageID]
	delete(r.memEntries[queueKey], messageID)
	delete(r.memInFlight[queueKey], messageID)

	pending := r.memPending[queueKey]
	for i, id := range pending {
		if id == messageID {
			r.memPending[queueKey] = append(pending[:i], pending[i+1:]...)
			break
		}
	}

	return wasInMsg, nil
}

// RevertInFlight moves an in-flight message back to pending due to socket write failure.
func (r *RedisBuffer) RevertInFlight(ctx context.Context, queueKey, messageID string) error {
	if r.client != nil {
		msgKey := QueueMsgPrefix + queueKey
		flightKey := QueueFlightPrefix + queueKey
		listKey := QueueListPrefix + queueKey

		rawJSON, err := r.client.HGet(ctx, msgKey, messageID).Result()
		if err != nil {
			return err
		}

		var entry QueueEntry
		if err := json.Unmarshal([]byte(rawJSON), &entry); err == nil {
			entry.Status = StatusQueued
			data, _ := json.Marshal(&entry)
			r.client.HSet(ctx, msgKey, messageID, data)
		}

		pipe := r.client.Pipeline()
		pipe.ZRem(ctx, flightKey, messageID)
		pipe.LPush(ctx, listKey, messageID)
		_, err = pipe.Exec(ctx)
		return err
	}

	r.fallbackMu.Lock()
	defer r.fallbackMu.Unlock()

	r.ensureMemMaps(queueKey)
	delete(r.memInFlight[queueKey], messageID)
	if entry, ok := r.memEntries[queueKey][messageID]; ok {
		entry.Status = StatusQueued
	}
	r.memPending[queueKey] = append([]string{messageID}, r.memPending[queueKey]...)
	return nil
}

// GetQueueMetrics returns the count of pending and in-flight messages for a queue.
func (r *RedisBuffer) GetQueueMetrics(ctx context.Context, queueKey string) (pending int64, inFlight int64, err error) {
	if r.client != nil {
		listKey := QueueListPrefix + queueKey
		flightKey := QueueFlightPrefix + queueKey
		pCount, err := r.client.LLen(ctx, listKey).Result()
		if err != nil && err != redis.Nil {
			return 0, 0, err
		}
		fCount, err := r.client.ZCard(ctx, flightKey).Result()
		if err != nil && err != redis.Nil {
			return 0, 0, err
		}
		return pCount, fCount, nil
	}

	r.fallbackMu.Lock()
	defer r.fallbackMu.Unlock()
	r.ensureMemMaps(queueKey)
	return int64(len(r.memPending[queueKey])), int64(len(r.memInFlight[queueKey])), nil
}

func (r *RedisBuffer) ensureMemMaps(queueKey string) {
	if r.memEntries == nil {
		r.memEntries = make(map[string]map[string]*QueueEntry)
	}
	if r.memPending == nil {
		r.memPending = make(map[string][]string)
	}
	if r.memInFlight == nil {
		r.memInFlight = make(map[string]map[string]int64)
	}
	if _, ok := r.memEntries[queueKey]; !ok {
		r.memEntries[queueKey] = make(map[string]*QueueEntry)
	}
	if _, ok := r.memInFlight[queueKey]; !ok {
		r.memInFlight[queueKey] = make(map[string]int64)
	}
}

func (r *RedisBuffer) IsConnected() bool {
	return r.client != nil
}

func (r *RedisBuffer) IsDegraded() bool {
	return r.isDegraded
}

func (r *RedisBuffer) GetClient() *redis.Client {
	return r.client
}

func (r *RedisBuffer) Close() error {
	if r.client != nil {
		return r.client.Close()
	}
	return nil
}
