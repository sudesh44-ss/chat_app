Direct P2P file transfer me server par koi storage na hone ki wajah se connection drops aur offline states ko sambhalna sabse challenging part hota hai.

Yahan wo saare real-world edge cases aur failure scenarios hain jo is system me aayenge, unke peeche ka mechanics, aur unhe bina server par file save kiye handle karne ka exact approach:

---

### Scenario 1: Jab User A File Bhej Raha Hai, Tab User B Offline Hai 📴

* **Problem:** Direct P2P tunnel tabhi banta hai jab dono ends simultaneously connected hon. Receiver offline hai toh WebRTC handshake fail ho jayega.
* **Underlying Mechanics:**
1. **Metadata Push Notification via Server:**
* User A actual file bhejne ke bajaye sirf ek tiny encrypted metadata JSON packet (File Name, Size, File Hash, aur Transfer ID) server ke through User B ko bhejta hai.
* Server par file upload nahi hoti; server sirf ek lightweight FCM High-Priority Data Notification User B ke phone par push karta hai.


2. **Pending Outbox Queue (Sender Device):**
* User A ke phone par file `PENDING_PEER_ONLINE` state me local Room database me lock ho jati hai.


3. **Wake-Up & Rendezvous:**
* Jaise hi User B ka phone internet se connect hota hai, uska app background me server ko bolta hai: *"Main online hoon, mere liye koi pending session hai?"*
* Server User A ko signal bhejta hai: *"User B online aa gaya hai, P2P handshake initiate karo."* Dono ke beech direct P2P connection banta hai aur transfer shuru ho jata hai.





---

### Scenario 2: Transfer Chal Raha Tha Aur Beech Me Internet Cut Ho Gaya (User A ya B ka) 🔌

* **Problem:** 1 GB ki file me se 600 MB transfer ho chuka tha aur sudden call aane ya tower switch hone se connection drop ho gaya. Agar file scratch se shuru karni padi toh user experience kharab hoga.
* **Underlying Mechanics (Offset Resumption / BitTorrent-Style Chunking):**
1. **Sparse File Allocation:** Receiver end par file receive shuru hote hi Android ke `RandomAccessFile` se disk par exact file size reserve kar li jati hai.
2. **Chunk Tracking Bitset:**
* File ko fixed chunks (e.g., 256 KB each) me toda jata hai.
* Dono devices ek local **Bitfield / Bitmap** maintain karti hain (e.g., `Chunk #0 to #2400: Received [1], Missing [0]`).


3. **Re-connection Handshake:**
* Jaise hi internet wapas aata hai, ICE restart (WebRTC reconnection) hota hai.
* Receiver sender ko apna last verified chunk index ya bitmap bhejta hai: *"Mere paas chunk #2400 tak securely written hai."*
* Sender seedha **chunk #2401** se stream resume karta hai. **Zero byte waste.**





---

### Scenario 3: Android OS Ne Background Me App Kill Kar Diya (Low RAM ya Battery Saver) 🛑

* **Problem:** User ne 2 GB ki file bhejne ke baad Instagram ya camera open kiya, ya screen lock karke jeb me daal li. Android system-level par background processes aur network sockets kill kar deta hai.
* **Underlying Mechanics:**
1. **Foreground Service with Sticky Notification:**
* Transfer start hote hi app ek Android `Foreground Service` start karega with `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
* Notification drawer me permanent progress bar chalega (`Transferring... 45%`). Android OS foreground service wale apps ko RAM cleanup me kill nahi karta.


2. **CPU & Wi-Fi WakeLocks:**
* Transfer ke dauran `PowerManager.PARTIAL_WAKE_LOCK` aur `WifiManager.WifiLock` hold kiye jate hain taaki phone screen off hone par bhi Wi-Fi chip aur CPU sleep mode (Doze) me na chale jayein. Transfer complete hote hi locks release.





---

### Scenario 4: Strict Symmetric NAT / Firewall (Direct P2P Fail) 🧱

* **Problem:** 10-15% cases me users aise strict mobile networks (CGNAT) ya Wi-Fi par hote hain jahan STUN se direct P2P connection banana mathematically impossible ho jata hai.
* **Underlying Mechanics:**
1. **Ephemeral Memory-Only TURN Relay:**
* Jab STUN se connection fail hota hai, system fallback ke roop me **TURN Server (Coturn)** use karta hai.


2. **Zero-Disk Guarantee:**
* TURN server file ko disk/storage par save nahi karta. Wo sirf network level par packets ko input port se output port par relay (forward) karta hai directly in-RAM.
* Data already application level par end-to-end encrypted (ChaCha20-Poly1305) hota hai, isliye server host bhi file inspect nahi kar sakta.





---

### Scenario 5: File Transfer Ke Beech Sender Ne File Delete Ya Move Kar Di 📁

* **Problem:** Transfer chal raha hai aur sender ne file manager me jaakar us video ya photo ko delete ya rename kar diya.
* **Underlying Mechanics:**
1. **File Descriptor Lock / Scoped Storage Access:**
* Android 10+ ke **Storage Access Framework (SAF)** me file ka direct URI persist kiya jata hai.


2. **File Checksum Verification on Fly:**
* Agar OS read stream crash karti hai (`FileNotFoundException` ya `IOException`), toh app instantly receiver ko ek custom signaling packet bhejta hai: `TRANSFER_ABORTED_SOURCE_MODIFIED`.
* Receiver side par incomplete file safely purge ya mark ho jati hai taaki corrupt media storage me space na ghere.





---

### Scenario 6: Corrupt Chunks / Packet Tampering 🦠

* **Problem:** Network glitch ki wajah se koi byte flip ho gaya ya bad packet deliver ho gaya, jisse decrypt hone ke baad file corrupt ho sakti hai.
* **Underlying Mechanics:**
* Har chunk ke saath ek chota **Blake3 ya SHA-256 Checksum** attach hota hai.
* Receiver chunk aate hi instantly verify karta hai. Agar checksum fail hua, toh receiver sirf us specific chunk ke liye `NACK (Negative Acknowledgement)` bhejta hai aur sender sirf wahi 256 KB chunk dubara bhejta hai.



---

### Edge Cases Summary Matrix

| Scenario | Primary Cause | System Handling Strategy | Server Cost |
| --- | --- | --- | --- |
| **Peer Offline** | User not on app/net | FCM Wake-up ping + Sender Outbox hold | **₹0** (Push only) |
| **Network Drop** | Wi-Fi/Data disconnect | Bitfield chunk tracking + Auto-resume from offset | **₹0** |
| **App Minimized** | Android Doze / Low RAM | Foreground Service + Partial WakeLock | **₹0** |
| **Direct P2P Blocked** | Symmetric NAT/Firewall | RAM-only TURN relay (Zero disk) | **Minimal bandwidth only** |
| **File Modified/Deleted** | User altered source file | `SAF Uri` read validation + Clean abort protocol | **₹0** |


Multiple users jab ek sath file transfer karte hain, toh do scenarios aate hain:

1. **1-to-Many (Group / Broadcast):** Ek sender ek hi file multiple receivers ko bhej raha hai (e.g., group me ek 500 MB video send kiya).
2. **Many-to-Many (Concurrent Multiple Transfers):** Phone par ek hi waqt par multiple transfers chal rahe hain (A se file aa rahi hai, C ko file ja rahi hai, D ko alag document bhej rahe ho).

Bina cloud server par file save kiye yeh kaise execute hota hai, iska exact mechanics aur optimization breakdown:

---

### 1. Direct Concept Breakdown 🎯

#### Case A: Ek Sender -> Multiple Receivers (1-to-Many / Group File)

Standard cloud apps (Telegram/WhatsApp) me sender file ko **ek baar** server par upload karta hai aur server sabhi ko baant deta hai.
Lekin **Zero-Server P2P** me agar 1 sender 5 logon ko 100 MB ki file direct bhejega, toh sender ka 500 MB upload data kharch ho jayega aur phone ka network choke ho jayega.

Iska solution hota hai **P2P Swarming (BitTorrent Mesh Architecture)**:

* Sender file ko multiple receivers me alag-alag chunks me baant deta hai:
* Receiver 1 ko chunk 1–25 bheja.
* Receiver 2 ko chunk 26–50 bheja.
* Receiver 3 ko chunk 51–75 bheja.


* Ab Receiver 1, 2 aur 3 aapas me ek doosre se direct connect hoke bache hue chunks swap kar lete hain!
* **Result:** Sender ka upload bandwidth sirf 1x laga, server par ₹0 kharch hua, aur file poore group me deliver ho gayi.

#### Case B: Multiple Parallel Transfers (Concurrent Transfers on One Phone)

Jab ek phone ek sath multiple files send/receive karta hai, toh phone ka CPU (encryption), RAM (file buffers), aur network bandwidth choke hone ka risk hota hai.
Isko sambhalne ke liye app me **Job Scheduler Queue with Bandwidth Throttling** lagayi jaati hai.

---

### 2. Underlying Tech Stack & Mechanics ⚙️

#### A. WebRTC Multi-Peer Mesh (Mesh Swarming)

* Har peer ke sath ek independent `RTCPeerConnection` aur `RTCDataChannel` establish hota hai.
* Ek **Swarm Coordinator Engine** banta hai jo signaling server ke zariye ek lightweight session table maintain karta hai:
```json
{
  "swarm_id": "file_hash_98234",
  "peers": ["peer_A", "peer_B", "peer_C"],
  "chunk_availability": {
    "peer_B": [1, 2, 3],
    "peer_C": [4, 5, 6]
  }
}

```


* Har peer doosre peer ko **"Have" messages** bhejta hai: *"Mere paas chunk #3 aa gaya hai, jisko chahiye mujhse direct le lo."*

#### B. Concurrent Multi-Queue Manager (Android Client Side)

* Android me **Kotlin Coroutines with Dispatchers.IO** aur `Semaphore` use hota hai:
* Maximum concurrent active network transfers limit: **Max 2 ya 3 active tasks**.
* Baaki sabhi transfers `QUEUED` state me local Room database me wait karte hain.


* Jaise hi ek file ka transfer complete hota hai, worker automatically agli file trigger kar deta hai.

#### C. End-to-End Encryption in Multi-Peer Swarm

* Sabhi peers ke paas file decrypt karne ki key honi chahiye, lekin transport safe rehna chahiye:
1. Sender ek unique random **Symmetric File Key ($K_{file}$)** generate karta hai.
2. File ko $K_{file}$ se encrypt kiya jata hai (ChaCha20-Poly1305).
3. $K_{file}$ ko har authorized receiver ki personal public key se alag-alag encrypt karke unhe directly/signaling se bheja jata hai.
4. Swarm me encrypted chunks travel karte hain; koi un-authorized peer (ya TURN relay) chunks pakad bhi le, toh decrypt nahi kar sakta.



---

### 3. Feasibility Check 🔍

| Parameter | Feasibility | Reality Check |
| --- | --- | --- |
| **Concurrent File Transfers (1-on-1 parallel)** | **100% Practical** | Standard mobile hardware 3-4 parallel P2P encrypted streams aaram se handle kar leta hai. |
| **Small Groups (3 to 10 users swarm)** | **100% Practical** | Office, family ya doston ke group ke liye perfect. Fast aur server-free. |
| **Massive Groups (100+ users)** | **Complex & Battery Heavy** | Mobile phones battery-constrained hote hain. 50+ peers se ek sath continuous WebRTC connections maintain karne se phone garam ho sakta hai aur battery jaldi drop hogi. Aise large scale groups me opportunistic cloud relay ya queue throttling mandatory hoti hai. |

---

### 4. Isko Aur Effective & Efficient Kaise Banayein? 🚀

1. **Dynamic Bandwidth Allocation (Fair Queuing):**
* Agar User A ek 2 GB video bhej raha hai aur usi waqt ek 200 KB ka urgent PDF bhejta hai:
* System ko **Priority-Based Chunking** deni chahiye. Choti files (PDF, images) ko high priority lane mile taaki wo instantly send ho jayein, jabki heavy video background me bache hue bandwidth par stream hota rahe.


2. **Local LAN Discovery Priority (mDNS):**
* Agar group ke 3-4 log ek hi Wi-Fi network par baithe hain (e.g., college lab, hostel room, office):
* App unhe internet par le jane ke bajaye **mDNS / NSD (Network Service Discovery)** se local IP par WebRTC connect kar deta hai. Data router se bahar nahi jata, aur transfer speed **20 MB/s se 40 MB/s** pahunch jati hai bina kisi internet data pack consumption ke.


3. **Fixed Buffer Memory Pool (Prevent OOM Crashes):**
* Multiple files ek sath encrypt/decrypt hone par memory leak se bachne ke liye direct buffer allocations na karein.
* Ek shared **Byte Array Pool** (e.g., 4 × 64 KB buffers) rakhein jisko saare active transfers reuse karein. Isse Android Garbage Collector (GC) par load nahi padta aur app kabhi frame drop ya hang nahi hota.


4. **Connection Pool Limits:**
* Ek device par active WebRTC Peer Connections ki maximum limit **4–5 peers** par cap karke rakhein. Agar 8 log group me hain, toh device sirf 2-3 sabse fast/stable peers se connect hokar chunks legi aur aage degi (Tree-based overlay).


50k users ke scale par jab hazaron log ek sath file transfers, network drops aur reconnects karenge, tab sabse pehle **Signaling Server** aur **State Synchronization Engine** par pressure aata hai.

Chunki humara rule hai: **Server par file ka 1 byte bhi store nahi hoga aur infra cost developer ke liye negligible rakhni hai**, isliye 50,000 active users ke is chaotic ecosystem ko scale karne ka exact production-level architecture yeh raha:

---

### 1. 50k Scale Par Core Problems (Bottlenecks) ⚠️

Agar 50k users hain:

1. **Signaling Storm (Thundering Herd):** Har user jab bar-bar offline/online hoga, agar har baar wo server ko ping karega aur heavy WebRTC SDP handshake create karega, toh backend crash ho jayega.
2. **Flapping Connections (Jitter / Fast Reconnects):** User B auto/rickshaw me travel kar raha hai, har 20 second me network 4G se disconnect ho raha hai. Pura connection scratch se recreate karne par file kabhi aage hi nahi badhegi.
3. **Bandwidth Trap (TURN Server Blow-Up):** Agar 50k me se 15% log (7,500 users) strict symmetric NAT par chale gaye aur TURN relay par 1 GB ki file stream karne lage, toh server ka bandwidth bill hazaron dollars me chala jayega.

---

### 2. Underlying Tech Stack & Mechanics (50k Ready) ⚙️

#### A. Backend Signaling Layer (Ultra-Low Footprint & Negligible Cost)

50k concurrent WebSockets ko Node.js ya Python se handle karne par multiple heavy servers lag jayenge.

* **Go (Golang) ya Rust Relay:** Go ke lightweight Goroutines aur `epoll` architecture par bana ek single **$10–$20/month VPS** (4 vCPU, 8 GB RAM) aaram se **100,000+ idle/signaling persistent connections** ko bina CPU load ke sambhal leta hai.
* **Pub/Sub Broker:** Server par koi SQL queries nahi chalegi. RAM-based **Redis Pub/Sub** ya **NATS.io** use hoga, jo sirf JSON signaling packets (ICE candidates, Session Offers) ko microseconds me route karta hai.

#### B. The "Exponential Backoff & Session Token" Reconnect Loop

Jab User B bar-bar online/offline ("Flapping") hota hai:

* **Session Ticket:** Jab pehli baar transfer start hota hai, dono devices ek 16-byte ka ephemeral `Session_Ticket` locally generate karti hain.
* **ICE Restart (Zero-Signaling Reconnect):** Har drop par naya WebRTC connection nahi banta. WebRTC ka **ICE Restart** feature use hota hai, jo bina audio/data channels ko destroy kiye existing socket ko naye IP par bind kar deta hai.
* **Quiet Window (Hysteresis Delay):** Agar User B 10 second ke andar 3 baar offline-online hua, toh app instantly handshake trigger nahi karega. App 5 second ka **Quiet Window** wait karega taaki network stable ho, fir direct last saved chunk index se transfer stream karega.

#### C. Local Sliding-Window Chunk Protocol (Zero Byte Loss)

50k users ke alag-alag phone memory configurations hote hain.

* **Sliding Window:** Sender saare chunks ek sath dump nahi karta. Sender ka window size hota hai (e.g., 8 chunks of 64KB = 512KB in-flight).
* Jaise hi Receiver chunk 1–8 ka acknowledgement deta hai, agla set 9–16 send hota hai.
* Agar User B beech me disconnect ho gaya, toh sender ke RAM me se wo 512KB drop ho jayega, aur disk pointer freeze ho jayega. No file corruption, zero memory leak.

---

### 3. Edge-Case Matrix: 50k Concurrent Users Par Kaise Handle Hoga? 🔍

| Chaos Scenario | System Execution (Zero Cloud Storage) | Developer Infra Impact |
| --- | --- | --- |
| **Both A & B Online** | STUN se direct P2P NAT punch hoga. File bits direct phone-to-phone flow honge. | **Server Cost = ₹0**, Zero bandwidth consumed. |
| **B Bar-Bar Disconnect Ho Raha Hai** | Chunk-level checkpointing + ICE Restart. Receiver har reconnection par `ACK_OFFSET: 45.2MB` bhejega; transfer 45.2 MB se hi resume hoga. | **Zero Cloud impact**, local storage handle karti hai. |
| **A Bhej Raha Hai, B Permanently Offline Ho Gaya** | Sender ke device par transfer `PAUSED_PEER_OFFLINE` me chala jata hai. Sender ki device par ek passive listener rehta hai jo B ke next FCM wake-up ka wait karega. | **Server Cost = ₹0**. |
| **Strict NAT / Direct P2P Blocked (High Scale)** | Direct P2P fail hone par P2P data TURN par shift hota hai. Lekin 50k users par bandwidth leak rokne ke liye: **Rate Limiting (e.g., 1.5 MB/s cap per relay transfer)** lagaya jata hai. | Minimal VPS bandwidth cost. |
| **A File Bhejte Bhejte App Switch / Phone Lock Kar Deta Hai** | Android `ForegroundService` with notification channel and `WifiLock` active rehta hai. Transfer background me 100% complete hota hai. | **Zero System crash**. |

---

### 4. Isko 50k Scale Par Sabse Efficient & Cost-Saving Kaise Banayein? 🚀

1. **Lazy Wake-Up (Battery & Push Cost Reduction):**
* Agar A ne B ko 10 files bheji hain aur B offline hai, toh har file par 10 alag-alag FCM Push notifications mat bhejo.
* Server notifications ko **Debounce / Batch** karega (1 notification: *"3 new incoming file requests from Alex"*). Isse user ka phone spam nahi hoga aur battery drain zero rahegi.


2. **P2P STUN Hole-Punching Optimization:**
* 85%+ connections direct P2P ban sakte hain agar aap multiple free public STUN servers ka pool maintain karein (Google STUN, Cloudflare STUN). Isse 50k me se 42k+ users ka data direct phone-to-phone travel karega, server relay par zero load aayega.


3. **Disk I/O Throttling on Budget Phones:**
* 50k users me se bohot se log low-end Android phones use karenge jinki eMMC flash storage slow hoti hai.
* Agar sender 5G par 50 MB/s se chunks bhej raha hai aur receiver ka phone utni fast disk write nahi kar pa raha, toh app buffer overflow se crash ho jayega.
* **WebRTC Backpressure (`bufferedAmount`):** Jab receiver ka write queue bharega, WebRTC automatically sender ko throttle karega taaki network speed receiver ki phone disk speed se match ho jaye.


4. **Relay Budget Safeguard (Circuit Breaker):**
* Direct P2P ke liye unlimited size allow karein (chahe 10 GB ho).
* Lekin agar transfer fallback **TURN Relay** par jata hai, toh app limit enforce kar sakta hai (e.g., max 100 MB per file over relay) ya user ko prompt kare: *"Direct connection not possible. Switch to same Wi-Fi or wait for better network."* Isse developer ka monthly server bill hamesha predictable rahega.



Jab **Sender (User A)** khud in saare chaotic scenarios me phans jaye (User A bar-bar offline-online ho raha ho, internet switch kar raha ho, phone switch off ho jaye, ya app background me kill ho jaye), tab direct P2P me challenge aur zyada badh jata hai — kyunki **data ka source (file) hi gayab ho raha hai**, aur humare paas beech me koi cloud server nahi hai jahan file pehle se rakhi ho.

Is condition ko handle karne ka exact technical mechanics aur state recovery system:

---

### 1. Direct Concept Breakdown 🎯

Jab User A disconnect hota hai, system **"Receiver Freezes, Sender Recovers"** model par chalta hai:

* **Receiver (User B):** Panic nahi karega aur na hi file delete karega. User B ka app download state ko `WAITING_FOR_SOURCE` me daal kar **freeze** kar dega (incomplete file `.part` ya `.downloading` format me disk par safe rahegi).
* **Sender (User A):** Jaise hi dobara network ya active state me aayega, uska local queue worker check karega: *"Kya koi outbound stream adhoori chhooti thi?"* Aur wahan se background negotiation shuru karega.

---

### 2. Underlying Tech Stack & Mechanics ⚙️

#### A. Scenarios & Exact Technical Mechanics

| User A Scenario | Problem | Technical Handling (Zero Cloud Storage) |
| --- | --- | --- |
| **A Bar-Bar Disconnect Ho Raha Hai (Flapping)** | 4G/Wi-Fi switch ho raha hai, socket tut raha hai. | **Lease Heartbeat (TTL):** Receiver 30-45 seconds tak socket drop hone ke baad bhi peer session ko memory me zinda rakhta hai. Sender ka **ICE Restart** hit hote hi bina handshake scratch se kiye streaming wahi se resume ho jati hai. |
| **A Ka Phone Switch Off / Battery Dead** | File ka source physical world se gayab ho gaya. | Receiver 60 seconds baad session close karke download ko `PAUSED_PEER_UNREACHABLE` mark kar deta hai. Receiver ki storage me jitne chunks aaye the, unka checksum save rehta hai. Jab ghanton baad bhi A phone on karega, connection silently pair ho jayega. |
| **A Ke Phone Par OS Ne App Kill Kar Diya** | Low RAM ya aggressive battery saver ne app band kar diya. | **WorkManager + Persistent State:** Transfer start hote hi status Room DB me `STATE_IN_PROGRESS` tha. Jab bhi user app kholega ya OS background maintenance trigger karega, `WorkManager` pending state dekh kar automatic resume trigger kar dega. |
| **A Ne File Transfer Ke Beech Source File Rename / SD Card Eject Kar Diya** | Sender ke storage se actual file ka pointer toot gaya. | Android ka `ContentResolver` file descriptor check karega. Agar file unreadable ho gayi, toh A receiver ko `ABORT_FILE_UNAVAILABLE` error packet bhejega taaki B ka storage choke na ho. |
| **A Ek Sath 5 Alag Users Ko Bhej Raha Tha Aur A Ka Net Slow Ho Gaya** | A ka uplink (upload speed) choke ho gaya, saare 5 transfers crash hone lagenge. | **Token-Bucket Dynamic Fair Queuing:** App upload bandwidth ko actively monitor karta hai. Agar bandwidth 200 KB/s ke niche giri, toh app 4 transfers ko auto-pause karke sirf **1 single priority transfer** ko pipe karega, taaki kam se kam ek file complete ho sake. |

#### B. Receiver Side Ka State Machine (The Safety Lock)

Receiver device par ek temporary file banti hai:
`xyz_video.mp4.nxdl` (NX Download File)

* Jab tak last chunk verify nahi hota, yeh file user ki gallery ya file manager me show nahi hoti.
* Iske sath ek **Index Map (`.bitmap`)** hota hai.
* Agar User A 10 baar bhi disconnect ho, Receiver ko pata hota hai: *"Mere paas Byte 0 se Byte 14,800,000 securely written hain."*

---

### 3. Feasibility Check 🔍

* **Feasibility:** **100% Practical** (Resilient P2P systems jaise BitTorrent clients, Syncthing aur AirDrop isi architecture par chalte hain).
* **Critical Edge-Case to Guard:**
* **Storage Leak on Receiver Side:** Agar User A ne 50% bhej kar app delete kar diya ya kabhi wapas online nahi aaya, toh User B ke phone me adhoori 2 GB file padi reh sakti hai.
* **Solution:** Receiver app me ek **Transfer TTL / Expiry Policy** (e.g., *Auto-purge incomplete files after 7 days if sender never reconnects*) lagayi jati hai.



---

### 4. Isko Aur Effective & Efficient Kaise Banayein? 🚀

1. **Persistent SAF URIs (Storage Access Framework):**
* Android me file pick karne ke baad standard file path store na karein. App restart hone par permission expire ho sakti hai.
* Android ke `takePersistableUriPermission()` API ko use karein, taaki phone reboot hone ya app kill hone ke baad bhi background service ke paas us file ko bina user confirmation ke read karne ka access barkarar rahe.


2. **Reverse Rendezvous (Receiver Pings Sender):**
* Agar User A offline hone ke baad wapas online aata hai, server A ko load nahi dega. Server sirf lightweight signaling message send karega: *"User B is still waiting on Session #402"*. A ka phone instantly socket wapas wire up kar dega.


3. **Adaptive Upload Throttling (Battery & Thermal Protection on A):**
* Agar User A 40 degree garmi me ya travel karte waqt file send kar raha hai, continuous WebRTC encryption se phone overheat ho sakta hai.
* Phone ke **Thermal API** ko monitor karein: Agar device state `THERMAL_STATUS_SEVERE` hit kare, toh chunk dispatch rate 50% slow kar dein taaki phone cool down ho sake aur OS app ko forcefully kill na kare.


4. **Offline Mesh Handoff (The Hybrid Bridge):**
* Agar A ka internet chala gaya lekin B uske aas-pass (same room, hall, ya bus me) hai:
* App turant internet failover detect karke **Wi-Fi Direct / Local Hotspot Handshake** par seamless switch kar lega — bina user ko transfer cancel karne ko bole! File internet se chhoot kar direct offline high-speed radio par resume ho jayegi.