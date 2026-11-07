package queue

import (
	"context"
	"fmt"
	"log"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	PendingKeyPrefix = "pending:"
	DefaultTTL       = 24 * time.Hour // 86400s ephemeral retention
)

type Buffer interface {
	PushPending(ctx context.Context, recipientNxId string, packetBytes []byte) error
	PopAllPending(ctx context.Context, recipientNxId string) ([][]byte, error)
	IsConnected() bool
	IsDegraded() bool
	GetClient() *redis.Client
	Close() error
}

// RedisBuffer manages the zero-disk ephemeral buffer backed by Redis.
type RedisBuffer struct {
	client     *redis.Client
	isDegraded bool

	// In-memory fallback if Redis is not configured or offline
	fallbackMu sync.Mutex
	fallback   map[string][][]byte
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
		fallback:   make(map[string][][]byte),
		isDegraded: false,
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

func (r *RedisBuffer) PushPending(ctx context.Context, recipientNxId string, packetBytes []byte) error {
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
			// Ephemeral delete: memory is immediately released from Redis
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
