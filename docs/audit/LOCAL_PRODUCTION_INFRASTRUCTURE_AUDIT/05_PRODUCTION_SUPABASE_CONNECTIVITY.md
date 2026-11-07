# NEXA Infrastructure Audit — 05: Production Supabase Connectivity & IPv6 Resolution

**Subsystem:** Cloud Database Networking & DNS Routing  
**Service Provider:** Supabase PostgreSQL  
**Project ID:** `kiktbhwmwptzqtkrdcvb`  
**Direct Hostname:** `db.kiktbhwmwptzqtkrdcvb.supabase.co`  
**Observed Error:** `dial tcp [2406:da1a:314:7101:98e3:f6f5:5d7a:1157]:5432: connect: network is unreachable`  

---

## 1. The IPv6 Root Cause Explained

### 1.1 Supabase Free-Tier Direct Connection DNS
Supabase projects on the free and standard tiers use AWS infrastructure where direct database hostnames (`db.<project-ref>.supabase.co`) are assigned IPv6 addresses. 

When DNS resolution occurs:
- **DNS A Record (IPv4):** None returned for direct hostname on many cloud regions.
- **DNS AAAA Record (IPv6):** Returns `2406:da1a:314:7101:98e3:f6f5:5d7a:1157`.

### 1.2 The Render Incompatibility
Render web service containers do not have public IPv6 network routes configured. When the Go runtime executes `net.DialContext` against `db.kiktbhwmwptzqtkrdcvb.supabase.co:5432`:
1. The system resolver returns the IPv6 address `2406:da1a:314:7101:98e3:f6f5:5d7a:1157`.
2. The Go TCP socket attempts `connect()` on `[2406:da1a:314:7101:98e3:f6f5:5d7a:1157]:5432`.
3. The Linux network kernel checks the routing table (`ip -6 route`).
4. Because there is no default IPv6 gateway (`default via ...`), the kernel returns `ENETUNREACH` (`network is unreachable`).
5. `pgxpool.NewWithConfig` fails and returns `database ping failed: ... connect: network is unreachable`.

---

## 2. Direct Connection vs Supabase Connection Pooler (Supavisor)

Supabase officially provides the **Supabase Connection Pooler (Supavisor)** to solve IPv4 network compatibility for platforms such as Render, AWS Lambda, and Vercel.

```
+-------------------------------------------------------------------------------------------------+
|                               SUPABASE CONNECTION COMPARISON                                    |
+------------------------------+----------------------------------+-------------------------------+
| ATTRIBUTE                    | DIRECT CONNECTION                | SUPABISOR CONNECTION POOLER   |
+------------------------------+----------------------------------+-------------------------------+
| Hostname Pattern             | db.<project-ref>.supabase.co     | aws-0-<region>.pooler.supabase.com |
| IP Protocol Support          | IPv6 Only (Free/Standard tiers)  | Dual-Stack (IPv4 & IPv6)      |
| Render Compatibility         | INCOMPATIBLE (ENETUNREACH)       | FULLY COMPATIBLE (IPv4)       |
| Supported Modes              | Direct connection (port 5432)    | Session Mode (5432) / Tx (6543)|
| Prepared Statements Support  | YES                              | YES (Session Mode on 5432)    |
| Schema Migrations Support    | YES                              | YES (Session Mode on 5432)    |
| Username Format              | postgres                         | postgres.<project-ref>        |
+------------------------------+----------------------------------+-------------------------------+
```

---

## 3. Required Port & Pooling Mode Selection for NEXA

Supavisor offers two operational modes:
1. **Transaction Mode (Port 6543):**
   - Connections are returned to the pool after each transaction.
   - **DOES NOT** support prepared statements or advisory locks.
   - Incompatible with `pgx` prepared query caching and DDL migrations.
2. **Session Mode (Port 5432):**
   - Each client holds an isolated session connection.
   - **FULL SUPPORT** for prepared statements, advisory locks, and DDL migrations.
   - **MANDATORY FOR NEXA BACKEND.**

---

## 4. The Render Solution

In the Render Dashboard Environment settings for the NEXA service, update `DATABASE_URL`:

```env
# Change FROM (Direct IPv6 Hostname):
DATABASE_URL=postgres://postgres:[PASSWORD]@db.kiktbhwmwptzqtkrdcvb.supabase.co:5432/postgres?sslmode=require

# TO (Supabase Pooler IPv4 Hostname - Session Mode):
DATABASE_URL=postgres://postgres.kiktbhwmwptzqtkrdcvb:[PASSWORD]@aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require
```

*(Note: Verify exact pooler region in Supabase Dashboard -> Project Settings -> Database -> Connection Pooling).*

### Why this fixes the issue:
1. `aws-0-*.pooler.supabase.com` resolves to a public IPv4 address (`A` record).
2. Render's IPv4 container network routes the connection successfully.
3. Session mode on port 5432 allows Go's `pgxpool` and `RunMigrations` to run seamlessly.
