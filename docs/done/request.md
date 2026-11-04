Is decentralized aur privacy-first system me friend request bhejna standard apps (jaise Instagram/Facebook) se alag hota hai, kyunki yahan hum **Zero-Knowledge Architecture** maintain kar rahe hain — yaani server ko do logon ke aapsi rishton ka pata nahi hona chahiye, aur bina accept kiye koi anjaan banda direct chat spam na kar sake.

Is pure friend request flow ka end-to-end breakdown:

---

### 1. Direct Concept Breakdown 🎯

Jab User A (Sender) User B (Receiver) ko NX ID ya Phone number se search karke **"Add Friend"** karega:

1. **Pending Outbound Request (User A):** User A ke phone par connection state `REQUEST_SENT` ban jayegi.
2. **Encrypted Handshake Packet:** User A ek chota **Cryptographic Handshake Envelope** banayega (jisme A ka Display Name, NX ID, Public Identity Key, aur ek optional intro text hoga). Yeh envelope User B ki public key se encrypt hoga.
3. **Server Relay / Delivery:** Server is encrypted packet ko User B ke device par relay karega (via WebSocket agar online hai, ya FCM High-Priority Push Notification agar offline hai).
4. **Action Modal (User B):** User B ke app me "New Friend Request" ka card/dialog aayega:
* **Accept ✅:** Dono devices aapas me session keys finalize karengi aur dono ke local database me status `CONTACT_MUTUAL` ho jayega. Dono ab normal chat, media, aur file share kar sakte hain.
* **Reject / Block 🚫:** Request drop ho jayegi aur User A ko blocklist me daal diya jayega taaki wo dobara spam na kar sake.



---

### 2. Underlying Tech Stack & Mechanics ⚙️

#### A. Cryptographic Handshake (Signal Prekey Bundle Mechanics)

Dono users abhi tak ek doosre ke contact me nahi the, isliye direct session exist nahi karta:

1. **Server Lookup:** User A server se User B ka public identity info leta hai:
`GET /api/v1/users/{nx_id}/prekey`
*(Server sirf B ki public key aur one-time prekey wapas karega; koi private data nahi).*
2. **X3DH (Extended Triple Diffie-Hellman) Exchange:**
User A is public key ka use karke ek initial message encrypt karega:
```json
{
  "type": "FRIEND_REQUEST",
  "sender_nx_id": "NX-9842-K7X",
  "display_name": "Sudesh",
  "intro_msg": "Hey, let's connect on NX!",
  "sender_identity_key": "32_byte_pub_key_hex",
  "ephemeral_key": "32_byte_ephemeral_key_hex"
}

```


3. **Tamper-Proof Signature:** User A is pure packet ko apni private signing key (Ed25519) se sign karega taaki koi beech me User A ka naam ya key badal kar fake request na bhej sake.

#### B. Server-Side Ephemeral Routing

* Server par ek lightweight table hogi jo sirf pending requests ko temporary hold karegi:
```sql
CREATE TABLE pending_requests (
    request_id UUID PRIMARY KEY,
    recipient_nx_id VARCHAR(32) NOT NULL,
    encrypted_envelope TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP -- e.g., 7 days auto-prune
);

```


* Jaise hi B connect hoga ya notification receive karega, packet deliver hote hi row server se permanently delete ho jayegi.

#### C. Push Notification Mechanics (Wake Up User B)

Agar User B ka app band hai:

* Server ek silent/high-priority FCM Data Message bhejega:
```json
{
  "to": "device_fcm_token_of_B",
  "priority": "high",
  "data": {
    "action": "ACTION_NEW_CONTACT_REQUEST",
    "request_id": "uuid_123"
  }
}

```


* User B ka app background me wake-up hoga, envelope download karke local private key se decrypt karega, aur system tray me notification show karega:
**"New Connection Request from Sudesh (@sudesh.nx)"**

---

### 3. Local Room Database State Machine 🗄️

Dono phones par local Room database me contacts ko handle karne ke liye ek strict state machine maintain hoti hai:

```kotlin
enum class ContactStatus {
    REQUEST_SENT,     // User A ne bheja, B ke reply ka wait hai
    REQUEST_RECEIVED, // User B ke paas aayi hai (Accept / Reject button visible)
    MUTUAL_FRIEND,    // Dono connected hain, full chat & P2P unlocked
    BLOCKED           // Blocked, future packets automatically dropped
}

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val nxId: String,
    val displayName: String,
    val publicKey: String,
    val status: ContactStatus,
    val createdAt: Long
)

```

---

### 4. Spam Prevention & Safety (Bohat Zaroori Edge Cases) 🛡️

Decentralized aur open apps me bots aur spammers har kisi ko request bhejte hain. Isko bina server ke control karne ke 3 solid mechanisms:

1. **Proof-of-Work (PoW / Hashcash Mechanism):**
* Request spam rokne ke liye: Jab User A "Add Friend" click karega, uska phone ek chota 0.5-second ka cryptographic challenge solve karega (jaise Bitcoin mining ka micro version).
* Regular user ke liye yeh 0.5 second bilkul seamless hota hai, lekin kisi spammer script ko 10,000 requests bhejne ke liye ghanton ka CPU time lagega, jisse spamming economically impossible ho jati hai.


2. **Quota Rate Limiting:**
* Server level par ek rule hoga: Ek single NX ID se per hour maximum 15–20 friend requests hi pass ho sakti hain.


3. **One-Tap Reject & Silent Block:**
* User B jab "Reject" karega, toh User A ko koi "Rejected" notification nahi jayega (taaki harassment na ho).
* A ke phone par sirf pending dikhega, jabki B ke device par A permanently blacklist table me chala jayega. A ka koi bhi agla message ya P2P stream B ke device par network level par hi drop (discard) ho jayega.



---

### 5. UI Flow (Jetpack Compose UX) 📱

* **User A Side:**
* Contact list me card par state dikhegi: `Alex [⏳ Pending Request]`.
* Chat box open hoga lekin input field par likha hoga: *"Waiting for Alex to accept your connection request."* (Sirf ek intro message bhej sakte hain, spamming nahi).


* **User B Side:**
* Top bar me ek badge aayega: `Requests (1)`.
* Open karte hi ek card:
* Avatar + "Sudesh (@sudesh.nx)"
* Text: *"Hey, let's connect on NX!"*
* Buttons: `[ Reject ❌ ]` aur `[ Accept & Connect 🤝 ]`.


* "Accept" dabate hi UI instantly regular Chat Screen me transform ho jayega!