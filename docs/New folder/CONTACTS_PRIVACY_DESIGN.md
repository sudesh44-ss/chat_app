# NEXA — Contact Discovery & Address Book Privacy Architecture

## 1. Zero-Knowledge Address Book Policy

The primary vulnerability of commercial messengers (e.g., Telegram, Signal, WhatsApp) is the compulsory or implicit bulk synchronization of the user's entire address book to cloud servers.

In NEXA, address-book privacy is governed by an inviolable architectural guarantee:
**The user's address book NEVER leaves the device.**

```
+---------------------------------------------------------------------------------------------------+
| COMMERCIAL MESSENGERS (INSECURE MODEL)                                                            |
| [Device Address Book] ════ (Names, Phones, Emails, Notes) ════> [Cloud Social Graph Database]    |
+---------------------------------------------------------------------------------------------------+

+---------------------------------------------------------------------------------------------------+
| NEXA PRIVACY-FIRST MODEL                                                                          |
| [Device Address Book]                                                                             |
|         │                                                                                         |
|         ▼ (Local Android Query via ContentResolver)                                               |
| [Extract & Normalize E.164 Phone Numbers Only]                                                    |
|         │                                                                                         |
|         ▼ (Batch of Max 100 Phone Numbers)                                                        |
| [POST /api/v1/contacts/lookup] ─── (In-Memory Lookup against Verified Users)                      |
|                                       │                                                           |
|                                       ▼                                                           |
| [Return Only Verified NEXA Matches] ◄─── (Zero Contact DB Storage)                                |
|         │                                                                                         |
|         ▼ (Client-Side Merge)                                                                     |
| [UI Displays Matches with "NEXA" Badges]                                                          |
+---------------------------------------------------------------------------------------------------+
```

---

## 2. Privacy Guarantees

1. **No Cloud Address Book Storage**:
   There is no table in the Supabase PostgreSQL database for storing user phone books or raw contact lists. Migration `005_create_contacts.sql` only tracks confirmed cryptographic friend relationships between two existing NEXA IDs who have completed an anti-spam handshake.

2. **No Transmission of Contact Names or Metadata**:
   When querying the backend for NEXA matches, the client application strips all contact names, notes, emails, physical addresses, and profile images. Only the normalized E.164 phone string is passed.

3. **Opt-Out of Phone Discoverability**:
   Users who value pseudonymity can set `is_discoverable_by_phone = false` in their profile. Once disabled, their account will never appear in any contact discovery lookup, even if their phone number is saved in another user's device address book.

4. **Rate Limiting & Anti-Scraping Protection**:
   The Go backend enforces rate limits on `/api/v1/contacts/lookup` (maximum 5 requests per minute, maximum 100 phone numbers per batch) to prevent attackers from performing brute-force phone enumeration.

---

## 3. Real-Time Contact Synchronization
- Android `ContentObserver` monitors changes to `ContactsContract.Contacts.CONTENT_URI`.
- When the user adds, deletes, or edits a contact in their phone's native address book, a debounced trigger (500ms) re-queries local contacts and updates the UI state in real time without requiring a full manual refresh.
