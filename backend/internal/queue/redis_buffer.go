package queue

import (
	"context"
	"fmt"
	"log"
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
	Close() error
}

// RedisBuffer manages the zero-disk ephemeral buffer backed by Redis.
type RedisBuffer struct {
	client *redis.Client

	// In-memory fallback if Redis is not configured or offline
	fallbackMu sync.Mutex
	fallback   map[string][][]byte
}

func NewRedisBuffer(redisAddr, password string) (*RedisBuffer, error) {
	rb := &RedisBuffer{
		fallback: make(map[string][][]byte),
	}

	if redisAddr == "" {
		log.Println("[Queue] No Redis address configured. Operating with zero-disk RAM fallback.")
		return rb, nil
	}

	opts := &redis.Options{
		Addr:     redisAddr,
		Password: password,
		DB:       0,
	}

	client := redis.NewClient(opts)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		log.Printf("[Queue] Redis connection failed at %s (%v). Falling back to in-memory RAM queue.", redisAddr, err)
		return rb, nil
	}

	rb.client = client
	log.Printf("[Queue] Connected to ephemeral Redis at %s (TTL: %v)", redisAddr, DefaultTTL)
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

func (r *RedisBuffer) Close() error {
	if r.client != nil {
		return r.client.Close()
	}
	return nil
}
