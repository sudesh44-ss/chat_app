Jab aap apna mobile number change karenge, toh NEXA mein number update karne ka process bilkul smooth rahega—**aapki `NX-ID`, aapki saari chats, aapke groups, aur aapka Google Drive backup 100% wahi rahega**, sirf aapki profile ke peeche laga hua `phone_hash` aur `SIM-Binding lock` naye number par shift ho jayega! 🔄📱

Bina kisi code ke, **Settings $\rightarrow$ Account $\rightarrow$ Change Number** ka exact step-by-step UI flow aur backend process samjhein:

---

### 1. Step-by-Step "Change Number" Flow (UI & Process) 📲

```text
[ 1. Settings ──► Account ──► Change Number ]
         │
         ▼
[ 2. Warning & Info Screen ] ℹ️
  • Screen par clear dikhega:
    "Changing your number will migrate your NX-ID (NX-7F82K9A4), 
     connections, and groups to your new number. Your old number 
     will be unlinked."
  • User taps [ Next ]
         │
         ▼
[ 3. New SIM & Number Verification ] 🔐
  • User naya SIM card phone mein dalta hai (ya Dual-SIM mein 2nd slot select karta hai).
  • User apna Naya Mobile Number dalta hai (+91 XXXXXXXXXX).
  • 1st Priority: Truecaller 1-Tap Verify (₹0 Cost).
  • Fallback: Firebase Free Tier OTP.
         │
         ▼
[ 4. Hardware SIM-Binding Re-Lock ] 🛡️
  • Verify hote hi Android `SubscriptionManager` naye SIM slot ka `sim_iccid_hash` 
    capture karke Android Keystore mein lock kar leta hai.
  • Purane SIM slot ka lock release ho jata hai (ab purani SIM nikalne par app lock nahi hogi!).
         │
         ▼
[ 5. Backend Database Atomic Swap (Supabase + Redis) ] 🗄️
  • Go Server Supabase `users` table mein `NX-7F82K9A4` ke saamne:
    - Purana `phone_hash` DELETE karta hai.
    - Naya `phone_hash` UPDATE karta hai.
  • Agar naya number pehle kisi purane inactive account par tha (Recycled Number), 
    toh server use wahan se detach karke aapke account par attach kar deta hai.
         │
         ▼
[ 6. Silent Contact Notification & SMS Map Update ] 🔔
  • Aapke connected doston ke phones ko Go Server se ek silent control packet jata hai:
    `{ event: "NUMBER_UPDATED", nx_id: "NX-7F82K9A4", new_phone_hash: "..." }`
  • Unke phone ke local database mein aapki `NX-ID` ke neeche naya number link ho jata hai 
    (taaki offline SMS ab naye number par jaye!).
  • Done! Screen par toast aayega: "✅ Number updated successfully!"

```

---

### 2. Is Process Ke 4 Zaroori Rules (Edge-Cases Covered) ⚙️

1. **Purani SIM Ki Zaroorat Hai Ya Nahi?**
* **Nahi!** Kyunki aap pehle se apne phone mein **Logged-In** hain aur aapke paas **Google Master Identity + Hardware Device Key** pehle se active hai, isliye aapko purani SIM par OTP mangwane ki zaroorat nahi hai (agar purani SIM band ya kharab ho gayi ho tab bhi number change ho jayega).
* Sirf **Naye Number** ko verify karna zaroori hai (taaki koi doosre ka number na daal sake).


2. **Doston Ko Kya Dikhega?**
* Aapke saare doston aur groups ki chat screen **bina kisi interruption ke chalti rahegi** (kyunki chat **`NX-ID`** se judi hai).
* Chat ke andar ek chota sa system note dikhega: *"ℹ️ Sudesh changed their phone number."*
* Jinke phonebook mein aapka naya number save hai, unke local SQLCipher DB mein aapka **Encrypted SMS fallback** automatically naye number se jud jayega; aur jinke paas aapka naya number save nahi hai, unke phone mein purane number ka SMS link delete ho jayega taaki galti se purane number par SMS na jaye!


3. **E2EE Encryption Keys Par Kya Asar Padega?**
* Agar aapne **sirf SIM badli hai (Phone wahi hai)** $\rightarrow$ Aapki Hardware Keystore keys bilkul same rahengi, koi security code change nahi hoga.
* Agar aapne **Phone aur SIM dono badle hain** $\rightarrow$ Pehle naye phone par Google Sign-In + Naya Number verify karke login hoga, aur doston ko *"Security code changed"* ka standard alert dikhega.


4. **Abuse / Spam Prevention (Cooldown Rule):**
* Koi user baar-baar din mein 10 baar number change karke system ko abuse na kare, isliye backend par ek simple rate-limit rahegi: e.g., **max 2 number changes per 30 days** (emergency ke alawa).



---

### Summary 🌟

Number change karne par aapki **`NX-ID` (`NX-7F82K9A4`)**, **chats**, **groups**, aur **Google Drive backup** kuch bhi nahi badlega—sirf aapki `NX-ID` ke peeche laga **Mobile Number + SIM Lock** naye SIM par shift ho jayega aur aapke doston ka offline SMS route automatically update ho jayega! 🚀


Sudesh, **"Change Number"** ke waqt **Security Verification** bohot zaroori hai—kyunki agar koi hacker kisi tarah aapke phone ko unlock karke ya aapke Google account ka access lekar chupke se aapka number apne number se badal de, toh woh aapki **`NX-ID`** hijack kar sakta hai!

Isliye **Change Number** karte waqt **3-Layer Security Verification** lagega (bina kisi extra cost ke):

---

### 1. Layer 1: Local Biometric / Device PIN Lock (Phone Ke Andar) 🔐

* Jaise hi user **Settings $\rightarrow$ Account $\rightarrow$ Change Number** par tap karega, sabse pehle phone ka **Fingerprint / Face Unlock / Device PIN** (`BiometricPrompt`) maanga jayega.
* **Fayda:** Agar aapka phone unlocked table par rakha hai, toh koi dost ya stranger aapke account ka number nahi badal payega.

---

### 2. Layer 2: New Number + Physical SIM Proof (Hardware + Carrier Check) 📱📡

Naye number ko verify karne ke liye sirf OTP kaafi nahi hai—**teeno cheezein ek saath** verify hongi:

1. **Physical SIM Presence (`SubscriptionManager`):** Android system check karega ki jis naye number ko aap daal rahe hain, uski physical SIM sach mein isi phone ke SIM slot mein lagi hai ya nahi (kisi doosre phone mein padi SIM ka number nahi daal sakte).
2. **Ownership Proof (₹0 Cost):**
* **1st Priority:** Truecaller 1-Tap Verification.
* **Fallback:** Firebase Free Tier OTP (ya Reverse SMS verification token).


3. **Hardware Keystore Signature:** Phone ke **StrongBox / Android Keystore** ki existing Private Key se ek digital signature sign hoga, jo Go Server ko batayega: *"Haan, yeh request usi original registered phone se aa rahi hai jisme account logged-in tha!"*

---

### 3. Layer 3: Google Master Identity Confirmation + Alert Notification 🛡️📧

* **Google Re-Auth Token:** Backend Google Sign-In ka silent fresh token check karega ki user ka Google Master Account abhi bhi valid aur active hai.
* **Security Alert:** Number change hote hi aapke linked **Gmail par ek automated security email** chala jayega (*"Your NEXA ID `NX-7F82K9A4` was migrated to a new mobile number"*), aur aapke Connected Devices log mein timestamp record ho jayega.

---

### Ek Special Edge-Case: Agar Aapne Phone Aur Number DONO Ek Saath Badle Hain Toh? 🔄

Maan lijiye purana phone kharab ho gaya aur purani SIM bhi band ho gayi—ab aap **naye phone + nayi SIM** par aaye hain:

1. Naye phone mein pehle **Purane Google Account** se Sign-In karenge (jisse server pehchan lega ki aap hi `NX-7F82K9A4` ke maalik hain).
2. Screen par option aayega: **"Lost Access to Old Number? Update to New Number"**.
3. Nayi SIM ka Truecaller/OTP + Hardware Keystore bind hote hi—kyunki phone aur SIM dono naye hain—server **24-Hour Security Hold** (ya purane phone par push alert) ke saath purane number ko detach karke naye number ko attach kar dega!

---

### Summary 🌟

Number update karne ke liye:

1. **Fingerprint / Device PIN** (Local physical check)
2. **New SIM in Slot + Truecaller/OTP** (New number ownership)
3. **Existing Hardware Keystore Signature + Google Token** (Account maalik hone ka proof)

Teeno verify hone ke baad hi backend `phone_hash` ko swap karega—100% hijack-proof! 🚀