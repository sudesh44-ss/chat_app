package database

import (
	"context"
	"fmt"
	"log"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Config defines connection pool settings for PostgreSQL.
type Config struct {
	DatabaseURL        string
	MaxConns           int32
	MinConns           int32
	MaxConnLifetime    time.Duration
	MaxConnIdleTime    time.Duration
	HealthCheckPeriod  time.Duration
	ConnectTimeout     time.Duration
}

// DefaultConfig returns production-tuned pool defaults.
func DefaultConfig(databaseURL string) Config {
	return Config{
		DatabaseURL:       databaseURL,
		MaxConns:          25,
		MinConns:          5,
		MaxConnLifetime:   1 * time.Hour,
		MaxConnIdleTime:   30 * time.Minute,
		HealthCheckPeriod: 1 * time.Minute,
		ConnectTimeout:    5 * time.Second,
	}
}

// NewPool initializes a managed PostgreSQL connection pool using pgxpool.
func NewPool(ctx context.Context, cfg Config) (*pgxpool.Pool, error) {
	if cfg.DatabaseURL == "" {
		return nil, fmt.Errorf("database URL is required")
	}

	poolConfig, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return nil, fmt.Errorf("failed to parse database URL: %w", err)
	}

	if cfg.MaxConns > 0 {
		poolConfig.MaxConns = cfg.MaxConns
	}
	if cfg.MinConns > 0 {
		poolConfig.MinConns = cfg.MinConns
	}
	if cfg.MaxConnLifetime > 0 {
		poolConfig.MaxConnLifetime = cfg.MaxConnLifetime
	}
	if cfg.MaxConnIdleTime > 0 {
		poolConfig.MaxConnIdleTime = cfg.MaxConnIdleTime
	}
	if cfg.HealthCheckPeriod > 0 {
		poolConfig.HealthCheckPeriod = cfg.HealthCheckPeriod
	}

	// Connect with bounded timeout
	connectCtx, cancel := context.WithTimeout(ctx, cfg.ConnectTimeout)
	defer cancel()

	pool, err := pgxpool.NewWithConfig(connectCtx, poolConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to initialize connection pool: %w", err)
	}

	// Verify connectivity
	if err := pool.Ping(connectCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("database ping failed: %w", err)
	}

	sanitizedURL := SanitizeDatabaseURL(cfg.DatabaseURL)
	log.Printf("[Database] Connected to PostgreSQL at %s (MaxConns: %d, MinConns: %d)",
		sanitizedURL, poolConfig.MaxConns, poolConfig.MinConns)

	return pool, nil
}

var dsnPasswordRegex = regexp.MustCompile(`(?i)(password=)[^\s]+`)

// SanitizeDatabaseURL strips sensitive passwords from connection strings and URLs for safe logging.
func SanitizeDatabaseURL(rawURL string) string {
	if strings.Contains(rawURL, "://") {
		u, err := url.Parse(rawURL)
		if err == nil {
			return u.Redacted()
		}
	}

	// Key-value DSN format or non-URL fallback
	sanitized := dsnPasswordRegex.ReplaceAllString(rawURL, "${1}xxxxx")
	if strings.Contains(sanitized, "@") {
		parts := strings.Split(sanitized, "@")
		if len(parts) == 2 {
			return "...@" + parts[1]
		}
	}
	return sanitized
}
