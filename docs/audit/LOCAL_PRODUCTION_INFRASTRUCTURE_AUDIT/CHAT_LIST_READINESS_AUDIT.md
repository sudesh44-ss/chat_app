# NEXA — Chat List Screen & Dashboard Readiness Audit

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** `MainChatDashboardScreen.kt`, `ChatRepositoryImpl.kt`, Local Database  
**Date:** November 2026  

---

## 1. Architectural Model: Zero-Server Conversation Storage

A central question in the audit was: *"Does the backend have a Chat List API?"*

### Finding
In NEXA's Blind-Postman E2EE architecture, **there is NO backend Chat List API by design**.
- The server never stores user chat history, conversation lists, or contact relationships.
- The relay only forwards encrypted wire packets (`NexaWirePacket`) between connected WebSockets or holds them in ephemeral Redis buffers for up to 24 hours.
- **Conversations exist strictly in the client's local Room database (`ChatDao`).**

---

## 2. Android Dashboard Implementation Audit

### Screen File
`app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt`

### Observed State Handling
1. **Profile Data:**
   `val activeProfile by authRepository.getActiveProfile().collectAsState(initial = null)`
   Displays user name, avatar, and permanent `NX-ID`.
2. **Conversations:**
   `val conversations by chatRepository.getConversations().collectAsState(initial = emptyList())`
   Directly observes `chatDao.getChatsFlow()`.
3. **Empty State Card:**
   ```kotlin
   if (conversations.isEmpty()) {
       // Renders "No conversations yet. Start a chat by exchanging NX-IDs or scanning a QR code."
   }
   ```
   **Verdict:** Handles newly registered accounts with zero conversations properly without crashing or hanging.
4. **Relay Connection Pill (`RelayStatusPill`):**
   Observes `chatRepository.getConnectionState()`:
   - Green dot: "Relay Connected"
   - Orange dot: "Connecting..."
   - Grey dot: "Relay Disconnected"

---

## 3. What Blocks the User from Reaching the Chat List?

The Chat List screen code is fully functional. What blocks the user from reaching it is:

1. **Step 3 Onboarding Block:** The 90-second Reverse SMS timeout prevents `VerificationRadarScreen` from navigating to `ProfileBadgeScreen` and `MainChatDashboardScreen`.
2. **WebSocket Authentication Failure (Once Reached):**
   - As audited in `DEVICE_IDENTITY_AND_SESSION_AUDIT.md`, `CryptoKeystoreManager` generates a public key via SHA-256 clamping, while `WebSocketManager` signs with `nexa_handshake_keys` (Ed25519).
   - When the user arrives on the dashboard, the WebSocket disconnects with `Auth failed: packet public key does not match registered account public key`.
   - The status pill stays stuck on **"Relay Disconnected"**.
