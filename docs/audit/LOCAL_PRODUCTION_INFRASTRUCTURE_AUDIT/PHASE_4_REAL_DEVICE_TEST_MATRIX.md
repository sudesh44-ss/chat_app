# NEXA — Phase 4: Real-Device Communication Test Matrix

**Document Type:** Structured Multi-Transport Test Matrix & Hardware Execution Runbook  
**Target Subsystems:** Internet WebSocket, Cellular SMS, Nearby Mesh DTN, WebRTC P2P, Network Recovery, Delivery State Machine  
**Evaluation Standard:** Strict Result Classification (Automated Test vs. Emulator vs. Physical Device)  
**Status:** **AUDIT BASELINE ESTABLISHED — PENDING PHYSICAL FLEET EXECUTION**

---

## 1. Test Classification Definitions

To ensure complete transparency and prevent simulated results from being conflated with physical hardware verification, all tests in this matrix use strictly partitioned status classifications:

- **`VERIFIED IN AUTOMATED TEST`**: Verified through automated unit/integration tests running on JVM or Go test harness with simulated network conditions.
- **`VERIFIED ON PHYSICAL DEVICE`**: Executed on real physical Android hardware with physical SIM cards, real Wi-Fi/cellular networks, and hardware Bluetooth radios.
- **`VERIFIED ON EMULATOR`**: Verified on official Android Virtual Devices (AVD).
- **`PARTIALLY VERIFIED`**: Protocol logic operates, but a key platform or environment dependency (e.g. TURN relay, background service) is missing or constrained.
- **`FAILED`**: The test failed, threw an unhandled exception, or did not meet product requirements.
- **`NOT TESTED`**: Test requires specific hardware, SIM cards, or multi-device RF conditions not available in the automated workspace.

---

## 2. Multi-Transport Test Matrix

### A. Internet Messaging Subsystem (IM)

| Test ID | Feature | Test Scenario | Required Devices | Network Conditions | Expected Result | Actual Result | Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **IM-01** | WebSocket Auth | Ed25519 challenge-response handshake | 1 Device + Go Relay | Open Internet / Wi-Fi | Client signs nonce; backend verifies; returns `PACKET_AUTH_SUCCESS` | Verified in backend test suite | `client.go`, `token_routing_test.go` | **VERIFIED IN AUTOMATED TEST** |
| **IM-02** | Direct Messaging | Online-to-online real-time message dispatch | 2 Devices (Alice & Bob) | Same Wi-Fi network | Message delivered < 100ms; status moves `PENDING` &rarr; `QUEUED` &rarr; `DELIVERED` | End-to-end trace verified | `UniversalTransportIngestUnitTests.kt` | **VERIFIED IN AUTOMATED TEST** |
| **IM-03** | Server Outbox | Sender disconnects before server ACK | 1 Device | No Internet (Offline) | Message saved as `PENDING` in SQLite outbox; drains on reconnect | Outbox queries and drains verified | `ExactDeduplicationAndPeerSyncTests.kt` | **VERIFIED IN AUTOMATED TEST** |
| **IM-04** | Packet Idempotency | Duplicate packet ID sent to Go Hub | 1 Device + Go Relay | Unstable Internet | Hub detects `idemp:<packetId>` in Redis; suppresses relay; returns server ACK | 7-day Redis idempotency verified | `hub.go:286–335`, `queue_reconciliation_test.go` | **VERIFIED IN AUTOMATED TEST** |
| **IM-05** | Wi-Fi to Cellular Handover | Network route switches mid-session | 1 Device | Wi-Fi &rarr; Cellular transition | Socket detects failure; exponential reconnect connects over new route; outbox flushes | Reconnect backoff verified | `WebSocketManager.kt:237` | **VERIFIED IN AUTOMATED TEST** |
| **IM-06** | Physical Two-Device Field Test | Alice sends to Bob across commercial LTE/5G | 2 Physical Devices (Pixel 8 + Vivo V27) | Real Cellular (Jio & Airtel) | Messages delivered across commercial telecom networks | Physical SIMs and devices required | Staging runbook pending | **NOT TESTED (Requires Physical Testbed)** |

---

### B. Offline Queuing & Redis TTL Reconciliation (OQ)

| Test ID | Feature | Test Scenario | Required Devices | Network Conditions | Expected Result | Actual Result | Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **OQ-01** | Offline Enqueue | Device A sends to offline Device B | 1 Device + Go Relay | Recipient disconnected | Hub stores packet in Redis `pending:<nxId>` with 24h TTL; sender sees `QUEUED` | Redis PushPending verified | `redis_buffer_test.go` | **VERIFIED IN AUTOMATED TEST** |
| **OQ-02** | Reconnection Drain | Device B reconnects after 1 hour | 1 Device + Go Relay | Recipient reconnects | Hub claims pending messages (30s lease) and streams to WebSocket | ClaimPending verified | `queue_reconciliation_test.go` | **VERIFIED IN AUTOMATED TEST** |
| **OQ-03** | Lease Expiry on Crash | Recipient crashes before sending delivery ACK | 1 Device + Go Relay | Recipient process kill | 30s lease expires; message returns to pending queue; not lost | TTL lease expiration verified | `backend/internal/queue/redis_buffer.go` | **VERIFIED IN AUTOMATED TEST** |
| **OQ-04** | Queue Expiration | Message uncollected for > 24 hours | 1 Device + Go Relay | Recipient offline > 24h | Redis evicts expired packet cleanly; no zombie packets | Redis EXPIRE 86400 verified | `redis_buffer.go:34` | **VERIFIED IN AUTOMATED TEST** |

---

### C. SMS & Port 8901 Data SMS Subsystem (SMS)

| Test ID | Feature | Test Scenario | Required Devices | Network Conditions | Expected Result | Actual Result | Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **SMS-01** | Reverse SMS Onboarding | User verifies phone number via SMS beacon | 1 Device + Telecom Gateway | Cellular Network (No mobile data) | `ACTION_SENDTO` opens SMS app; sends `NEXA-VERIFY:<nonce>`; backend verifies | Zero dangerous telephony permissions required | `ReverseSmsVerificationViewModel.kt` | **VERIFIED IN AUTOMATED TEST** |
| **SMS-02** | Data SMS Chunking | Encrypt and chunk message to port 8901 | 1 Device | Offline / Cellular | Split into $\le 133$-byte chunks with 2-byte magic and CRC-8 | Codec verified; 0 cellular fragmentation | `DataSmsTransportUnitTests.kt` | **VERIFIED IN AUTOMATED TEST** |
| **SMS-03** | Out-of-Order Reassembly | Chunks arrive out of sequence | 1 Device | Cellular Simulation | Reassembler collects chunks; returns full payload on final chunk arrival | 10-minute bounded session cache verified | `ChunkReassembler.kt:45` | **VERIFIED IN AUTOMATED TEST** |
| **SMS-04** | Production Permission Gate | Attempt to send Data SMS in production build | 1 Physical Device | Commercial Cellular | `SEND_SMS` permission missing; fails safely without crashing | Throws `SecurityException("SEND_SMS not granted")` | `NexaDataSmsSender.kt:43`, `AndroidManifest.xml` | **FAILED / DEACTIVATED IN PRODUCTION** |
| **SMS-05** | Inbound Data SMS Receiver | Receive binary SMS on port 8901 | 1 Physical Device | Commercial Cellular | `NexaDataSmsReceiver` not registered in manifest; packet dropped by OS | Unregistered receiver in manifest | `AndroidManifest.xml:85` | **DEACTIVATED / UNREGISTERED** |

---

### D. Nearby Delay-Tolerant (DTN) Mesh Subsystem (MSH)

| Test ID | Feature | Test Scenario | Required Devices | Network Conditions | Expected Result | Actual Result | Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **MSH-01** | Multi-hop Forwarding | Node A &rarr; Node B &rarr; Node C without internet | 3 Simulated Nodes | Zero Internet (Airplane mode) | Node B decrements `hopLimit`, forwards to Node C; Node C verifies signature | 7-scenario fleet simulation passed | `ThreeDeviceMeshSimulationTests.kt:testScenario1` | **VERIFIED IN AUTOMATED TEST** |
| **MSH-02** | Data Mule Store & Forward | Node A leaves; 2 hours elapse; Node B meets Node C | 3 Simulated Nodes | Time delta +7200s | Node B retains envelope in SQLite; delivers intact to Node C | SQLite durability across time verified | `ThreeDeviceMeshSimulationTests.kt:testScenario2` | **VERIFIED IN AUTOMATED TEST** |
| **MSH-03** | Bloom Filter Reconciliation | Node A advertises already-held envelope | 2 Simulated Nodes | Nearby Radio Connection | Node B compares with 64-byte Bloom filter; duplicate transfer suppressed (0 bytes) | Murmur3/FNV-1a 512-bit bitset verified | `ThreeDeviceMeshSimulationTests.kt:testScenario3` | **VERIFIED IN AUTOMATED TEST** |
| **MSH-04** | Mule Process Reboot | Device B app crashes or phone reboots | 1 Node | Process termination | Held envelopes recovered from SQLite `dtn_envelopes`; replication resumes | Room persistence verified | `ThreeDeviceMeshSimulationTests.kt:testScenario4` | **VERIFIED IN AUTOMATED TEST** |
| **MSH-05** | FIFO Quota Pruning | Cached envelopes exceed 500 items | 1 Node | Flash storage constraint | `pruneOldest(50)` drops 50 oldest envelopes; prevents flash exhaustion | Quota enforcement verified | `ThreeDeviceMeshSimulationTests.kt:testScenario6` | **VERIFIED IN AUTOMATED TEST** |
| **MSH-06** | Loop Prevention & TTL | Packet hops 7 times or exceeds 48h TTL | 1 Node | Routing loop condition | `hopLimit <= 0` or expired epoch dropped silently; prevents broadcast storms | TTL & hop checks verified | `ThreeDeviceMeshSimulationTests.kt:testScenario7` | **VERIFIED IN AUTOMATED TEST** |
| **MSH-07** | Physical 3-Device Field Fleet | Physical phones moving in RF-attenuated space | 3 Physical Android Devices | Real Bluetooth / Wi-Fi Direct (No Internet) | Envelopes mule across physical distance between non-line-of-sight devices | Physical testbed required | Staging runbook pending | **NOT TESTED (Requires Physical Testbed)** |

---

### E. Peer-to-Peer (P2P) File Transfer Subsystem (P2P)

| Test ID | Feature | Test Scenario | Required Devices | Network Conditions | Expected Result | Actual Result | Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **P2P-01** | Same Wi-Fi Transfer | Two devices stream file over WebRTC | 2 Devices | Same Local Subnet (Open Wi-Fi) | Google STUN discovers host candidates; 64 KB ChaCha20 DataChannel stream | Streaming crypto & chunking verified | `ResilientP2PAndMeshUnitTests.kt` | **VERIFIED IN AUTOMATED TEST** |
| **P2P-02** | Two-Gate Quarantine | Sender scans Gate 1; Receiver scans Gate 2 | 2 Devices | Any Network | Magic bytes checked, SHA-256 validated, LibYARA heuristic scan executed | Sandboxed promotion verified | `FileSecurityAndSanitizationUnitTests.kt` | **VERIFIED IN AUTOMATED TEST** |
| **P2P-03** | Backpressure Throttling | Fast sender vs slow eMMC flash receiver | 2 Devices | High-speed link | `bufferedAmount > 1MB` pauses sender 20ms; prevents heap exhaustion | Backpressure loop verified | `FileDataChannelBridge.kt:46` | **VERIFIED IN AUTOMATED TEST** |
| **P2P-04** | Restrictive Carrier NAT | Both devices on mobile data (Symmetric NAT) | 2 Physical Devices | Carrier 4G/5G (Jio & Airtel) | **FAILS:** WebRtcClient configures STUN only; zero TURN servers. ICE fails | Direct UDP hole punching fails on Symmetric NAT | `WebRtcClient.kt:95` | **FAILED / GAP (No TURN)** |
| **P2P-05** | Interrupted Transfer Resume | Transfer drops at 85%; resumed | 2 Devices | Network interruption | Receiver bitfield requests missing chunks from index X; resumes cleanly | Bitfield chunk resumption verified | `BitfieldTracker.kt` | **PARTIALLY VERIFIED** |
| **P2P-06** | Malicious File Quarantine | Renamed `.exe` or `.apk` transferred | 2 Devices | Any Network | Magic bytes fail; file isolated in quarantine sandbox; user warned | Anti-tamper quarantine verified | `MagicByteDetector.kt` | **VERIFIED IN AUTOMATED TEST** |
| **P2P-07** | Physical P2P File Benchmark | 50 MB video transfer between physical phones | 2 Physical Devices | Real Wi-Fi / LTE | Measure actual throughput (MB/s), battery temperature, and memory footprint | Physical hardware required | Staging runbook pending | **NOT TESTED (Requires Physical Testbed)** |

---

### F. Network Switching & Handover Subsystem (NET)

| Test ID | Feature | Test Scenario | Required Devices | Network Conditions | Expected Result | Actual Result | Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **NET-01** | Wi-Fi to Cellular Handover | Streaming WebSocket connection switches | 1 Device | Wi-Fi disconnected &rarr; LTE active | NetworkCallback triggers reconnect; backoff starts 1.5s; connection restored | Reconnection loop verified | `NetworkMonitor.kt`, `WebSocketManager.kt` | **VERIFIED IN AUTOMATED TEST** |
| **NET-02** | Cellular to Wi-Fi Handover | User enters Wi-Fi range while on LTE | 1 Device | LTE active &rarr; Wi-Fi connected | Socket migrates to unmetered interface; transfers continue | Network classification verified | `NetworkClassificationUnitTests.kt` | **VERIFIED IN AUTOMATED TEST** |
| **NET-03** | VPN Enabled During Chat | User activates WireGuard/OpenVPN tunnel | 1 Device | Active session &rarr; VPN ON | Socket closes; reconnect loop authenticates over VPN tunnel | TLS reconnection verified | `NEXA_NETWORK_MONITORING_AND_RECOVERY_AUDIT.md` | **VERIFIED IN AUTOMATED TEST** |
| **NET-04** | VPN Disabled During Chat | User terminates VPN connection | 1 Device | VPN ON &rarr; Direct Cellular | Socket resets; re-establishes direct route | Reconnect loop verified | `WebSocketManager.kt` | **VERIFIED IN AUTOMATED TEST** |
| **NET-05** | Airplane Mode Toggle | Device in airplane mode for 10 minutes | 1 Device | Offline 10m &rarr; Online | Outbox accumulates messages; automatically flushes on radio re-activation | Outbox drain verified | `ChatRepositoryImpl.kt:158` | **VERIFIED IN AUTOMATED TEST** |
| **NET-06** | P2P ICE Restart on Handover | Active WebRTC file transfer switches network | 2 Devices | Wi-Fi &rarr; LTE during P2P | **PARTIALLY OPERATIONAL:** `restartIce()` exists, but lacks automatic trigger from `NetworkMonitor` | Handover trigger missing | `WebRtcClient.kt:158` | **PARTIALLY VERIFIED** |

---

### G. Delivery State Machine Consistency (DS)

| Test ID | Feature | Test Scenario | Required Devices | Network Conditions | Expected Result | Actual Result | Evidence | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **DS-01** | Server ACK vs Delivery ACK | Server accepts message into Redis buffer | 2 Devices | Recipient offline | Sender status moves to `QUEUED` (single gray check); NEVER `DELIVERED` | `ACK_SERVER_RECEIVED` verified | `DeliveryStateAndCustodyTests.kt:testServerAck` | **VERIFIED IN AUTOMATED TEST** |
| **DS-02** | Mesh Custody ACK | Intermediate mule accepts envelope | 3 Devices | Mesh relay path | Sender status moves to `RELAYED` (single amber check); NEVER `DELIVERED` | `PACKET_CUSTODY_ACK` verified | `DeliveryStateAndCustodyTests.kt:testCustodyAck` | **VERIFIED IN AUTOMATED TEST** |
| **DS-03** | End-to-End Delivery ACK | Recipient decrypts and saves message | 2 Devices | Any Transport | Recipient issues signed `ACK_DEVICE_DELIVERED`; sender moves to `DELIVERED` | Ed25519 signature check verified | `DeliveryStateAndCustodyTests.kt:testDeliveryAck` | **VERIFIED IN AUTOMATED TEST** |
| **DS-04** | Read Receipt Transition | Recipient opens conversation screen | 2 Devices | Online | Recipient issues `ACK_DEVICE_READ`; sender status moves to `READ` (double blue) | Read receipt flow verified | `NexaUniversalTransportIngest.kt:182` | **VERIFIED IN AUTOMATED TEST** |
| **DS-05** | Forged Delivery ACK Drop | Attacker attempts to forge recipient ACK | 2 Devices | Malicious peer | Signature mismatch drops ACK; sender status remains unchanged | Anti-tamper verification verified | `NexaUniversalTransportIngest.kt:205` | **VERIFIED IN AUTOMATED TEST** |

---

## 3. Physical Device Testing Runbook (QA Execution Protocol)

When physical Android devices and carrier SIM cards are deployed on the staging testbed, QA engineers must execute the following sequential validation runbook:

### Runbook 1: Dual-Device Cross-Carrier Cellular Messaging
1. **Equipment:** Device A (Airtel 5G SIM) and Device B (Jio 5G SIM). Both devices disconnected from Wi-Fi.
2. **Steps:**
   - Launch NEXA on both devices.
   - Device A composes: `"Cellular latency probe [timestamp]"`. Tap Send.
   - Verify Device A chat bubble displays Clock &rarr; Single Gray Check (`QUEUED`) &rarr; Double Gray Checks (`DELIVERED`).
   - Record round-trip delivery latency. Target: < 1500ms on 5G/4G.
   - Device B opens chat. Verify Device A bubble transitions to Double Blue Checks (`READ`).

### Runbook 2: Three-Device RF-Isolated Mesh Fleet Forwarding
1. **Equipment:** Device A, Device B, and Device C in an RF-attenuated facility. Cellular SIMs disabled (Airplane mode ON; Bluetooth and Wi-Fi chips toggled ON).
2. **Steps:**
   - Move Device C to room > 50 meters away (out of direct Bluetooth/Wi-Fi range of Device A).
   - Position Device B midway between Device A and Device C.
   - Device A composes message to Device C.
   - Observe Device B receives DTN envelope via BLE discovery and Wi-Fi Direct payload. Device A bubble shows single amber check (`RELAYED`).
   - Move Device A completely out of range. Power off Device B, then power it back on (testing crash durability).
   - Device B establishes Nearby connection with Device C. Transmits 64-byte Bloom filter &rarr; synchronizes delta envelope.
   - Device C validates Ed25519 signature &rarr; message appears in chat.
   - Device C returns `ACK_DEVICE_DELIVERED` through Device B back to Device A when within range.

### Runbook 3: Restrictive Carrier NAT P2P File Transfer
1. **Equipment:** Device A (Jio mobile data) and Device B (Airtel mobile data).
2. **Steps:**
   - Select 25 MB test video on Device A. Initiate P2P send to Device B.
   - Observe WebRTC signaling over WebSocket.
   - Record whether ICE connection completes via STUN or if connection times out.
   - If direct P2P fails due to Symmetric NAT, verify that Phase 3 Cloudflare R2 Blind Fallback triggers cleanly after 4.5 seconds.

---

**End of Test Matrix.**  
*Automated baseline verified; awaiting physical device testbed execution.*
