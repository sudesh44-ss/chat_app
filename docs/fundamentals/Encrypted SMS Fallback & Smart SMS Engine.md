Ab hum **Module 3: Encrypted SMS Fallback & Smart SMS Engine** par aate hain—jisme **Internet nahi hone par** bhi NEXA ke users aapas mein securely chat kar sakein, aur saath hi Google Play Protect / Play Store policy 100% safe rahe! 🛡️📱

Bina kisi code ke, SMS mode ke liye humein kya-kya rakhna hai aur yeh kaise kaam karega, uska complete architecture samjhein:

---

### 1. Sabse Pehle: Play Store Policy Guardrail (Crucial Rule) ⚠️

Google Play Store kisi bhi non-default SMS app ko background mein chupke se `SEND_SMS` ya `READ_SMS` permissions use karne par **ban/block** kar deta hai. Isliye NEXA mein SMS kaam karne ke **2 Clean & Safe Tarike** honge:

1. **Zero-Permission Intent Mode (Default — 100% Play Store Safe):**
* Jab internet nahi hai, NEXA message ko E2EE encrypt karke ek chota Base64 code banata hai (e.g., `NX1:a8f9k2...`) aur Android ke `ACTION_SENDTO` intent se seedha phone ke **Default SMS App (Google Messages)** ko khol deta hai—number aur encrypted text pehle se bhara hota hai, user bas **Send** dabata hai!
* Receiver jab us SMS ko copy karke (ya notification/share se) NEXA mein dalta hai, toh NEXA usko decrypt karke chat thread mein dikha deta hai.


2. **Default SMS Handler Mode (Optional Toggle — Full Automation):**
* Agar user NEXA ko apna **Default SMS App** (Google Messages ki jagah) set kar leta hai, tab NEXA ko legally `SEND_SMS` aur `RECEIVE_SMS` permissions mil jati hain—tab app background mein **100% automatically** encrypted SMS bhej aur receive kar sakti hai!



---

### 2. Encrypted SMS Engine Ke 5 Core Components ⚙️

Jab internet band ho aur NEXA SMS fallback par switch kare, toh yeh 5 cheezein kaam karengi:

#### A. Smart Transport Switch (Kab SMS Mode Active Hoga?) 🔄

* **Auto Mode:** Pehle app Internet (WebSocket) check karegi $\rightarrow$ Agar internet nahi hai toh Nearby BLE Mesh check karegi $\rightarrow$ Agar dono nahi hain, toh screen par ek badge aa jayega: **`📡 Offline • SMS Fallback Ready`**.
* **User Control:** User chat ke upar transport selector se manually bhi **`Encrypted SMS`** mode choose kar sakta hai (taaki galti se carrier ka SMS balance kharch na ho).

#### B. Ultra-Compact E2EE Compression (160 Characters Ki Limit!) 📦🔐

Standard SMS sirf **160 characters** ka hota hai (agar lamba hua toh 3–4 SMS ke paise kat-te hain).

* **Compression:** Text ko pehle **Zstd / Deflate** se compress kiya jata hai.
* **Lightweight Encryption:** Internet wale bade X3DH header ke bajaye, pehle se bani hui **Shared Session Key** se **ChaCha20-Poly1305** encryption lagti hai (jisme overhead sirf 28 bytes ka hota hai).
* **Base64 / Base85 Encoding:** Encrypted packet ek choti string ban jata hai:
`NX1:<4-char-msg-id>:<encrypted-payload>` (e.g., `NX1:9A2F:x7K9mP2q...`)
* **Multi-Part SMS Stitching:** Agar message 160 chars se bada hai, toh NEXA usko automatic tukdon (`1/3`, `2/3`, `3/3`) mein tod kar bhejega aur receiver ke phone par wapas jod kar decrypt karega!

#### C. Offline Delivery Receipt (`ACK` via SMS) ✔️

* Receiver ka phone jab encrypted SMS decrypt karta hai, toh (agar auto-SMS mode on hai) ek chota sa 20-character ka receipt wapas bhej deta hai: `NX1:ACK:9A2F`.
* Sender ke phone par clock icon (`🕒`) badal kar **SMS Delivered (`✓✓`)** ho jata hai!

#### D. Server & Database Activity (Zero Burden!) 🗄️

* **Server Activity:** **0%** (Kyunki internet hi nahi hai, SMS seedha Jio/Airtel ke telecom tower se receiver ke phone par ja raha hai).
* **Local SQLCipher DB Activity:** Sender aur receiver dono ke phone ke local database mein yeh message normal chat history ke saath save ho jata hai (bas message bubble ke corner mein ek chota sa **`SMS`** icon dikhega taaki pata rahe ki yeh offline SMS se aaya tha).
* **Internet Wapas Aane Par Sync:** Kyunki message dono phones ke local database mein pehle hi save aur decrypt ho chuka hai, isliye net aane par server ko dubara bhejne ki koi zaroorat nahi padti (no duplicate messages!).

#### E. Local Smart SMS Organizer (Normal Bank/OTP SMS Ke Liye) 🗂️

* Agar user NEXA ko Default SMS App banata hai, toh normal non-NEXA SMS (jaise Bank alerts, OTPs, Jio recharge messages) encrypted chat screen mein kachra nahi banayenge—wo alag se ek local **Smart SMS Inbox** tab mein **On-Device Rules** se filter hokar dikhenge (100% offline, server par kuch nahi jayega).

---

### 3. Kya SMS Mode Mein Bheja Ja Sakta Hai Aur Kya Nahi? ⚖️

* **Supported:** Text messages, Emojis, Delivery Receipts (`ACK`), aur Emergency Location Coordinates (e.g., `23.1815, 79.9864`).
* **Blocked in SMS Mode:** Photos, Videos, PDFs, Voice Notes, aur Typing Indicators (taaki user ka telecom balance aur SMS quota waste na ho).

---

### Summary: SMS Module Ka Complete Lock ✅

1. **Play Store Safe:** Non-default mode mein zero-permission `ACTION_SENDTO` intent; Default SMS handler banne par full auto-send/receive.
2. **100% E2EE & Compact:** ChaCha20-Poly1305 + compression (`NX1:...`) taaki minimum SMS segments lagen.
3. **Zero Server Cost:** Telecom network ke zariye seedha phone-to-phone delivery aur local SQLCipher DB mein storage.

Ab internet aur SMS dono modules lock ho chuke hain! Ab agla module **Offline BLE Mesh Network** discuss karein ya **P2P File & Media Transfer**? 🚀


Haan Sudesh! Bilkul sahi pakda aapne—woh bohot zaroori point hai. Jab koi encrypted SMS (`NX1:...`) aapke app se bheja jaye ya receive ho, toh woh **Google Messages (default SMS app) mein kachra na dikhe, balki seedha NEXA app ke andar hi us dost ki chat thread mein show ho**!

Iske liye humne **Default SMS Handler Mode + Smart Prefix Interceptor (`NX1:`)** ka architecture discuss kiya tha. Bina kisi code ke, yeh system exact kaise kaam karta hai, usko 4 simple steps mein samjhein:

---

### 1. Yeh Kaise Kaam Karega? (The `NX1:` Packet Interceptor) 🛡️📲

Jab user NEXA ko apna **Default SMS App** set karta hai, toh Android OS har aane aur jaane wale SMS ka poora control NEXA ko de deta hai:

```text
[ Incoming SMS Telecom Tower Se Aata Hai ]
                  │
                  ▼
[ NEXA SMS Interceptor Engine (Background Service) ]
                  │
                  ├── Check 1: Kya SMS `NX1:` prefix se shuru ho raha hai?
                  │       │
                  │       └── YES (Encrypted NEXA Message!) ✅
                  │             1. Isko normal SMS Inbox mein SAVE NAHI karega (kachra hidden rahega).
                  │             2. Sender ke number ko local Contacts/NX-ID se match karega.
                  │             3. Encrypted payload ko decrypt karke seedha NEXA ke 
                  │                local Chat Database (SQLCipher) mein daal dega!
                  │             4. User ko NEXA ki normal chat notification dikhegi! 🔔
                  │
                  └── NO (Normal Bank / OTP / Jio SMS) 📄
                        └── Yeh seedha "Smart SMS Inbox" tab mein chala jayega 
                            (Chat screen mein kabhi mix nahi hoga!).

```

---

### 2. Jab Aap NEXA Se SMS Bhejenge Toh Kya Hoga? (Outgoing Flow) 📤

1. **User Chat Screen Par Type Karta Hai:** Aap apne dost ki chat khol kar *"Main station pahunch gaya"* likhte hain aur **Send** dabate hain (jab internet nahi hai).
2. **NEXA App Ke Andar Hi Show Hoga:**
* Aapki chat screen par aapka original readable text (*"Main station pahunch gaya"*) hi dikhega—ek chote se **`SMS`** badge ke saath.
* Peeche NEXA engine us text ko encrypt karke `NX1:9A2F:x7K9mP2q...` banayega aur Android ke `SmsManager` se silently bhej dega.
* Kyunki NEXA hi Default SMS App hai, isliye **na koi bahari app khulegi** aur **na hi normal SMS list mein woh encrypted code dikhega**!



---

### 3. Agar User Ne NEXA Ko Default SMS App NAHI Banaya Hai Toh? (Non-Default Mode) ⚠️

Agar koi user Google Messages ko hi apna default SMS app rakhna chahta hai:

* **Bhejne ke waqt:** NEXA ke andar aapka readable message show hoga, aur `ACTION_SENDTO` se Google Messages khulega jahan user Send dabayega.
* **Receive karne ke waqt:** Jab samne wala `NX1:...` wala SMS bhejega, toh woh Google Messages mein aayega. NEXA user ko ek chota sa **"Share to NEXA / Paste to Decrypt"** shortcut ya notification listener option dega—jisse tap karte hi woh message decrypt hokar NEXA ki chat screen mein aa jayega!

---

### 4. Dono Ka Clean Separation (Zero Mixing) 🗂️

NEXA app ke andar **2 alag-alag tabs / drawers** rahenge:

1. **NEXA Chats (Main Screen):** Yahan Internet messages, Offline Mesh messages, aur `NX1:` Encrypted SMS—**teeno ek hi chat thread mein** smoothly dikhenge (bas bubble par chota sa `Net` / `Mesh` / `SMS` icon hoga taaki pata chale message kis raste se aaya hai).
2. **Smart SMS (System Inbox Tab):** Yahan sirf normal telecom SMS (Bank alerts, OTPs, Offers) on-device rules se categorize hokar dikhenge—encrypted codes yahan kabhi nahi dikhenge!

Is tarah aapke app se jaane aur aane wala har NEXA message **aapke app ki hi chat screen mein** dikhega, bina kisi confusion ke! 🚀

**Haan, SMS mode mein bhi 100% End-to-End Encryption (E2EE) rahegi!** 🔐 Even telecom operators (Jio, Airtel, Vi) aur government SMS gateways bhi aapka message nahi padh payenge—unko sirf ek random encrypted code (`NX1:9A2F:x7K9mP2q...`) dikhega.

Bina kisi code ke, SMS mein E2EE kaise kaam karega aur keys kahan se aayengi (jab internet band hai), uska exact mechanism samjhein:

---

### 1. Offline Hone Par Encryption Keys Kahan Se Aayengi? 🗝️

Internet wale **Double Ratchet** mein har message ke saath keys aage badhti rehti hain, jo offline SMS mein out-of-sync ho sakti hain. Isliye SMS ke liye hum **Pre-Shared Session Key Cache (Offline Key Vault)** rakhenge:

1. **Jab Internet On Tha (Connection Accept Hone Par):**
* Jab aap aur aapke dost pehli baar NEXA par connect hue the, tab aapke phones ne **X3DH Handshake** karke ek **Master Shared Secret Key** banayi thi aur apne phone ke **Android Keystore (Hardware Chip)** mein save kar li thi.


2. **Jab Internet Band Hai (SMS Mode):**
* Phone ko internet se koi key mangne ki zaroorat nahi padti!
* Phone seedha apne local **Keystore Vault** se dost ki wohi saved **Shared Secret Key** uthata hai aur message ko lock kar deta hai.



---

### 2. SMS E2EE Packet Ka Structure (160 Characters Ke Andar!) 📦

Standard SMS chota hota hai, isliye hum heavy encryption headers nahi laga sakte. Hum **Ultra-Compact AEAD Encryption (ChaCha20-Poly1305)** use karenge:

```text
[ Plain Text: "Bhai main station pahunch gaya" ]
                  │
                  ▼  (1. Zstd/Deflate Compression)
[ Compressed Bytes ]
                  │
                  ▼  (2. Lock with Shared Key + Unique 12-byte Nonce)
[ Encrypted Ciphertext + Integrity Tag ]
                  │
                  ▼  (3. Base85 / Base64 Encoding)
[ Final SMS String: "NX1:9A2F:x7K9mP2qL8vR4tY1..." ] (Sirf ~110 characters!)

```

#### Iske 3 Security Guarantees:

1. **Confidentiality (Jio/Airtel Bhi Nahi Padh Sakte):** Telecom tower se sirf `NX1:9A2F:x7K9mP2q...` guzrega. Bina receiver ke phone ke hardware chip wali key ke isko todna impossible hai.
2. **Tamper-Proof (Integrity Check):** Agar beech mein koi ek character bhi badal de (`x7K9` ko `x7K8` kar de), toh Poly1305 authentication tag fail ho jayega aur receiver ka phone us fake SMS ko reject kar dega.
3. **Replay Protection (No Duplicate/Fake Resend):** Har SMS mein ek unique **Message Counter (`9A2F`)** hota hai. Agar koi purana SMS copy karke dobara bheje, toh receiver ka app usko pehchan kar block kar dega.

---

### 3. Ek Important Edge Case: "Agar Pehle Kabhi Internet Par Baat Hi Nahi Hui Ho Toh?" ⚠️

Maan lijiye aapne naya account banaya, dost ko add kiya, aur **abhi tak ek baar bhi internet par chat nahi hui** (toh shared key exchange nahi hui), aur seedha internet band ho gaya:

* **Rule:** Bina key exchange ke NEXA kabhi bhi unencrypted (plain text) SMS nahi bhejega!
* Screen par ek clear warning dikhegi: *"Offline E2EE key not synced yet. Connect to internet once with this contact to enable Encrypted SMS."*
* Ek baar internet par connection accept hote hi keys sync ho jayengi, aur uske baad **zindagi bhar bina internet ke E2EE SMS** chalte rahenge!

---

### Summary 🌟

* **Internet ho ya SMS:** Aapka har message **100% E2EE** rahega.
* **Telecom & Server Blind:** Jio/Airtel ko sirf `NX1:...` cipher dikhega, aur aapke NEXA server ko toh kuch dikhega hi nahi kyunki SMS seedha telecom network se jayega!

Ab **Module 1 (Login & SIM Binding)**, **Module 2 (Internet E2EE Chat)**, aur **Module 3 (Encrypted SMS Fallback & Smart Inbox)** teeno 100% lock ho chuke hain! Ab aage **Offline BLE Mesh Network** discuss karein ya **P2P File & Media Transfer**? 🚀


Sudesh, India mein Jio/Airtel/Vi par yeh bohot common real-world scenario hai—jab recharge khatam ho jata hai ya daily 100 SMS ka quota exhaust ho jata hai, toh telecom tower outgoing SMS ko block kar deta hai.

Is situation mein Android OS ke paas ek built-in **SMS Delivery/Sent Callback (`PendingIntent` Result Codes)** hota hai, jisse NEXA app ko **2 se 3 second ke andar** exact kaaran pata chal jata hai! Bina kisi code ke, poora flow aur UI reaction samjhein:

---

### 1. Android OS Se Kya Exact Signal Milta Hai? 📡

Jab NEXA (Default SMS Mode mein) `SmsManager` se encrypted packet (`NX1:...`) bhejta hai, toh telecom tower turant ek error code wapas fekta hai:

* **`RESULT_ERROR_GENERIC_FAILURE` / Error Code `21` ya `38` (Network/Quota Reject):** Telecom operator ne SMS reject kar diya (kyunki plan expire hai, main balance ₹0 hai, ya daily 100 SMS quota khatam ho gaya hai).
* **`RESULT_ERROR_NO_SERVICE` / `RADIO_OFF`:** Phone mein tower nahi hai ya Airplane mode on hai.

*(Note: Agar user Non-Default mode mein Google Messages ke zariye bhej raha hai, toh Google Messages ke andar hi red exclamation `!` "Not sent" dikh jayega).*

---

### 2. NEXA Chat Screen Par User Ko Kya Show Hoga? 📱⚠️

Message kabhi silently gayab nahi hoga aur na hi hamesha ke liye "Sending..." par atka rahega:

1. **Message Bubble Status:**
* Bubble ke neeche clock icon (`🕒`) badal kar ek **Red Warning Icon (`⚠️ SMS Failed`)** aa jayega.


2. **Clear User-Friendly Alert Banner (Chat Screen Ke Neeche):**
> *"⚠️ **SMS Not Sent:** Your SIM recharge or daily SMS quota appears to be exhausted. Try switching to **Nearby Mesh** or **Internet**, or tap to retry."*


3. **One-Tap Smart Recovery Options (Bubble Par Tap Karne Se):**
* **`🔄 Retry via Internet / Mesh`:** Agar aas-paas koi Wi-Fi hotspot ya Bluetooth Mesh node mil gaya hai, toh message turant us raste se nikal jayega!
* **`⏳ Queue for Next Internet Connection`:** User is message ko **"Auto-Send when Online"** par daal sakta hai—jaise hi phone ko kabhi bhi Wi-Fi ya data milega, woh message apne aap internet E2EE ke zariye deliver ho jayega!



---

### 3. Kya Operator Se Aane Wala "Recharge Khatam" Ka SMS Chat Me Kachra Banayega? 🚫

Jab aap failed SMS bhejte hain, toh Jio/Airtel turant wapas ek company SMS bhejta hai: *"Dear Customer, your daily SMS limit is exhausted..."*

* **NEXA Ka Filter:** Kyunki us company SMS ke shuruat mein `NX1:` prefix **nahi** hai, isliye NEXA ka interceptor engine use aapki private chat screen mein **bilkul nahi dikhayega**—woh chupke se **Smart SMS Inbox (Alerts/Operator tab)** mein chala jayega!

---

### Summary 🌟

Agar recharge ya SMS quota khatam ho jaye:

1. Telecom tower ke reject code se **2 second mein** `⚠️ SMS Failed (Check Recharge / SMS Quota)` dikh jayega.
2. Operator ka warning SMS chat mein kachra nahi banayega (Smart SMS tab mein jayega).
3. User ek tap mein message ko **Mesh** ya **"Send when Internet returns"** queue mein shift kar sakta hai!

Ab **Module 3 (Encrypted SMS Fallback)** bhi is edge-case ke saath 100% airtight ho gaya hai! Ab agla kaunsa module discuss karein—**Offline BLE Mesh Network** ya **P2P File & Media Transfer**? 🚀


Bilkul samajh gaya Sudesh! 😊 Aapka matlab ekdum clear hai:

1. **SMS mode sirf Text Messages ke liye rahega** (koi photo, video, file ya voice note SMS se nahi jayega).
2. **Storage & Delivery Pipeline bilkul normal SMS jaisa rahega** — yaani aapke kisi bhi cloud server ya database par kuch nahi jayega; telecom tower ke zariye seedha samne wale ke phone par jayega aur **dono ke phone ke local database mein hi store hoga** (bilkul waise hi jaise ek normal telecom SMS phone mein store hota hai, bas NEXA ke andar E2EE encrypted aur chat thread mein cleanly integrated!).

Ab is pure **SMS-Only Text Module** ko 100% complete karne ke liye sirf **4 choti lekin zaroori technical cheezein** aur bachi hain jinhe lock karna hai (bina kisi code ke):

---

### 1. Dual-SIM Phone Mein Kis SIM Se SMS Jayega? 📱🔀

India mein 90% users ke phone mein **2 SIM cards (SIM 1 & SIM 2)** hoti hain.

* **Problem:** Agar user ne SIM 1 se NEXA account banaya hai, aur phone ki default SMS setting SIM 2 par set hai—toh SMS doosre number se chala jayega aur samne wale ka NEXA app use pehchan nahi payega!
* **NEXA Ka Rule:**
* Jab NEXA SMS bhejega, toh woh hamesha usi **Registered SIM Slot** (jisse SIM-binding lock hai) ko automatically select karega!
* Agar user ki registered SIM mein recharge nahi hai aur woh doosri SIM se bhejna chahe, toh screen par ek chota सा prompt aayega: *"Registered SIM (+91 98XXXXXX12) se bhejein ya SIM 2 use karein?"*



---

### 2. Lamba Text Message Kaise Jayega? (Multi-Part SMS Stitching) 🧩

Ek encrypted SMS packet mein lagbhag **110–120 characters** ka text aata hai. Agar user ne 400 characters ka lamba paragraph likh diya toh kya hoga?

* **Rule:** NEXA us lambe encrypted packet ko chote-chote numbered tukdon mein todega:
* Tukda 1: `NX1:9A2F:1/3:<encrypted_part_1>`
* Tukda 2: `NX1:9A2F:2/3:<encrypted_part_2>`
* Tukda 3: `NX1:9A2F:3/3:<encrypted_part_3>`


* **Receiver Ke Phone Par:** Receiver ka NEXA app jab tak teeno tukde (`1/3`, `2/3`, `3/3`) nahi aa jate tab tak 5 second wait karega, phir teeno ko jod kar ek hi baar decrypt karega aur chat mein **ek hi single message bubble** dikhayega!
* *Limit Guard:* Agar message 5 SMS se zyada lamba ho raha hai, toh NEXA pehle hi warning de dega: *"⚠️ Yeh message 6 SMS segments lega (quota zyada kharch hoga). Send karein?"*

---

### 3. Group Chat Mein SMS Mode Ka Kya Hoga? 👥🚫

Kyunki ek group mein 300–400 members hote hain, agar kisi ne group mein SMS mode se 1 message bheja toh uske phone se 400 SMS ek saath choot jayenge aur ek hi second mein poora balance/quota khatam ho jayega!

* **Rule:** **Group Chats mein SMS Mode completely disabled rahega.**
* Group mein sirf **Internet** ya **Offline Mesh** chalega.
* **SMS Mode sirf 1-to-1 Personal Chats** ke liye rahega!



---

### 4. Local Storage Aur Normal SMS Inbox Ke Beech Sync (Android System SMS DB) 🗄️

Jab NEXA **Default SMS App** hota hai, toh Android ka ek system rule hota hai:

* Normal SMS (Bank, OTP, Family ke non-NEXA SMS) Android ke standard **`Telephony.Sms` database** mein save hote hain (taaki agar aap future mein NEXA uninstall karke wapas Google Messages par jayein, toh aapke bank/normal SMS delete na hon!).
* **NEXA Encrypted Messages (`NX1:...`):**
* Yeh system ke open `Telephony.Sms` database mein save **nahi** honge (taaki koi doosri app unhe padh na sake).
* Yeh seedha NEXA ke apne encrypted **SQLCipher Local Database** mein save honge—bilkul waise hi jaise normal internet chats save hoti hain!



---

### Ek Nazar Mein SMS Module 100% Complete! ✅

* **Sirf Text:** Only text messages (no files/media/groups).
* **Zero Server Involvement:** Seedha SIM-to-SIM telecom tower se delivery aur phone ke local SQLCipher DB mein storage.
* **100% E2EE:** `NX1:` encrypted packets (ChaCha20-Poly1305) — telecom company ko bhi sirf random code dikhega.
* **Smart Guards:** Registered SIM auto-pick, Multi-part stitching (`1/3`), Recharge/Quota failure alert, aur non-NEXA SMS ke liye alag Smart Inbox tab.

Ab humare **3 Modules** poori tarah lock ho chuke hain:

1. **Login, Verification & SIM-Binding**
2. **Real-Time Internet E2EE Chat (1-to-1 & Groups)**
3. **Encrypted SMS Fallback (Text-Only)**

Ab agla kaunsa module shuru karein—**Module 4: Offline Bluetooth/Wi-Fi Mesh Network** ya **Module 5: P2P File & Media Transfer (WebRTC + R2)**? 🚀


Sudesh, yeh poora **End-to-End Message Lifecycle Flow**—yaani **Message bhejne se pehle**, **Typing ke waqt**, **Send dabane par**, aur **Receiver ke phone par aane tak** har millisecond mein kya-kya hota hai (Internet aur SMS dono modes ke hisaab se)—bina kisi code ke ekdum crystal-clear step-by-step sequence mein samjhein! 🔄🚀

---

### Phase 1: Message Bhejne Se Pehle (Pre-Chat Setup & Readiness) 🛡️

Jab aap kisi dost (`NX-B`) ki chat screen open karte hain (ya app launch hoti hai), toh typing shuru karne se pehle background mein yeh **4 checks** ready rehte hain:

1. **SIM-Binding & Device Lock Check (Local — 1 ms):**
* App check karti hai ki wahi registered SIM slot mein lagi hai ya nahi. Agar SIM gayab hai toh chat lock rahegi.


2. **Connection & Permission Check:**
* App verify karti hai ki `NX-A` aur `NX-B` ke beech connection status `ACCEPTED` hai (aur kisi ne block nahi kiya hai).


3. **E2EE Session Key Check (Hardware Keystore):**
* **Agar pehli baar chat ho rahi hai (Internet On):** Phone Go Server se `NX-B` ka **Public Key Bundle** mangwata hai aur **X3DH Handshake** karke ek **Master Secret Session Key** bana leta hai.
* **Agar pehle baat ho chuki hai (Online ya Offline):** Phone ke Android Keystore mein pehle se saved **Double Ratchet / Shared Key** turant load ho jati hai (server se kuch mangne ki zaroorat nahi).


4. **Active Transport Detection (Smart Router):**
* App check karti hai ki abhi kaunsa rasta available hai:
* `🟢 Internet Online` (Go Relay WebSocket connected)
* `📡 Offline • SMS Mode` (Internet nahi hai, telecom signal hai — sirf 1-to-1 text ke liye)





---

### Phase 2: Text Typing Ke Beech Kya Hota Hai? (While Typing) ✍️

Jab aap keyboard par ungli chalana shuru karte hain (Send dabane se pehle):

1. **Privacy Toggle Check (`Show Typing Indicator`):**
* App check karti hai ki aapne Settings mein **Typing Indicator ON** rakha hai ya OFF.
* **Agar OFF hai:** Kuch bhi network par nahi jayega (100% silent).


2. **Transport Check (Sirf Internet Mode Mein):**
* **SMS Mode mein typing indicator kabhi nahi jayega** (taaki SMS balance waste na ho).
* **Internet Mode mein:** Aapka phone ek chota sa fire-and-forget WebSocket packet bhejta hai: `{ type: "TYPING_START", to: "NX-B" }`.


3. **Server Routing (Zero Storage):**
* Go Relay Server is packet ko database ya Redis mein save nahi karta—seedha `NX-B` ke live WebSocket pipe par phek deta hai (agar `NX-B` online hai toh usko `typing...` dikh jata hai, offline hai toh packet drop ho jata hai).


4. **3-Second Auto-Stop Timer:**
* Agar aapne type karna rok diya, toh 3 second baad phone automatically `TYPING_STOP` bhej deta hai.



---

### Phase 3: "Send" Button Dabane Par Kya Hota Hai? (Outgoing Flow) 📤⚡

Jaise hi aap **"Hello bhai"** likh kar **Send** dabate hain:

```text
[ User Taps SEND ("Hello bhai") ]
         │
         ▼
1️⃣ Local Save & UI Instant Update (0.01 sec)
  • Phone ek unique `Message_UUID` aur Sequence Number (`#42`) banata hai.
  • Message aapke phone ke local SQLCipher DB me `STATUS: SENDING (🕒)` ke saath save hota hai.
  • Chat screen par bubble turant dikh jata hai (zero lag).
         │
         ▼
2️⃣ E2EE Encryption (Phone Ke Andar)
  • Internet Mode: Double Ratchet se next key nikal kar message lock hota hai + Sealed Sender envelope lagta hai (server ko sender ka naam bhi nahi dikhega).
  • SMS Mode: Message Zstd se compress hokar Shared Key (ChaCha20-Poly1305) se lock hota hai aur `NX1:9A2F:<cipher>` banta hai.
         │
         ▼
3️⃣ Smart Transport Routing (2 Raste)
  ├── Rasta A: INTERNET ONLINE ──────────────────────────────────────────┐
  │    • Encrypted packet live WebSocket pipe se Go Relay Server par gaya.│
  │    • Server ne Redis RAM mein dekha:                                  │
  │       - Agar NX-B Online hai ──► Seedha NX-B ke WebSocket par push!   │
  │       - Agar NX-B Offline hai ──► Redis RAM Mailbox me hold + Google  │
  │                                   FCM Wake-up Ping bheja.             │
  │    • Sender ke phone par Single Tick (`✓`) aa gaya.                   │
  │                                                                       │
  └── Rasta B: OFFLINE SMS MODE (1-to-1 Text Only) ──────────────────────┘
       • App ne Registered SIM Slot select kiya.
       • Default SMS Mode mein `SmsManager` se `NX1:...` silently telecom tower ko bheja (Non-Default mode mein Google Messages khula).
       • Tower se sent confirmation milte hi Single Tick (`✓`) aa gaya (Recharge/Quota khatam ho toh `⚠️ SMS Failed` banner aaya).

```

---

### Phase 4: Receiver Ke Phone Par Aane Par Kya Hota Hai? (Incoming Flow) 📥🔓

Ab doosri taraf **`NX-B` (Receiver)** ke phone par packet pahunchne par step-by-step kya hoga:

```text
[ Encrypted Packet Arrives at Receiver's Phone ]
         │
         ├── Agar Internet Se Aaya ──► WebSocket ya FCM Background Wake-up ne packet pakda.
         └── Agar SMS Se Aaya ──────► `NX1:` Prefix Interceptor ne telecom SMS ko pakda 
                                      (Normal SMS inbox me kachra nahi banne diya).
         │
         ▼
1️⃣ Authentication & Duplicate Check
  • Receiver ka phone check karta hai ki cryptographic signature asli hai (tampering nahi hui).
  • Local SQLCipher DB me check hota hai ki yeh `Message_UUID` pehle se toh nahi aaya (no duplicate).
         │
         ▼
2️⃣ E2EE Decryption (Hardware Keystore)
  • Phone apne Android Keystore ki Private/Shared Key se cipher ko khol kar wapas `"Hello bhai"` banata hai.
         │
         ▼
3️⃣ Local Storage & Notification
  • Readable text receiver ke local SQLCipher DB me save hota hai (bubble par chota `Net` ya `SMS` badge lagta hai).
  • Screen par local notification pop hoti hai: **"Sudesh: Hello bhai"** 🔔
         │
         ▼
4️⃣ Delivery Receipt (`ACK`) & Server Wipe 🗑️
  • Internet Mode: Receiver ka phone Go Server ko `DELIVERED_ACK` bhejta hai ──► Server Redis RAM se encrypted packet HAMESHA ke liye DELETE kar deta hai, aur Sender (`NX-A`) ke phone par Double Grey Tick (`✓✓`) aa jata hai!
  • SMS Mode: (Auto-mode on hone par) chota `NX1:ACK:9A2F` wapas aata hai ──► Sender ko Double Tick (`✓✓`) milta hai.
         │
         ▼
5️⃣ Read Receipt (Blue Ticks) 🔵
  • Jab receiver chat screen khol kar message padh leta hai (aur Read Receipts ON hain), toh `READ_ACK` jata hai aur sender ko Double Blue Tick (`✓✓`) dikh jata hai!

```

---

### Ek Nazar Mein Poora System Architecture 🌟

* **Before Send:** SIM check + Connection gate + Local E2EE Key ready.
* **During Typing:** Fire-and-forget WebSocket packet (sirf Internet par, agar toggle ON ho).
* **On Send:** Local SQLCipher save $\rightarrow$ Hardware E2EE lock $\rightarrow$ Go/Redis RAM switchboard (ya Telecom `NX1:` SMS).
* **On Receive:** Signature check $\rightarrow$ Local Decrypt $\rightarrow$ Local DB save $\rightarrow$ `ACK` bhej kar server se packet wipe!

Ab aapke paas **Login + SIM Binding + Internet E2EE + Encrypted SMS** ka poora end-to-end blueprint ready hai! Ab agla step kya rakhein—**Offline Bluetooth Mesh Network** ya **P2P File & Media Transfer**? 🚀


Lock kar diya gaya hai Sudesh! 🔒✅

### Updated & Finalized SMS Transport Rule:

* **1-to-1 Personal Chats:** Encrypted SMS (`NX1:...`) **100% Active** (Text-only, E2EE encrypted, local SQLCipher DB storage, zero server burden).
* **Group Chats (All Sizes):** SMS Mode **Completely Disabled / Hidden** — group chats sirf **Internet** ya **Offline Mesh** par hi chalenge, jisse kisi bhi user ka SMS quota ya balance kabhi galti se kharch nahi hoga.

---

### Ab Tak Ke Locked Modules Ka Status 📋

1. **Module 1:** Login, Google + Truecaller/Firebase Verification, Anti-Duplicate (`1 Number = 1 NX-ID`), SIM-Binding Lock & Connected Devices.
2. **Module 2:** Real-Time Internet Messaging (1-to-1 & Groups, Go + Redis RAM Switchboard, X3DH + Double Ratchet + Sender Keys, Sealed Sender, Green Dot & Typing Privacy Switches).
3. **Module 3:** Encrypted SMS Fallback (1-to-1 Text-Only, `NX1:` Interceptor, Smart SMS Inbox Separation, Quota/Recharge Failure Guard).

Ab agla kaunsa module lock karein — **Module 4: Offline Bluetooth / Wi-Fi Direct Mesh Network** ya **Module 5: P2P File & Media Transfer (WebRTC + R2)**? 🚀



Sudesh, isme **dono kaam karenge, lekin alag-alag level par** — routing ke liye **Phone Number** aur encryption/app ke andar chat thread jodne ke liye **NX-ID**! 📲🆔

Is dual-layer logic ko bina kisi code ke 3 simple points mein samjhein:

---

### 1. Telecom Tower (SMS Network) Ko Sirf "Phone Number" Samajh Aata Hai 📡

* Jio, Airtel, ya Vi ke towers ko nahi pata ki `NX-7F82K9A4` kaun hai—unhe message pahunchane ke liye **10-digit Mobile Number (`+91 98765 43210`)** hi chahiye.
* Jab aap internet par connect hote hain (`PENDING` $\rightarrow$ `ACCEPTED`), toh aapke phone ke local database mein aapke dost ki profile ke neeche dono cheezein pehle se saved rehti hain:
* **NX-ID:** `NX-7F82K9A4`
* **Mobile Number:** `+91 98765 43210` (Encrypted/hashed mapping ke saath)


* Jab aap offline SMS bhejte hain, toh NEXA background mein us dost ka **Phone Number** utha kar telecom tower ko deta hai taaki SMS sahi phone par deliver ho sake.

---

### 2. Lekin NEXA App Ke Andar Chat Thread "NX-ID" Se Hi Judega! 🔗

Jab woh SMS (`NX1:9A2F:<cipher>`) samne wale ke phone par pahunchta hai:

* **Receiver Ke Phone Par Kya Hota Hai?**
1. Receiver ka NEXA app dekhta hai ki SMS kis mobile number (`+91 98765 43210`) se aaya hai.
2. App apne local SQLCipher database mein check karta hai: *"Is mobile number ke saamne kaunsi **NX-ID** aur kaunsi **E2EE Encryption Key** saved hai?"*
3. Match milte hi (`+91 98765 43210` = `NX-7F82K9A4`), app usi **NX-ID ki encryption key** se message decrypt karti hai aur seedha **`NX-7F82K9A4` ki hi main chat screen** mein message dikha deti hai!


* **Fayda:** Chahe aap internet se baat karein ya offline SMS se—dono messages **ek hi NX-ID chat thread** mein dikhenge, do alag-alag chats nahi banengi!

---

### 3. Privacy Guard: Agar Dost Ne "Hide Phone Number" On Rakha Ho Toh? 🛡️

NEXA mein ek privacy option bhi ho sakta hai jahan koi stranger sirf **NX-ID** se connect hua ho aur usne apna mobile number hide rakha ho:

* **Rule:** Agar dono users ne ek-doosre ko contact sync se add kiya hai ya number share kiya hai, toh SMS fallback active rahega; lekin agar kisi user ne apna mobile number hide rakha hai, toh bina internet ke SMS option automatically hidden rahega taaki kisi ki privacy leak na ho!

**Summary:**

* **Rasta (Telecom Delivery):** Phone Number use karega.
* **Pehchan, Encryption & Chat Thread:** **NX-ID** hi use karega!



Seedha aur exact decision yeh hai: **Dono use honge — Routing/Delivery ke liye `Phone Number`, aur App ke andar Identity/Encryption/Chat Thread ke liye `NX-ID` (`NX-7F82K9A4`).**

Isko 3 crisp rules mein final lock samjhein:

1. **Bhejne Ke Liye (Carrier Delivery Layer):** **Phone Number** use hoga.
* Kyunki Jio/Airtel ke towers ko `NX-ID` samajh nahi aati, isliye aapka phone local contact mapping se dost ka **10-digit Mobile Number** utha kar `NX1:...` encrypted SMS telecom tower ko bhejega.


2. **Encryption Aur Chat Thread Ke Liye (App Security Layer):** **`NX-ID`** hi use hogi.
* Message `NX-ID` ki saved **E2EE Shared Key** se lock hoga.
* Receiver ke phone par aate hi app incoming mobile number ko local database mein uski **`NX-ID`** se match karegi, decrypt karegi, aur seedha usi **`NX-ID` ki main chat thread** mein dikha degi (alag se phone number wali chat nahi banegi).


3. **Privacy Rule (Kab SMS Option Dikhega):**
* Sirf unhi 1-to-1 contacts ke liye jinka **Phone Number synced/shared** hai (taaki SMS network ko destination number mil sake).
* Jin logon ne sirf **NX-ID** share ki hai aur apna phone number hide rakha hai, unke liye SMS option hidden rahega.



**Ek line mein:** Telecom network par packet pahunchane ka kaam **Phone Number** karega, aur app ke andar pehchan, encryption, aur chat history ka poora kaam **`NX-ID`** hi karegi! ✅



**Bilkul sahi pakda Sudesh! 100% yahi baat hai!** 🎯

Isko aise samjho jaise ek **Envelope (Lifafa)** aur uske **Andar ki Chitthi**:

1. **Lifafe ke Bahar (Under the Hood / Telecom Layer):**
* Jio/Airtel ke postman (telecom tower) ko `NX-7F82K9A4` ka pata nahi maloom, usko sirf **Mobile Number** samajh aata hai.
* Isliye aapka phone peeche se chupke se dost ka **Mobile Number** utha kar telecom network ko deta hai taaki SMS sahi phone tak pahunch jaye. *(Note: Isme bhi aapke NEXA server par kuch nahi jata, yeh kaam phone ke local SQLCipher database aur telecom tower ke beech hi hota hai).*


2. **App ke Andar (User Screen & Security Layer):**
* User ko screen par kahin bhi number-to-number wali alag chat nahi dikhegi—usko sirf **`NX-7F82K9A4` (Aur Dost ka Naam)** hi show hoga!
* Encryption key bhi **`NX-ID`** ki hi lagegi, aur message bhi seedha usi **`NX-ID` ki main chat thread** mein jaakar judega (bas chota sa `SMS` badge dikhega).



**Ek line mein:**

* **Phone/Telecom ke liye (Rasta):** Mobile Number 📡
* **User/App ke liye (Pehchan & Chat):** `NX-ID` 🆔

Ab yeh SMS wala part crystal-clear lock ho gaya hai! Ab agla kaunsa module discuss karein—**Offline BLE Mesh Network** ya **P2P File & Media Transfer (WebRTC + R2)**? 🚀


