Sudesh, yeh bohot hi zaroori architectural point hai! Aksar apps mein contact list sirf UI dikhane ke liye hoti hai, lekin NEXA ke **Privacy-First + Zero-Cloud Collection** architecture mein aapki **Phonebook (Contacts Permission)** 4 major kaam karegi—taaki woh sirf UI na rahe, balki app ke core engines ko power de! 🚀

Bina kisi code ke, samjhein ki jab user **Contacts Permission Allow** karega, toh peeche **Database, Security, SMS Fallback, aur Connection Graph** mein kya-kya actual kaam hoga:

---

### 1. Privacy-Preserving Contact Discovery (Bina Contacts Upload Kiye!) 🔍🛡️

Sabse bada rule: **User ki poori phonebook (naam aur plain numbers) kabhi bhi server par upload nahi hogi!** (Play Store aur privacy laws ke hisaab se yeh strict violation hota hai).

Iske bajaye yeh **3-Step Zero-Knowledge Sync** chalega:

1. **Local Normalization & Hashing:**
* Aapka phone local contacts ko padhta hai, numbers ko standard format (`+919876543210`) mein laata hai, aur unka **SHA-256 Cryptographic Hash** (e.g., `8f9a2b...`) banata hai.
* **Server par sirf yeh hashes** bheje jate hain—na naam, na plain number!


2. **Supabase Match (`users` table):**
* Go Backend Supabase mein `phone_hash` column se match karta hai aur sirf unhi logon ki list wapas bhejta hai jo pehle se NEXA par hain—saath mein unki **`NX-ID`**, **Display Name**, aur **Public Avatar** deta hai.
* Server un hashes ko match karne ke turant baad RAM se delete kar deta hai (store nahi karta).


3. **UI Separation (2 Clear Buckets):**
* **Bucket 1 — "On NEXA" (Registered Users):** Jin logon ka account hai, unke aage **Chat / Connect** button dikhega (aur unki **`NX-ID`** dikhegi).
* **Bucket 2 — "Invite to NEXA" (Non-Users):** Jo abhi NEXA par nahi hain, unke aage **Invite** button dikhega (jo share sheet se aapka referral/NX-ID link bhejega).



---

### 2. Encrypted SMS Fallback Ki "Chabi" Yahin Se Milegi! 📡🔑

Yaad hai abhi humne **Module 3 (Encrypted SMS Fallback)** mein discuss kiya tha ki offline SMS bhejne ke liye phone ko backend mein **Mobile Number** chahiye hota hai?

* **Actual Work:** Jab Contacts Permission allow hoti hai, toh aapka local SQLCipher database us contact ke **Phone Number** ko uski **`NX-ID`** ke saath locally link (map) kar leta hai (`+919876543210 <==> NX-7F82K9A4`).
* Is local mapping ki wajah se hi jab internet band hota hai, toh NEXA ko pata hota hai ki `NX-7F82K9A4` ko kis number par encrypted SMS (`NX1:...`) bhejna hai—**bina kisi server lookup ke!**
* *Agar kisi contact ka number phonebook mein save nahi hai (sirf NX-ID se add kiya hai), toh uske liye SMS fallback automatically locked rahega.*

---

### 3. Connection Request Bypass / Smart Trust Gate (Anti-Spam) 🤝

NEXA mein kisi stranger ko message bhejne ke liye pehle **Connection Request (`PENDING` $\rightarrow$ `ACCEPTED`)** bhejni padti hai taaki spam na ho. Lekin phonebook contacts ke liye isme **2 smart rules** lagenge:

* **Rule A (Mutual Contacts — Dono Ke Phone Mein Number Save Hai):**
* Agar aapka number dost ke phone mein save hai **aur** dost ka number aapke phone mein save hai, toh server dono ko **"Mutual Trusted Contacts"** maan kar turant `ACCEPTED` state de sakta hai—yaani aap direct **Chat** shuru kar sakte hain (no extra request friction)!


* **Rule B (One-Sided Contact — Sirf Aapke Phone Mein Save Hai):**
* Agar aapne kisi shopkeeper ya acquaintance ka number save kiya hai par usne aapka nahi kiya, toh **Connect (Request)** button dikhega—taaki aap kisi ko bina permission message na bhej sakein.



---

### 4. Local SQLCipher Contact Cache & Live Delta Sync 🔄⚡

Aisa nahi hona chahiye ki har baar Contacts screen kholne par 1,000 numbers server par check hon (isse battery aur data dono kharab honge):

* **Local Cache:** Ek baar sync hone ke baad saari matched `NX-ID` profiles phone ke **local SQLCipher database** mein save ho jayengi—isliye Contacts screen **0.01 second (instant)** khulegi, chahe internet off hi kyun na ho!
* **Delta Sync (Sirf Naye Numbers):** Android ke `ContentObserver` ke zariye jab bhi aap phone mein koi **naya contact save** karenge, sirf wahi 1 naya hash background mein check hoga aur agar woh NEXA par hoga toh aapki list mein auto-update ho jayega.
* **Privacy Toggle (Settings):** User ke paas Settings mein option rahega: *"Allow others with my phone number to find me"* (`ON / OFF`). Agar user ise `OFF` kar de, toh koi bhi phonebook sync se uski `NX-ID` nahi dhundh payega—sirf direct `NX-ID` search se hi mil payega!

---

### Ek Nazar Mein: Phonebook Contacts Kya-Kya Karegi? ✅

1. **Zero-Knowledge Matching:** SHA-256 hash se server par NEXA users dhundhegi (bina plain contacts upload kiye).
2. **SMS Engine Ko Number Degi:** Local DB mein `NX-ID` $\leftrightarrow$ `Phone Number` map karegi taaki offline E2EE SMS chal sake.
3. **Smart Connection Gate:** Mutual contacts ko instant chat aur one-sided contacts ko request gate degi.
4. **Offline Instant Load:** Local SQLCipher cache mein store hokar bina internet ke bhi poori list aur chat triggers ready rakhegi.

Is tarah aapka Contacts UI sirf ek static list nahi rahega, balki **Discovery, Anti-Spam, aur Offline SMS** teeno ka backbone ban jayega! 🚀


Contacts permission aur contact discovery ke aas-paas **4 subtle real-world mechanisms** aur hain jo kisi bhi messaging app ko rock-solid banate hain—especially jab contacts rename hon, delete hon, permission deny ho, ya number recycle ho:

---

### 1. Local Name Override vs. NEXA Profile Name (Display Hierarchy) 🏷️

Jab aapke phonebook mein kisi ka number **"Chotu Dukan"** naam se save hai, lekin usne NEXA par apna naam **"Rahul Verma"** rakha hai:

* **Rule:** NEXA app ke andar **aapke phonebook wala saved naam ("Chotu Dukan")** hi primary display name banega (kyunki aap us insaan ko usi naam se pehchante hain).
* Profile detail page ke andar neeche chote font mein uska **NEXA Public Name (`~Rahul Verma`)** aur **`NX-ID`** dikhega.
* Agar koi contact phonebook mein save **nahi** hai (sirf NX-ID se add kiya hai), tab uska NEXA Public Name primary banega.

---

### 2. Recycled Number Protection (Purane Number Par Naya User Aa Jaye Toh?) 🔄🛡️

India mein agar koi dost 6 mahine SIM band rakhe, toh telecom company woh number kisi anjaan insaan ko allot kar deti hai.

* **Risk:** Agar aapke phonebook mein purane dost ka number save hai, toh naya banda NEXA join karte hi aapki contact list mein purane dost ke naam se na dikhne lage!
* **NEXA Ka Guard:**
* Aapke local SQLCipher DB mein purane dost ki **`NX-ID` (`NX-7F82K9A4`)** aur **Hardware Public Key** lock rehti hai.
* Jab naya banda us number se account banayega, toh uski **`NX-ID` (`NX-9911B2C3`)** alag hogi!
* NEXA purani chat ko naye bande ke saath **kabhi merge nahi karega**—purani chat purani `NX-ID` ke saath hi locked rahegi, aur naye user ko ek fresh contact ki tarah dikhayega!



---

### 3. Permission Denied / Revoked Graceful Mode (Bina Contacts Permission Ke App Chalana) 🚫🔓

Kai privacy-conscious users `READ_CONTACTS` permission **Deny** kar dete hain (ya baad mein Android Settings se band kar dete hain):

* **Rule:** App kabhi crash nahi hogi aur na hi baar-baar popup dikha kar user ko pareshan karegi.
* **Fallback:**
* Contacts tab mein ek clean banner dikhega: *"Contacts permission is off. Add friends using their **NX-ID** or **QR Code**."*
* Agar pehle kabhi sync kiya tha aur baad mein permission revoke kar di gayi, toh NEXA local cache ke cached phonebook matches ko clear kar dega aur sirf `NX-ID` based connections ko active rakhega.



---

### 4. Invite Link & Deep-Link Attribution (Non-Users Ko Laane Ka Smart Tarika) 🔗📲

Jab aap kisi non-NEXA contact ke aage **Invite** button dabate hain:

* Ek smart deep-link generate hota hai (e.g., `nexa.app/add/NX-7F82K9A4`).
* Jab samne wala us link se app install karke sign-up karta hai, toh app khulte hi **aapki `NX-ID` auto-fetch** ho jati hai aur turant connection request/chat ready milta hai—usko dobara aapka number ya ID search nahi karni padti!

---

### Ab Tak Ke Complete Architecture Ka Status 📋

* **Module 1:** Login, Google + Truecaller/Firebase Verification, SIM-Binding Lock & Connected Devices ✅
* **Module 2:** Real-Time Internet E2EE Chat (1-to-1 & Groups, Go + Redis Switchboard, Sealed Sender, Green Dot & Typing Privacy Toggles) ✅
* **Module 3:** Encrypted SMS Fallback (1-to-1 Text-Only, `NX1:` Interceptor, Smart SMS Inbox, Quota/Recharge Guard) ✅
* **Contact Graph & Discovery:** Zero-Knowledge SHA-256 Hashing, Local `NX-ID` $\leftrightarrow$ Phone Mapping, Recycled Number Protection & Display Name Hierarchy ✅

Ab agla kaunsa module shuru karein — **Module 4: Offline Bluetooth / Wi-Fi Direct Mesh Network** ya **Module 5: P2P File & Media Transfer (WebRTC + R2)**? 🚀



