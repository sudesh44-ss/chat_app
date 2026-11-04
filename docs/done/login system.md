Yeh onboarding aur identity discovery flow user-friendly hone ke sath-sath technical privacy architecture me smoothly fit ho sakta hai.

Is pure onboarding pipeline aur multi-way contact discovery (Phone Number, NX ID, QR Code) ka breakdown:

---

### 1. Direct Concept Breakdown 🎯

#### Step-by-Step Onboarding Pipeline

1. **Google Sign-In (OAuth 2.0):** User Google account se authenticate karega. Isse system ko ek verified Gmail ID aur spam-bot protection mil jati hai.
2. **Profile Setup Screen:** Name aur optional details add hongi.
3. **Phone Number + OTP Verification:** SIM ownership confirm hoti hai taaki address-book matching ho sake.
4. **Local Keypair & NX ID Generation:**
* Device ke hardware me private/public keypair generate hoga.
* System user ko ek unique **NX ID** assign karega (e.g., `NX-9842-K7X` ya customizable handle jaise `@alex.nx`).


5. **Contact Discovery (3 Ways):**
* **Phone Number:** Agar kisi ke phone me contact saved hai, toh standard WhatsApp style match.
* **NX ID:** Agar number share nahi karna, toh NX ID search karke direct chat request.
* **QR Code:** Instant local handshake (ek hi scan me NX ID + Public Encryption Key dono swap).



---

### 2. Underlying Tech Stack & Mechanics ⚙️

#### A. Authentication & Verification

* **Google Auth:** **Google Credential Manager API** (Android ka latest unified authentication framework jo legacy Google Sign-In SDK ko replace karta hai).
* **Phone OTP:** **Firebase Phone Auth** ya standard SMS gateway (Msg91, Twilio, Fast2SMS).
* *Android SMS Retriever API:* Background me SMS auto-read karega bina user se sensitive SMS permission maange.



#### B. Cryptographic Identity & NX ID Mapping

* **Public Key Derived NX ID:**
* NX ID ko random string banane ke bajaye user ke **Ed25519 Public Identity Key** ka hash use karein (e.g., Public key ke SHA-256 hash ko Base32 me encode karke 8-10 characters ka clean format banayein).
* *Fayda:* NX ID fake ya spoof nahi ki ja sakti kyunki wo mathematically user ki encryption key se linked hoti hai.


* **Key-Directory Server (Public Lookup Table):**
* Server par ek lightweight lookup table rahegi:
```sql
CREATE TABLE identity_directory (
    nx_id VARCHAR(32) PRIMARY KEY,
    phone_hash VARCHAR(64) UNIQUE, -- SHA-256 with pepper
    email_hash VARCHAR(64) UNIQUE,
    public_identity_key TEXT NOT NULL,
    prekey_bundle TEXT NOT NULL
);

```





#### C. Contact Discovery Mechanics (Privacy-Preserving)

* **Phone Number Match:** Contacts ka raw phone number server par upload karne ke bajaye client-side **Hashed Contact Discovery (Private Set Intersection / Bloom Filter)** use karein. Phone contacts ko hash (`SHA-256(country_code + number)`) karke server lookup par bhejte hain.
* **QR Code Flow:**
* QR Code ek compact payload contain karega:
`nx://user?id=NX-9842-K7X&pk=a9f4c3...&name=Alex`
* Camera scan karte hi dono devices ko internet ki bhi zaroorat nahi padegi—offline mesh ke liye bhi public keys instantly exchange ho jayengi.



---

### 3. Feasibility Check 🔍

| Feature | Feasibility | Practical Note |
| --- | --- | --- |
| **Google Auth + Phone OTP Flow** | **100% Practical** | Standard industry pattern hai, reliable aur smooth hai. |
| **NX ID + QR Code Sharing** | **100% Practical** | Telegram username aur Signal QR flow ki tarah seamless kaam karta hai. |
| **OTP SMS Cost Risk** | **Medium Risk (Cost)** | Phone OTP me har SMS par ₹0.15 - ₹0.30 (India) ya ₹1 - ₹5 (International) ka gateway charge lagta hai. Free tier khatam hone ke baad yeh developer ki jeb se lagta hai. |

---

### 4. Isko Aur Effective & Efficient (Cost-Saving) Kaise Banayein? 🚀

1. **OTP Gateway Cost Cutting (WhatsApp OTP / Missed Call Fallback):**
* SMS gateways kafi mehenge padte hain.
* Alternative: Agar Google Sign-In pehle hi ho chuka hai, toh OTP ko primary verification ke bajaye sirf un users ke liye rakhein jo **Phone Number Discovery** on karna chahte hain. Jo user sirf NX ID use karna chahta hai, wo SMS OTP skip kar sakta hai (zero cost for developer).
* Ya fir SMS ke sath WhatsApp OTP fallback use karein jo standard SMS se cheap padta hai.


2. **Offline-Ready QR Exchange:**
* Jab do log saamne baithe hon aur internet na ho, QR code scan karte waqt app unka local Bluetooth MAC/Peer ID aur X25519 public key ek saath locally bind kar dega. Isse wo internet aane se pehle hi **Day-0 se offline mesh me encrypted chat** shuru kar sakenge.


3. **Salted Hash Contact Sync (Server Privacy):**
* WhatsApp par users ka phone data server par rehta hai. Server par raw phone numbers save karne ke bajaye hamesha salted hash store karein. Agar kabhi database leak bhi ho jaye, toh koi phone numbers extract nahi kar sakega.


4. **NX ID Caching in Local Room DB:**
* Bar-bar server par public key verify karne ke request na jayein, isliye ek baar contact add hone ke baad uski public key aur NX ID ko device ke local encrypted database me permanently cache kar lein. Network calls 90% kam ho jayengi.