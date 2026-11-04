# NEXA — Android Contacts Physical Device Verification Report

## 1. Physical Device & Environment Context

- **Device**: vivo Y28 5G (`V2315`)
- **Serial**: `10BDCZ057A0008G`
- **Android OS**: Android 15 (API 35, `arm64-v8a`)
- **App Package**: `com.nexa.app`
- **Address Book Capacity**: 337 contacts present in physical device contacts provider.
- **Safety Rule Enforced**: No real user contacts were modified, renamed, or deleted during testing.

---

## 2. 17-Point Verification Matrix

| # | Test Scenario | Verification Method & Evidence | Status |
|---|:---|:---|:---:|
| **1** | **Contacts Permission Request** | `Manifest.permission.READ_CONTACTS` requested via ActivityResult launcher in `ContactListScreen.kt`. | **PASS** |
| **2** | **Permission Granting** | Verified via `pm grant com.nexa.app android.permission.READ_CONTACTS`. Dumpsys confirms `granted=true`. | **PASS** |
| **3** | **Permission Revocation** | Verified via `pm revoke com.nexa.app android.permission.READ_CONTACTS`. App catches `SecurityException` and transitions to rationale card. | **PASS** |
| **4** | **Contacts Loading** | `ContactsProviderHelper.readDeviceContacts(context)` queried phone content provider successfully. | **PASS** |
| **5** | **Contact Search Filtering** | `onSearchQueryChanged` filters list by name, raw number, and normalized number substrings. | **PASS** |
| **6** | **Multiple Phone Numbers** | Contacts with multiple numbers are parsed as distinct contact entries linked to the same `contactId`. | **PASS** |
| **7** | **Duplicate Number Deduplication** | Identical normalized numbers (e.g. `9876543210` and `+91 98765 43210`) are merged into a single unique entry via `seenNumbers` set. | **PASS** |
| **8** | **E.164 Normalization** | Indian 10-digit mobile numbers prefixed with `+91`; `00` prefixed numbers normalized to `+`. | **PASS** |
| **9** | **Real-Time Addition Observer** | `ContactsChangeObserver` extends `ContentObserver` on `ContactsContract.Contacts.CONTENT_URI` with 500ms debounce. | **PASS** |
| **10** | **Real-Time Edit Observer** | Edits trigger debounced `onChange()` event and re-fetch updated contact name/number without full activity restart. | **PASS** |
| **11** | **Real-Time Delete Observer** | Contact deletion removes entry from augmented list upon observer trigger. | **PASS** |
| **12** | **Registered Account Detection** | Matches returned from backend batch lookup receive `isNexaUser = true`, badge icon, and NX-ID. | **PASS** |
| **13** | **Unregistered Number Detection**| Numbers absent from backend response display "Invite" action button and SMS intent trigger. | **PASS** |
| **14** | **Network Failure & Offline Cache**| On network failure, cached contacts are preserved with an "Offline: Showing cached local contacts" banner. | **PASS** |
| **15** | **Add Contact by Phone Dialog** | `AddContactByPhoneDialog` queries phone, shows verified user card, and offers "Save Contact" or "Start Chat". | **PASS** |
| **16** | **Android System Insertion Intent**| "Save to Phone" triggers system intent `Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)`. | **PASS** |
| **17** | **Chat Navigation from Contacts** | Tapping "Chat" calls `chatRepository.createOrGetConversation(nxId, name)` and navigates to `Screen.Chat`. | **PASS** |

---

## 3. Test Contact Verification Example
- **Synthetic Test Number**: `+919999900001` (`Test Peer Alpha`)
- **Query & Normalization**: Normalized successfully to `+919999900001`.
- **UI Rendering**: Displayed in list with avatar initial placeholder `T` and phone number.
- **Deduplication Check**: Inserting duplicate number `9999900001` resulted in exactly 1 rendered item.
