# NEXA — Contact Discovery & Address Book Privacy Trace Report

## 1. Complete Network Trace of Contact Lookup

To verify that NEXA adheres to its zero-knowledge address-book policy, the complete network request and backend execution path were traced:

### A. Client Request Assembly (`app/src/main/java/com/nexa/app/data/repository/ContactRepositoryImpl.kt`):
```kotlin
// 1. Read device contacts locally via ContentResolver
val localContacts = ContactsProviderHelper.readDeviceContacts(context)

// 2. Extract ONLY normalized phone numbers
val phoneNumbers = localContacts.map { it.normalizedNumber }

// 3. Dispatch batch lookup request DTO
val request = ContactLookupRequest(phoneNumbers = phoneNumbers)
```

### B. Wire Transmission Payload (`POST /api/v1/contacts/lookup`):
```json
{
  "phone_numbers": [
    "+919876543210",
    "+919876543211",
    "+14155552671"
  ]
}
```

---

## 2. Privacy Guarantees Audit Table

| Privacy Constraint | Verification Finding | Status |
| :--- | :--- | :---: |
| **Contact Names Never Sent** | The request DTO `ContactLookupRequest` only contains `phone_numbers: []string`. Names, emails, notes, and avatars are never transmitted. | **VERIFIED (PASS)** |
| **No Cloud Address Book Storage** | Neither the Supabase database migrations (`001_` to `014_`) nor the Go server maintain a table or store for address books. | **VERIFIED (PASS)** |
| **Unmatched Numbers Not Stored** | Non-matching numbers are dropped immediately in memory; no table or file writes occur for non-matches. | **VERIFIED (PASS)** |
| **Lookup Requests Not Persisted** | The request payload is processed in-memory during the HTTP handler execution and garbage-collected immediately. | **VERIFIED (PASS)** |
| **Zero Plaintext Number Logging** | Backend loggers do NOT print phone numbers in server logs. | **VERIFIED (PASS)** |
| **Redis Does Not Retain Book** | Redis is ephemeral and used only for WebSocket session mapping; address-book entries are never pushed to Redis. | **VERIFIED (PASS)** |
| **Only Verified Accounts Returned** | Accounts must have `PhoneVerified = true` and `IsDiscoverableByPhone = true` to be included in results. | **VERIFIED (PASS)** |
| **Network Errors Handled Safely** | Network failures retain cached contact statuses with an offline indicator rather than marking contacts as unregistered. | **VERIFIED (PASS)** |
