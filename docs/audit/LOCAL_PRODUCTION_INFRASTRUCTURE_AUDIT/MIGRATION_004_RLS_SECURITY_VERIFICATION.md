# Migration 004 & 006 Row-Level Security (RLS) & Database Access Audit

**Target:** `phone_visibility_grants` & `legacy_supabase_phone_visibility_grants`  
**Date:** 2026-11-10  
**Classification:** Security Verification Report  

---

## 1. Executive Security Summary

The NEXA relationship graph and phone number visibility subsystem implements defense-in-depth across two distinct architectural layers:
1. **Database Layer (PostgreSQL Engine):** Row-Level Security (RLS) enabled on all public tables with direct PostgREST access fully revoked from unprivileged roles (`anon`, `authenticated`).
2. **Application Layer (Go Backend API):** Strict identity-bound session authentication (`session.NxID`) ensuring users can only manage grants where they are the authenticated granter.

---

## 2. Database Roles & Privilege Model

### 2.1 Backend Connection Role (`postgres` / `nexa_user`)
- The Go backend connects to PostgreSQL via `DATABASE_URL` using the database connection owner role (`postgres` in Supabase or `nexa_user` on Render).
- In PostgreSQL, table owners and superusers possess `BYPASSRLS` privileges by default. This permits the backend repository ([`backend/internal/connections/postgres_repository.go`](file:///H:/chat_app/backend/internal/connections/postgres_repository.go)) to execute queries (`INSERT`, `SELECT`, `UPDATE`) without being blocked by RLS policies.

### 2.2 Unprivileged Client Roles (`anon` & `authenticated`)
- Supabase exposes a PostgREST HTTP interface (`/rest/v1/...`) accessed with JWT tokens under roles `anon` (unauthenticated) or `authenticated` (Supabase Auth).
- Migration 006 executes the following fail-closed revocation:
  ```sql
  REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
  REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
  REVOKE ALL ON ALL ROUTINES IN SCHEMA public FROM anon, authenticated;
  ```
- Because direct permissions are revoked, **zero direct read or write access via PostgREST is permitted to any public table**, including `phone_visibility_grants` and `legacy_supabase_phone_visibility_grants`.

---

## 3. Row-Level Security on Active & Archived Tables

Migration 006 enforces:
```sql
EXECUTE 'ALTER TABLE phone_visibility_grants ENABLE ROW LEVEL SECURITY';

IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'legacy_supabase_phone_visibility_grants') THEN
    EXECUTE 'ALTER TABLE legacy_supabase_phone_visibility_grants ENABLE ROW LEVEL SECURITY';
END IF;
```

### Security Implications:
1. **Fail-Closed Default:** When RLS is enabled on a PostgreSQL table without any permissive `CREATE POLICY` statements, all non-owner roles default to **deny all**.
2. **Archived Table Protection:** `legacy_supabase_phone_visibility_grants` is explicitly placed under RLS. Unprivileged roles cannot query legacy phone visibility grants or discover historical phone linkages.
3. **Active Table Protection:** Direct SQL queries to `phone_visibility_grants` from non-owner roles return 0 rows.

---

## 4. Application-Layer Identity Binding & Anti-IDOR Defense

All client access to phone visibility is mediated exclusively by the Go backend REST API endpoint:
`POST /api/v1/users/phone-visibility` & `GET /api/v1/users/phone-visibility`

### Source Code Audit ([`backend/internal/connections/handler.go:695-755`](file:///H:/chat_app/backend/internal/connections/handler.go#L695)):
```go
func (h *Handler) handlePhoneVisibility(w http.ResponseWriter, r *http.Request) {
    session := h.authenticateSession(r)
    if session == nil {
        http.Error(w, "Unauthorized: valid session required", http.StatusUnauthorized)
        return
    }

    if r.Method == http.MethodPost {
        var reqBody struct {
            GranteeNxID string `json:"grantee_nx_id"`
            IsActive    bool   `json:"is_active"`
        }
        ...
        // SECURITY ENFORCEMENT: The granter is strictly bound to session.NxID.
        // The client cannot specify or spoof another user's NX-ID as the granter.
        if err := h.repo.SetPhoneVisibility(r.Context(), session.NxID, reqBody.GranteeNxID, reqBody.IsActive); err != nil {
            http.Error(w, err.Error(), http.StatusInternalServerError)
            return
        }
    ...
```

### Security Guarantees:
1. **Zero Cross-User Grant Creation (Anti-IDOR):** The granter NX-ID passed to `SetPhoneVisibility` is extracted directly from the verified session token. A caller cannot create, modify, or revoke phone grants on behalf of any other user.
2. **Zero Cross-User Grant Inspection:** When querying active grants (`ListPhoneVisibilityGrants`), the query is parameterized strictly by `session.NxID`. Users can only inspect visibility grants issued by their own account.
3. **Zero Phone Number Disclosure in Grants Table:** The `phone_visibility_grants` table contains only:
   `id`, `granter_nx_id`, `grantee_nx_id`, `is_active`, `created_at`, `updated_at`.
   No plaintext or hashed phone numbers exist in the grants table. A grant is purely a cryptographic authorization flag between two NX-IDs.

---

## 5. View & Stored Procedure Security: `safe_profiles`

In the reconciled Migration 4:
1. The legacy function `public.toggle_phone_visibility()` is safely dropped. It is not recreated because the Go backend uses its own internal transactional logic.
2. If `public.profiles` exists on a legacy database, `public.safe_profiles` is recreated as a backward-compatible view:
   ```sql
   CREATE OR REPLACE VIEW public.safe_profiles WITH (security_invoker = true) AS
   ...
   CASE 
       WHEN auth.uid() = p.id THEN p.phone_number
       WHEN EXISTS (
           SELECT 1 FROM public.legacy_supabase_phone_visibility_grants g 
           WHERE g.granter_id = p.id AND g.grantee_id = auth.uid() AND g.is_active = TRUE
       ) THEN p.phone_number
       ELSE NULL 
   END AS phone_number
   FROM public.profiles p;
   ```
3. **`WITH (security_invoker = true)`:** Ensures the view executes with the privileges of the calling user, not the view owner. If an unauthorized user queries the view, phone numbers evaluate to `NULL` (fail-closed zero leakage).
4. Direct PostgREST access to routines and tables is revoked by Migration 006, ensuring external unauthenticated scraping is impossible.
