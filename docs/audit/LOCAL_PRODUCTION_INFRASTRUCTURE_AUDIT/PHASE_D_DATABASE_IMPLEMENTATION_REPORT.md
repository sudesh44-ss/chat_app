# PHASE D — DATABASE IMPLEMENTATION REPORT

## 1. Schema DDL & Migration Overview

Migration 2 (`002_create_connections_schema`) was added to `backend/internal/database/migrations.go`. It executes in a single atomic transaction and creates three primary relational tables:
1. `connection_requests`: Tracks inbound/outbound invitations, intro messages, TTL expiration, and transition states.
2. `connections`: Authoritative bidirectional relationship registry using canonical user ordering.
3. `user_blocks`: Authoritative blocklist registry preventing communication, discovery, and requests.

---

## 2. Table Definitions & Integrity Constraints

### 2.1 Table: `connection_requests`

```sql
CREATE TABLE IF NOT EXISTS connection_requests (
    id VARCHAR(64) PRIMARY KEY,
    sender_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    recipient_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    intro_message TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_request_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_no_self_request CHECK (sender_nx_id != recipient_nx_id)
);
```

#### Indexes:
- `CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_pending_request ON connection_requests (sender_nx_id, recipient_nx_id) WHERE status = 'PENDING';`
  - Guarantees at most one active pending request from User A to User B at any given moment.
- `CREATE INDEX IF NOT EXISTS idx_conn_requests_recipient ON connection_requests (recipient_nx_id, status);`
  - Optimizes incoming request inbox queries with pagination.
- `CREATE INDEX IF NOT EXISTS idx_conn_requests_sender ON connection_requests (sender_nx_id, status);`
  - Optimizes outgoing sent request queries with pagination.
- `CREATE INDEX IF NOT EXISTS idx_conn_requests_expires ON connection_requests (expires_at) WHERE status = 'PENDING';`
  - Optimizes background cleanup or query-time TTL filtering of expired requests (default TTL: 7 days).

---

### 2.2 Table: `connections`

```sql
CREATE TABLE IF NOT EXISTS connections (
    id VARCHAR(64) PRIMARY KEY,
    user_a_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    user_b_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    established_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    removed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT chk_canonical_users CHECK (user_a_nx_id < user_b_nx_id),
    CONSTRAINT chk_connection_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT uq_connection_pair UNIQUE (user_a_nx_id, user_b_nx_id)
);
```

#### Canonical Ordering & Duplicate Prevention:
- **Constraint `chk_canonical_users CHECK (user_a_nx_id < user_b_nx_id)`:**
  By strictly requiring `user_a_nx_id` to be lexicographically smaller than `user_b_nx_id`, every bidirectional relationship has a single unique canonical representation in the database.
- **Constraint `uq_connection_pair UNIQUE (user_a_nx_id, user_b_nx_id)`:**
  Ensures that duplicate rows (such as inserting `(Alice, Bob)` and `(Bob, Alice)`) cannot be created, preventing race conditions and double-counting of contacts.

#### Indexes:
- `CREATE INDEX IF NOT EXISTS idx_connections_user_a ON connections (user_a_nx_id, status);`
- `CREATE INDEX IF NOT EXISTS idx_connections_user_b ON connections (user_b_nx_id, status);`
  - Optimizes connection queries from either user's perspective.

---

### 2.3 Table: `user_blocks`

```sql
CREATE TABLE IF NOT EXISTS user_blocks (
    blocker_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    blocked_nx_id VARCHAR(32) NOT NULL REFERENCES accounts(nx_id) ON DELETE CASCADE,
    reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (blocker_nx_id, blocked_nx_id),
    CONSTRAINT chk_no_self_block CHECK (blocker_nx_id != blocked_nx_id)
);
```

#### Indexes:
- `CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked ON user_blocks (blocked_nx_id);`
  - Optimizes inverse block lookups (e.g., verifying if the discovery caller has been blocked by the target user).

---

## 3. Migration Runner Verification

The migration runner in `backend/internal/database/migrations.go` applies schema changes sequentially using `schema_migrations`.
Schema integrity verification tests in `backend/internal/database/database_test.go` validated:
1. Migration 1 applied cleanly creating `accounts`, `sessions`, `devices`.
2. Migration 2 applied cleanly creating `connection_requests`, `connections`, `user_blocks`.
3. Check constraints enforce `sender_nx_id != recipient_nx_id` and `user_a_nx_id < user_b_nx_id`.
4. Foreign key cascades enforce account removal cleanup.

Test execution status: `PASS` (0.962s).
