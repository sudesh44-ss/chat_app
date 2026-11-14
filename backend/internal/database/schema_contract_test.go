package database

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"
)

// The Go runtime migrations in migrations.go are the short-term authoritative
// schema. The SQL files under database/migrations are the long-term contract
// artifact and are currently divergent. This test pins that divergence instead
// of pretending the two are identical, so any accidental schema drift shows up
// as a failing test rather than a production surprise.
//
// When the two sources are reconciled, shrink the allowlists below and delete
// the pinned assertions. Do not silently widen them.

var createTableRE = regexp.MustCompile(`(?i)CREATE TABLE(?: IF NOT EXISTS)?\s+(?:public\.)?([a-z_][a-z0-9_]*)([^\n]*)`)

// collectTables extracts real table creations, ignoring partition children
// declared with "CREATE TABLE ... PARTITION OF".
func collectTables(src string) map[string]bool {
	tables := make(map[string]bool)
	for _, match := range createTableRE.FindAllStringSubmatch(src, -1) {
		if strings.Contains(strings.ToUpper(match[2]), "PARTITION OF") {
			continue
		}
		tables[match[1]] = true
	}
	return tables
}

// runtimeOnlyTables exist in the Go runtime migrations but not in the SQL
// contract files yet.
var runtimeOnlyTables = map[string]bool{
	"accounts":            true,
	"sessions":            true,
	"connection_requests": true,
	"connections":         true,
	"user_blocks":         true,
	"message_idempotency": true,
	"device_events":       true,
}

// sqlOnlyTables exist in the SQL contract files but are not created by the Go
// runtime migrations (they are legacy Supabase-shaped tables or newer
// partitioned telemetry).
var sqlOnlyTables = map[string]bool{
	"profiles":              true,
	"nx_identities":         true,
	"contacts":              true,
	"identity_keys":         true,
	"prekeys":               true,
	"message_metadata":      true,
	"device_sessions":       true,
	"user_telemetry_events": true,
}

func TestSchemaContract_RuntimeAndSQLMigrations(t *testing.T) {
	sqlDir := filepath.Join("..", "..", "..", "database", "migrations")
	entries, err := os.ReadDir(sqlDir)
	if err != nil {
		if os.IsNotExist(err) {
			t.Skipf("SQL migration contract directory not found at %s", sqlDir)
		}
		t.Fatalf("failed to read SQL migration directory: %v", err)
	}

	sqlTables := make(map[string]bool)
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		path := filepath.Join(sqlDir, entry.Name())
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("failed to read %s: %v", entry.Name(), err)
		}
		for table := range collectTables(string(data)) {
			sqlTables[table] = true
		}
	}
	if len(sqlTables) == 0 {
		t.Fatal("parser found no CREATE TABLE statements in SQL migrations")
	}

	runtimeTables := make(map[string]bool)
	for _, m := range migrations {
		for table := range collectTables(m.SQL) {
			runtimeTables[table] = true
		}
	}
	if len(runtimeTables) == 0 {
		t.Fatal("parser found no CREATE TABLE statements in Go runtime migrations")
	}

	// Every runtime table must be either present in the SQL contract or
	// explicitly allowlisted as a known gap.
	var undocumentedRuntime []string
	for table := range runtimeTables {
		if !sqlTables[table] && !runtimeOnlyTables[table] {
			undocumentedRuntime = append(undocumentedRuntime, table)
		}
	}
	sort.Strings(undocumentedRuntime)
	if len(undocumentedRuntime) > 0 {
		t.Fatalf("runtime tables missing from SQL migrations and not allowlisted: %v", undocumentedRuntime)
	}

	var undocumentedSQL []string
	for table := range sqlTables {
		if !runtimeTables[table] && !sqlOnlyTables[table] {
			undocumentedSQL = append(undocumentedSQL, table)
		}
	}
	sort.Strings(undocumentedSQL)
	if len(undocumentedSQL) > 0 {
		t.Fatalf("SQL-only tables not allowlisted in contract test: %v", undocumentedSQL)
	}

	// Pin the most dangerous divergence: the two schemas describe device
	// ownership with different columns and key types.
	deviceSQL := mustReadFile(t, filepath.Join(sqlDir, "002_create_devices.sql"))
	if !strings.Contains(deviceSQL, "user_id UUID") {
		t.Errorf("pinned divergence changed: SQL devices.user_id UUID not found; update the contract test deliberately")
	}
	runtimeDevices := runtimeTableSQL(t, "devices")
	if !strings.Contains(runtimeDevices, "account_id") || !strings.Contains(runtimeDevices, "nx_id") {
		t.Errorf("pinned divergence changed: runtime devices.account_id/nx_id not found; update the contract test deliberately")
	}
}

func runtimeTableSQL(t *testing.T, table string) string {
	t.Helper()
	for _, m := range migrations {
		if strings.Contains(m.SQL, "CREATE TABLE IF NOT EXISTS "+table) {
			return m.SQL
		}
	}
	t.Fatalf("runtime migration for table %s not found", table)
	return ""
}

func mustReadFile(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("failed to read %s: %v", path, err)
	}
	return string(data)
}
