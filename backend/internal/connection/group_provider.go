package connection

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// GroupMembershipProvider defines operations for group authorization and fan-out.
type GroupMembershipProvider interface {
	GetGroupMembers(ctx context.Context, groupId string) ([]string, error)
	IsMember(ctx context.Context, groupId string, nxId string) (bool, error)
	AddMember(ctx context.Context, groupId, nxId, role string) error
	RemoveMember(ctx context.Context, groupId, nxId string) error
}

// InMemoryGroupProvider provides a thread-safe in-memory group membership store for tests and standalone relay.
type InMemoryGroupProvider struct {
	mu      sync.RWMutex
	members map[string]map[string]string // groupId -> nxId -> role
}

func NewInMemoryGroupProvider() *InMemoryGroupProvider {
	return &InMemoryGroupProvider{
		members: make(map[string]map[string]string),
	}
}

func (p *InMemoryGroupProvider) GetGroupMembers(ctx context.Context, groupId string) ([]string, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	groupMap, exists := p.members[groupId]
	if !exists {
		return nil, fmt.Errorf("group %s not found", groupId)
	}

	result := make([]string, 0, len(groupMap))
	for member := range groupMap {
		result = append(result, member)
	}
	return result, nil
}

func (p *InMemoryGroupProvider) IsMember(ctx context.Context, groupId string, nxId string) (bool, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	groupMap, exists := p.members[groupId]
	if !exists {
		return false, nil
	}
	_, found := groupMap[nxId]
	return found, nil
}

func (p *InMemoryGroupProvider) AddMember(ctx context.Context, groupId, nxId, role string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if _, exists := p.members[groupId]; !exists {
		p.members[groupId] = make(map[string]string)
	}
	p.members[groupId][nxId] = role
	return nil
}

func (p *InMemoryGroupProvider) RemoveMember(ctx context.Context, groupId, nxId string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if groupMap, exists := p.members[groupId]; exists {
		delete(groupMap, nxId)
	}
	return nil
}

// RedisGroupProvider wraps Redis caching for scalable group lookups.
type RedisGroupProvider struct {
	client   *redis.Client
	fallback GroupMembershipProvider
	ttl      time.Duration
}

func NewRedisGroupProvider(client *redis.Client, fallback GroupMembershipProvider) *RedisGroupProvider {
	return &RedisGroupProvider{
		client:   client,
		fallback: fallback,
		ttl:      1 * time.Hour,
	}
}

func (p *RedisGroupProvider) GetGroupMembers(ctx context.Context, groupId string) ([]string, error) {
	if p.client != nil {
		key := "group:" + groupId + ":members"
		members, err := p.client.SMembers(ctx, key).Result()
		if err == nil && len(members) > 0 {
			return members, nil
		}
	}

	if p.fallback != nil {
		members, err := p.fallback.GetGroupMembers(ctx, groupId)
		if err == nil && len(members) > 0 && p.client != nil {
			key := "group:" + groupId + ":members"
			pipe := p.client.Pipeline()
			for _, m := range members {
				pipe.SAdd(ctx, key, m)
			}
			pipe.Expire(ctx, key, p.ttl)
			_, _ = pipe.Exec(ctx)
		}
		return members, err
	}

	return nil, fmt.Errorf("group %s members unavailable", groupId)
}

func (p *RedisGroupProvider) IsMember(ctx context.Context, groupId string, nxId string) (bool, error) {
	if p.client != nil {
		key := "group:" + groupId + ":members"
		isMember, err := p.client.SIsMember(ctx, key, nxId).Result()
		if err == nil {
			return isMember, nil
		}
	}

	if p.fallback != nil {
		return p.fallback.IsMember(ctx, groupId, nxId)
	}
	return false, nil
}

func (p *RedisGroupProvider) AddMember(ctx context.Context, groupId, nxId, role string) error {
	if p.client != nil {
		key := "group:" + groupId + ":members"
		p.client.SAdd(ctx, key, nxId)
		p.client.Expire(ctx, key, p.ttl)
	}
	if p.fallback != nil {
		return p.fallback.AddMember(ctx, groupId, nxId, role)
	}
	return nil
}

func (p *RedisGroupProvider) RemoveMember(ctx context.Context, groupId, nxId string) error {
	if p.client != nil {
		key := "group:" + groupId + ":members"
		p.client.SRem(ctx, key, nxId)
	}
	if p.fallback != nil {
		return p.fallback.RemoveMember(ctx, groupId, nxId)
	}
	return nil
}
