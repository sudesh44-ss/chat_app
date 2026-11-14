# Migration Concurrency & Transactional Integrity Report

**Target:** `backend/internal/database/migrations.go` (`RunMigrations`)  
**Date:** 2026-11-10  
**Classification:** Concurrency & Transactional Safety Verification  

---

## 1. Concurrency Vulnerability Analysis (Before Remediation)

Prior to this remediation, `RunMigrations` implemented transactional execution for individual migrations, but lacked cluster-wide synchronization:
- If a horizontal autoscaling event occurred on Render, or if multiple web service replicas booted simultaneously, both instances could enter `RunMigrations` concurrently.
- Replicas would race to check `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = $1)`. Both finding version 4 unapplied, both would execute DDL simultaneously.
- This could lead to duplicate key errors on `schema_migrations`, DDL deadlocks, or partial table alterations.

---

## 2. Session Advisory Lock Implementation

To eliminate race conditions across multiple application instances, `RunMigrations` was updated to acquire a dedicated PostgreSQL session-level advisory lock before inspecting or applying migrations.

### Reconciled Source Code ([`backend/internal/database/migrations.go:411-470`](file:///H:/chat_app/backend/internal/database/migrations.go#L411)):
```go
func RunMigrations(ctx context.Context, pool *pgxpool.Pool) error {
	// 1. Ensure schema_migrations tracking table exists
	createTrackingTableSQL := `
	CREATE TABLE IF NOT EXISTS schema_migrations (
		version INT PRIMARY KEY,
		name VARCHAR(255) NOT NULL,
		applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
	);`
	if _, err := pool.Exec(ctx, createTrackingTableSQL); err != nil {
		return fmt.Errorf("failed to create schema_migrations table: %w", err)
	}

	// 2. Acquire PostgreSQL session advisory lock to ensure serialized migration execution across replicas
	const migrationAdvisoryLockID int64 = 8472910384729102 // unique 64-bit int for NEXA migration lock
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("failed to acquire connection for migration advisory lock: %w", err)
	}
	defer conn.Release()

	if _, err := conn.Exec(ctx, "SELECT pg_advisory_lock($1)", migrationAdvisoryLockID); err != nil {
		return fmt.Errorf("failed to acquire migration advisory lock: %w", err)
	}
	defer func() {
		// Release advisory lock upon exit
		_, _ = conn.Exec(context.Background(), "SELECT pg_advisory_unlock($1)", migrationAdvisoryLockID)
	}()

	// 3. Iterate and apply pending migrations using the locked connection
	for _, m := range migrations {
		var exists bool
		checkSQL := `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = $1)`
		if err := conn.QueryRow(ctx, checkSQL, m.Version).Scan(&exists); err != nil {
			return fmt.Errorf("failed to check migration status for version %d: %w", m.Version, err)
		}

		if exists {
			continue
		}

		log.Printf("[Database] Applying migration %d: %s...", m.Version, m.Name)

		tx, err := conn.Begin(ctx)
		if err != nil {
			return fmt.Errorf("failed to begin transaction for migration %d: %w", m.Version, err)
		}

		if _, err := tx.Exec(ctx, m.SQL); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("migration %d (%s) failed: %w", m.Version, m.Name, err)
		}

		recordSQL := `INSERT INTO schema_migrations (version, name) VALUES ($1, $2)`
		if _, err := tx.Exec(ctx, recordSQL, m.Version, m.Name); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("failed to record migration %d: %w", m.Version, err)
		}

		if err := tx.Commit(ctx); err != nil {
			return fmt.Errorf("failed to commit migration %d: %w", m.Version, err)
		}

		log.Printf("[Database] Migration %d (%s) applied successfully.", m.Version, m.Name)
	}

	return nil
}
```

---

## 3. Concurrency Guarantees & Lifecycle

1. **Serialized Execution:**
   - When Instance 1 acquires connection `conn` and calls `pg_advisory_lock(8472910384729102)`, any concurrent Instance 2 calling `pg_advisory_lock` will block and wait until Instance 1 releases the lock.
2. **Safe Automatic Lock Release:**
   - The lock is released via `pg_advisory_unlock` in a deferred function.
   - If the application process crashes or is killed by Render (`SIGKILL`), PostgreSQL automatically closes the TCP connection, which immediately releases the session-level advisory lock. No orphaned locks can permanently block subsequent deploys.
3. **Dedicated Connection Binding:**
   - `pool.Acquire(ctx)` guarantees that the advisory lock is held on the exact same physical database connection that executes the migration status checks and transactions.
4. **Clean Pass-Through for Waiting Replicas:**
   - When Instance 2 unblocks after Instance 1 finishes, it queries `schema_migrations`. Because Instance 1 committed versions 4, 5, and 6, Instance 2 observes `exists = true` for all versions and exits immediately without executing any duplicate DDL.

---

## 4. Transactional Atomic Rollback Guarantees

1. **Per-Migration Atomic Transactions:**
   - Every individual migration executes inside an explicit transaction: `tx, err := conn.Begin(ctx)`.
2. **Atomic Rollback on Failure:**
   - If any SQL statement fails (e.g. `SQLSTATE 42P07`), `_ = tx.Rollback(ctx)` executes.
   - In PostgreSQL, DDL is fully transactional. Table renames, index creations, and constraint definitions are reverted completely.
3. **Untouched Migration History:**
   - The version record `INSERT INTO schema_migrations (version, name)` executes inside the transaction **after** the migration SQL succeeds.
   - If the DDL fails, the record is rolled back with the transaction. Migration history never incorrectly records a failed migration as applied.
4. **Historical Migrations Untouched:**
   - Already-applied versions 1, 2, and 3 are never re-executed or modified.
