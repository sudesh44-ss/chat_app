package swarm

import (
	"sync"
)

// SwarmCatalog maintains an ephemeral, in-memory seeder lookup table.
// Zero data is written to disk.
type SwarmCatalog struct {
	mu      sync.RWMutex
	seeders map[string]map[string]bool // fileHash -> set of peer IDs
}

func NewSwarmCatalog() *SwarmCatalog {
	return &SwarmCatalog{
		seeders: make(map[string]map[string]bool),
	}
}

func (c *SwarmCatalog) AddSeeder(fileHash string, peerId string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	peers, exists := c.seeders[fileHash]
	if !exists {
		peers = make(map[string]bool)
		c.seeders[fileHash] = peers
	}
	peers[peerId] = true
}

func (c *SwarmCatalog) GetSeeders(fileHash string) []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	peers, exists := c.seeders[fileHash]
	if !exists {
		return []string{}
	}

	result := make([]string, 0, len(peers))
	for peerId := range peers {
		result = append(result, peerId)
	}
	return result
}

func (c *SwarmCatalog) RemovePeer(peerId string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	for fileHash, peers := range c.seeders {
		delete(peers, peerId)
		if len(peers) == 0 {
			delete(c.seeders, fileHash)
		}
	}
}
