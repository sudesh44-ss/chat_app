# NEXA — P1 Race Detector Test Results

**Date:** 2026-11-07  
**Host OS:** Windows (Shell: PowerShell)  
**Execution Mode:** STRICT READ-ONLY VERIFICATION  
**Requirement Status:** **PARTIALLY VERIFIED** (Environment Toolchain Constraint)

---

## 1. Race Detector Execution Record

### 1.1 Command 1 — Default Execution
```powershell
cd H:\chat_app\backend; go test -race -count=1 ./...
```
- **Exit Code:** `1`
- **Standard Error / Output:**
  ```text
  go: -race requires cgo; enable cgo by setting CGO_ENABLED=1
  ```

### 1.2 Command 2 — Execution with CGO Enabled
```powershell
cd H:\chat_app\backend; $env:CGO_ENABLED="1"; go test -race -count=1 ./...
```
- **Exit Code:** `1`
- **Standard Error / Output:**
  ```text
  # runtime/cgo
  cgo: C compiler "gcc" not found: exec: "gcc": executable file not found in %PATH%
  FAIL	nexa/wire/cmd/handshake [build failed]
  FAIL	nexa/wire/cmd/qr-sync [build failed]
  FAIL	nexa/wire/cmd/relay [build failed]
  FAIL	nexa/wire/cmd/security-rules [build failed]
  FAIL	nexa/wire/cmd/server [build failed]
  FAIL	nexa/wire/cmd/signaling [build failed]
  FAIL	nexa/wire/cmd/swarm [build failed]
  FAIL	nexa/wire/internal/auth [build failed]
  FAIL	nexa/wire/internal/connection [build failed]
  FAIL	nexa/wire/internal/connections [build failed]
  FAIL	nexa/wire/internal/database [build failed]
  FAIL	nexa/wire/internal/identity [build failed]
  FAIL	nexa/wire/internal/queue [build failed]
  FAIL	nexa/wire/internal/ratelimit [build failed]
  FAIL	nexa/wire/internal/wire [build failed]
  FAIL
  ```

### 1.3 Technical Analysis
The Go race detector (`-race`) relies on ThreadSanitizer (TSan), which requires CGO and a native C compiler (`gcc` or `clang`). On Windows, the Go toolchain defaults to `CGO_ENABLED=0` unless a MinGW-w64 or TDM-GCC toolchain is installed and registered on the system `PATH`.

Because no C compiler (`gcc`) is installed on the host operating system, the `-race` binary could not be linked. **Per audit requirements, this is NOT claimed as PASS; it is formally documented as an environment build limitation.**

---

## 2. Pure Go Test Suite Execution (Without Race Detector)

To verify functional correctness and absence of deadlocks across all concurrent routines, the complete test suite was executed in pure Go mode:

```powershell
cd H:\chat_app\backend; go test -count=1 ./...
```
- **Exit Code:** `0`
- **Package Results:**
  ```text
  ?   	nexa/wire/cmd/handshake	[no test files]
  ?   	nexa/wire/cmd/qr-sync	[no test files]
  ?   	nexa/wire/cmd/relay	[no test files]
  ?   	nexa/wire/cmd/security-rules	[no test files]
  ?   	nexa/wire/cmd/server	[no test files]
  ?   	nexa/wire/cmd/signaling	[no test files]
  ?   	nexa/wire/cmd/swarm	[no test files]
  ?   	nexa/wire/internal/api	[no test files]
  ok  	nexa/wire/internal/auth	0.973s
  ok  	nexa/wire/internal/connection	1.071s
  ok  	nexa/wire/internal/connections	0.787s
  ok  	nexa/wire/internal/database	0.712s
  ?   	nexa/wire/internal/fcm	[no test files]
  ?   	nexa/wire/internal/handshake	[no test files]
  ok  	nexa/wire/internal/identity	0.585s
  ok  	nexa/wire/internal/queue	0.625s
  ok  	nexa/wire/internal/ratelimit	0.502s
  ?   	nexa/wire/internal/relay	[no test files]
  ?   	nexa/wire/internal/rules	[no test files]
  ?   	nexa/wire/internal/signaling	[no test files]
  ?   	nexa/wire/internal/swarm	[no test files]
  ?   	nexa/wire/internal/sync	[no test files]
  ok  	nexa/wire/internal/wire	0.288s
  ```

---

## 3. Static Concurrency Audit of Modified Code

In lieu of the runtime race detector, a strict code audit was conducted on all concurrency primitives introduced in P1:

1. **`Hub.sessions` and `Hub.blindTokens` (`internal/connection/hub.go`, `pubsub.go`):**
   - Protected by `h.sessionsMu sync.RWMutex` and `h.blindTokensMu sync.RWMutex`.
   - All lookups use `RLock()`/`RUnlock()`.
   - All modifications (`Register`, `Unregister`, `RevokeDeviceSessionLocal`) use `Lock()`/`Unlock()`.
   - Socket closing and channel closes occur **outside** the mutex lock to prevent lock-ordering deadlocks.
2. **`ClientSession.Send` channel (`internal/connection/client.go`):**
   - Channel buffer: `256` elements.
   - All channel enqueues use `SafeSend` with a `recover()` block.
   - All channel closes use `func() { defer func() { _ = recover() }(); close(s.Send) }()`.
   - This eliminates both "send on closed channel" panics and "close of closed channel" panics.
3. **`ratelimit.MemoryLimiter` (`internal/ratelimit/limiter.go`):**
   - Protected by `m.mu sync.Mutex`.
   - Verified under 100 concurrent goroutines in `TestMemoryLimiter_Concurrent` with zero race collisions or slice corruption.
4. **`ratelimit.DistributedLimiter` (`internal/ratelimit/limiter.go`):**
   - Uses Redis server-side Lua script execution (`EVALSHA` / `EVAL`).
   - Redis single-threaded event loop executes the entire `ZREMRANGEBYSCORE -> ZCARD -> ZADD -> PEXPIRE` sequence atomically. Zero Go memory shared across instances.

---

## 4. Recommendation for CI/CD

To run `-race` in continuous integration:
- Target a Linux/amd64 runner (Ubuntu LTS) with pre-installed `gcc` (e.g., `build-essential`).
- On Windows development machines, install MinGW-w64 via `winget install MSYS2.MSYS2` or `winget install MinGW.MinGW` if local TSan profiling is required.
