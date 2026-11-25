Aapke UI flow ke hisaab se (Splash $\rightarrow$ Google Login $\rightarrow$ Details Fill [Name, Phone, Gender, Age] $\rightarrow$ NX ID Allot $\rightarrow$ Final Profile Ready $\rightarrow$ Home Page), peeche **Android Client, Go Backend, Redis, aur Supabase (PostgreSQL)** ke beech kya-kya hoga, kaise save hoga aur millions of users ke liye cost aur security kaise lock hogi—iska exact architecture neeche diya gaya hai:

---

### 1. Step-by-Step Backend & DB Flow (Bina Code Ke) 🔄

#### Step 1: Google Sign-In & Hardware Key Generation (Login Button Tap)

* **UI Action:** User "Continue with Google" par click karta hai.
* **Android Background Action:**
1. Android Credential Manager Google se ek signed **ID Token (JWT)** laata hai.
2. Saath hi phone ke hardware security chip (**Android Keystore / StrongBox**) mein user ki apni ek **Ed25519 Private Key** aur **Public Key** generate hoti hai. (Private key kabhi phone se bahar nahi jayegi).


* **Backend Action (Supabase + Go Relay):**
1. App Google ID Token ko Supabase Auth ko bhejti hai. Supabase verify karke `auth.users` mein ek internal `user_uuid` banata hai aur session token (JWT) wapas deta hai.
2. Abhi user ka account **"Incomplete / Onboarding"** state mein hai (abhi NX ID allot nahi hui hai).



---

#### Step 2: Details Fill & Phone Verification (Next Button #1) 📱

* **UI Action:** User apna **Name, Phone Number, Gender, Age** bharta hai aur **Next** dabata hai.
* **Verification Kaise Hoga? (Zero-Cost vs OTP Reality):**
* **Age Gate:** Backend check karega ki Age $\ge 13$ (ya $18$) hai ya nahi. Poori Date of Birth save karne ke bajaye sirf `birth_year` ya `is_age_verified = true` save hoga.
* **Phone Number Verification (2 Best Approaches for Millions of Users):**
1. **Zero-Cost Reverse SMS Intent (Recommended for ₹0 Cost):** App `ACTION_SENDTO` ke through user ka default SMS app kholti hai jisme ek pre-filled verification code (`NEXA-VERIFY:<nonce>`) hota hai. User bas "Send" dabata hai. Server ke virtual number/webhook par SMS aate hi Redis mein nonce verify ho jata hai aur phone number lock ho jata hai. Isme Play Store ki `SEND_SMS` policy bhi break nahi hoti aur developer ka kharcha ₹0 rehta hai.


2. **Fallback / Paid OTP:** Agar kisi ke SIM mein SMS pack nahi hai, tabhi backup ke roop mein **2Factor.in** ka OTP use hoga.







---

#### Step 3: NX-ID Allotment & Cryptographic Binding (Next Button #1 Processing) 🆔

Jaise hi details aur phone verify hote hain, Go Backend **NX-ID** generate karta hai:

* **NX-ID Kaise Banegi? (Collision-Free & Unpredictable):**
* Sequential numbers (`NX-00001`, `NX-00002`) **kabhi nahi** rakhenge, warna koi bhi guess kar lega ki app par kitne users hain.
* Backend ek **8-character Cryptographic Base32/Alphanumeric ID** generate karega (jaise `NX-7F82K9A4`), jisme confusing characters (`0, O, 1, I`) hata diye jate hain taaki padhne mein aasan ho.
* Server PostgreSQL mein check karega ki yeh ID pehle se exist toh nahi karti (`UNIQUE` constraint), aur user ke `user_uuid` ke sath permanently lock kar dega.


* **UI Action:** Screen par user ko uski chamakti hui **NX-ID (`NX-7F82K9A4`)** dikhti hai, aur user **Next** dabata hai.

---

#### Step 4: Final Profile Ready & Local Vault Creation (Next Button #2 $\rightarrow$ Home) 🏠

* **UI Action:** User final profile preview dekhta hai (jahan default Alphabet Initial Avatar set hai) aur **Next** dabakar Home Page par jata hai.
* **Backend & Local DB Action:**
1. Server par profile ka status `ACTIVE` mark ho jata hai.
2. Phone ke andar **SQLCipher (Encrypted Room DB)** initialize hota hai, jisme user ki profile, keys, aur empty chat tables save hoti hain.
3. App Go Relay Server ke sath ek lightweight **WebSocket connection** establish karti hai aur Home Screen load ho jati hai.



---

### 2. Exactly Kya-Kya Save Hoga Aur Kahan? 🗄️

Millions of users ke data ko **2 parts** mein divide kiya gaya hai taaki server halka rahe aur privacy 100% bachi rahe:

#### A. Cloud Server Par Kya Save Hoga? (Supabase PostgreSQL)

Sirf **1 single table (`profiles`)** aur **1 device table (`user_devices`)** banegi:

| Field Name | Kya Save Hoga? | Kyun Aur Kaise? |
| --- | --- | --- |
| `id` (UUID) | Supabase Auth internal ID | Primary key (indexed) |
| `nx_id` | e.g., `NX-7F82K9A4` | Unique permanent address (indexed for fast search) |
| `google_email_hash` | SHA-256 Hash of Gmail | Duplicate accounts rokne ke liye (plain email sirf `auth.users` mein protected rahega) |
| `phone_hash` & `phone_enc` | SHA-256 Hash + AES Encrypted Phone | Hash se contact discovery hogi; encrypted phone tabhi khulega jab user kisi ko permission dega |
| `display_name` | User ka naam (e.g., "Sudesh") | Public profile name |
| `gender` | Optional (`M` / `F` / `O` / `NULL`) | User ki privacy setting ke hisaab se |
| `birth_year` | Sirf saal (e.g., `2002`) | Age compliance ke liye, exact birthday leak nahi hoga |
| `public_identity_key` | Ed25519 Public Key | Taaki doosre users isko encrypted messages bhej sakein |
| `fcm_token` | Firebase Push Token | Offline hone par phone ko wake-up ping bhejne ke liye |
| `account_status` | `ACTIVE` / `SUSPENDED` | Security control ke liye |

#### B. Sirf User Ke Phone Mein Kya Save Hoga? (Local SQLCipher DB)

* **Ed25519 Private Key:** Android Keystore chip ke andar (server par 0% access).
* **Exact Age / DOB & Preferences:** Local settings.
* **Contacts & Chats:** Saara personal data.

---

### 3. Millions of Users Ke Liye Negligible Cost & Security Kaise Hogi? 💰🛡️

1. **PostgreSQL Storage Cost (5 Million Users = Sirf ~1.5 GB Data):**
* Kyunki hum server par na toh messages save kar rahe hain aur na hi photos/videos, isliye 50 Lakh users ki `profiles` table ka total size sirf **1.5 GB se 2 GB** hoga! Supabase ke basic plan ($25/mo) mein hi 5 Million users aaram se aa jayenge.


2. **Redis RAM Cache (Zero DB Overload):**
* Jab millions of log ek sath login ya search karenge, toh har baar database par load nahi padega. `NX-ID` aur `Online Status` Redis ki RAM mein cache rahega, jisse response **1 millisecond** mein aayega.




3. **Database Security (Row-Level Security - RLS):**
* Supabase mein RLS policies lagi hongi: Koi bhi user kisi doosre user ka `phone_enc`, `email`, ya `fcm_token` query nahi kar sakta. Doosre logon ko sirf `nx_id`, `display_name`, aur `public_identity_key` hi dikhegi.


4. **Bot & Spam Prevention (Play Integrity):**
* Login ke waqt Google Play Integrity API verify karegi ki request kisi asli Android phone se aa rahi hai, na ki kisi hacker ke script se—jisse fake NX-IDs banne ka khatra khatam ho jayega.



Yeh login aur onboarding ka poora backend blueprint hai. Kya is flow mein koi step hai jisme aap kuch badalna ya add karna chahte hain? Agar sab clear hai, toh agle module par chalein! 🚀


**Device Verification** (Device Attestation & Cryptographic Binding) NEXA ke security model ka sabse important hissa hai, kyunki phone number aur Google login toh sirf user ki pehchan batate hain, lekin **Device Verification** yeh prove karta hai ki:

1. Request kisi **asli, uncompromised Android phone** se aa rahi hai (kisi hacker ke emulator, rooted phone, ya PC script se nahi).
2. Yeh account **isi specific physical phone** se locked hai, taaki koi doosra banda aapke account ko apne phone par clone na kar sake.

Bina code ke, poora **Device Verification Flow** 4 steps mein samjhein:

---

### 1. Hardware Key Generation (Phone Ke Chip Mein Taala-Chabi) 🔐

Jab user pehli baar app open karke login process shuru karta hai:

* App Android phone ke hardware security chip (**Android Keystore / StrongBox TEE**) ko bolti hai: *"Ek naya Cryptographic Key Pair (Ed25519) banao."*
* **Private Key (Asli Chabi):** Yeh phone ke hardware chip ke andar permanently lock ho jati hai. Is chabi ko na toh user dekh sakta hai, na koi app copy kar sakta hai, aur na hi yeh kabhi server par bheji jati hai.
* **Public Key (Public Taala):** Yeh key app apne Go Backend server ko bhejti hai taaki server ko pata rahe ki is user ka asli device kaunsa hai.

---

### 2. Google Play Integrity Check (Asli Phone Ya Fake Bot?) 🛡️

Millions of users hone par hackers automated scripts ya Android emulators (jaise BlueStacks) se fake accounts banane ki koshish karte hain. Isko rokne ke liye:

* Login ke waqt backend server phone ko ek random **Challenge Nonce** (ek temporary code) bhejta hai.
* Android app Google Play Services ki **Play Integrity API** ko call karti hai.
* Google ka server phone ke hardware, bootloader, aur Play Store installation ko check karke ek cryptographically signed verdict deta hai:
* `MEETS_DEVICE_INTEGRITY` (Asli certified Android phone hai)
* `APP_RECOGNIZED` (Asli NEXA app hai, koi modified/cracked APK nahi)


* Backend server Google ke is verdict ko verify karta hai. Agar phone rooted hai ya fake emulator hai, toh backend wahi login reject kar deta hai.

---

### 3. Proof of Possession (Server Challenge Handshake) 🤝

Sirf Public Key bhej dena kaafi nahi hota—phone ko yeh sabit karna padta hai ki uske paas sach mein uski Private Key maujood hai:

1. Go Backend ek random puzzle/nonce bhejta hai.
2. Phone ka hardware chip apni Private Key se us puzzle ko **Digitally Sign** karke wapas bhejta hai.
3. Server user ki Public Key se us signature ko verify karta hai.
4. Signature match hote hi server database (`user_devices` table) mein is phone ko **"Trusted Active Device"** mark kar deta hai aur ek unique `Device_ID` allot kar deta hai.

---

### 4. Jab User Naye Phone Mein Login Karega Toh Kya Hoga? (Single-Device Lock & Revocation) 📱🔄

Maan lijiye aapne apna purana phone badal kar naya phone liya aur usme Google se login kiya:

1. **Detection:** Backend server dekhega ki `NX-7F82K9A4` par pehle se `Device_1` active hai, aur ab `Device_2` (naya hardware key) login maang raha hai.
2. **Approval / Alert:**
* Naye phone ko verify karne ke liye user ko phone number/Google verification complete karna hoga (aur agar purana phone paas hai toh uspar notification approval jayega).


3. **Old Device Revocation (Purana Phone Cut-off):**
* Jaise hi `Device_2` verify hota hai, Go Backend purane `Device_1` ka session token aur WebSocket connection **turant kill (revoke)** kar deta hai.
* Purane phone par alert aa jata hai: *"Your account was logged in on a new device"* aur naya messages ab sirf naye phone par aayenge.



---

### Server Par `user_devices` Table Mein Kya Save Hoga? 🗄️

Sirf 5 choti fields (negligible storage):

* `device_id`: Unique device UUID
* `nx_id`: User ki NEXA ID
* `device_public_key`: Phone ke Keystore ki public key
* `device_model`: Sirf basic naam (e.g., *"Vivo Y28 5G"*) taaki user ko settings mein dikh sake ki kaunsa phone logged in hai
* `is_active`: `TRUE` / `FALSE`

---

### Ek Line Mein Summary 🌟

* **Google Login + Phone Number** = *User ki Identity* ("Yeh Sudesh hai").
* **Android Keystore + Play Integrity** = *Device ki Identity* ("Yeh Sudesh ka hi asli phone hai, koi hacker nahi").

Kya ab Login + Phone + Device Verification ka poora picture 100% clear ho gaya? Ab aap batayein agla kaunsa feature discuss karna hai! 🚀


Aapke dono sawal production launch aur Indian regulatory compliance ke hisaab se bohot critical hain. Bina code ke, dono ko detail mein samjhein:

---

### 1. Kya Google Play Protect / Play Store Isko Block Karega? 🛡️

**Nahi, bilkul block nahi karega** — balki yeh Google ka khud ka recommended security standard hai, bas aapko 2 rules follow karne honge:

1. **Google Play Integrity & Android Keystore (100% Safe):**
* `Android Keystore (StrongBox)` aur `Play Integrity API` Google ke official Android APIs hain. Play Protect inhe kabhi block nahi karta; ulta inki wajah se aapka app Play Store par zyada trusted mana jata hai.


2. **SMS & Phone Permissions Ka Rule (Jahan Play Protect Block Karta Hai):**
* Google Play Protect sirf tab block ya warning deta hai jab aap background mein chupke se `SEND_SMS` ya `READ_SMS` permissions use karein bina default SMS app bane.
* **NEXA Ka Safe Solution:**
* Verification ke liye hum **2Factor.in OTP** (bina `READ_SMS` permission ke, **SMS Retriever API** ke zariye) ya **Zero-Permission `ACTION_SENDTO` Intent** (jisme user khud default SMS app mein Send dabata hai) use karenge.
* Isse Play Store ko `SEND_SMS` ya `READ_SMS` ki ek bhi restricted permission nahi maangni padegi, aur Play Protect **0% block** karega!





---

### 2. Govt New Rule: "System Me Wahi SIM Card Lagi Hai Ya Nahi?" (SIM-Binding Check) 📱🇮🇳

India mein Telecom/Cybersecurity guidelines (TRAI / DoT / RBI ke SIM-binding norms) ke hisaab se yeh check karna zaroori hota hai ki jis phone mein app chal rahi hai, **registered SIM card physically usi phone ke slot mein maujood hai ya nahi** (taaki koi SIM nikal kar kisi doosre phone par Wi-Fi se app na chalata rahe).

Isko NEXA ke backend aur Android client par bina kisi heavy cost ke **3-Step SIM-Binding Engine** se ensure kiya jata hai:

#### Step 1: Login Ke Waqt SIM Slot Fingerprint Lock Hona (Subscription ID + Carrier ICCID Hash)

* Jab user pehli baar phone number verify karta hai:
* Android ka `SubscriptionManager` check karta hai ki phone mein kaunsi SIM active hai.
* Har physical SIM card ka ek unique hardware identifier hota hai (**Subscription ID / ICCID**).
* App us SIM ka ek cryptographic **One-Way Hash** banati hai (taaki original SIM serial number leak na ho) aur use **Android Keystore** + **Server (`user_devices` table)** ke sath bind kar deti hai.



#### Step 2: Har App Open / Background Sync Par Silent Hardware Check (Zero Cost)

* Jab bhi user NEXA app kholta hai ya background mein WebSocket reconnect hota hai:
* App Android OS se puchti hai: *"Abhi phone ke SIM slot mein kaun-kaun se SIM cards active hain?"*
* **Case A (SIM Wahi Hai):** Active SIM ka hash purane saved hash se match kar jata hai $\rightarrow$ App normal chalti rehti hai (User ko kuch pata bhi nahi chalta, 0 millisecond delay).
* **Case B (SIM Nikal Li Gayi Ya Badal Di Gayi):**
* Agar user ne SIM tray khol kar SIM nikal li (Airplane mode mein nahi, balki physical SIM absent hai) ya doosri SIM daal di:
* Hash mismatch hote hi NEXA app turant **Session Lock Screen** dikha degi:
> *"Registered SIM card not detected in this device. Please insert your registered SIM (+91 98XXXXXX12) to continue using NEXA."*


* Jab tak wahi SIM wapas slot mein nahi dalegi, chat lock rahegi!





#### Step 3: Periodic Re-Verification (Anti-SIM-Swap Protection)

* Agar koi hacker telecom store se duplicate SIM (SIM Swap) nikalwa le:
* Purane phone se network signals cut hote hi purana device detect karega ki SIM inactive/unregistered ho gayi hai aur local session lock kar dega.
* Naye phone par login karne ke liye dobara **2Factor OTP + Google Auth** chalega, aur purana phone server se revoke ho jayega.



---

### Ek Important Real-World Exception (User Experience Ke Liye) ⚠️

* **Roaming / No Network / Airplane Mode:** Agar user kisi basement ya flight mein hai jahan tower nahi aa raha, lekin physical SIM phone ke andar hi lagi hai—toh `SubscriptionManager` batata hai ki **SIM card slot mein present hai** (sirf signal nahi hai). Is case mein app **block nahi hogi** (offline mesh/chats kaam karte rahenge). App sirf tab block karegi jab **SIM card slot se bahar nikali gayi ho** ya **change ki gayi ho**.

---

### Summary: Kya Lock Hua? ✅

1. **Play Protect 100% Safe:** No restricted background SMS permissions (`READ_SMS`/`SEND_SMS` nahi lagenge).
2. **Govt SIM-Binding Rule:** Har app launch par local `SubscriptionManager` physical SIM ki presence aur hash verify karega—SIM nikalte hi app auto-lock ho jayegi.

Kya ab **Onboarding + Auth + Device & SIM Verification** ka poora module 100% clear hai? Ab agle module (**Connection Graph & Privacy Arrows**) par chalein? 🚀

Yeh bohot valid confusion hai Sudesh! Isse crystal clear samajhna zaroori hai kyunki Android OS ki ek technical limit hai.

Seedha jawab hai: **Haan, 2Factor.in (OTP) ki need hai** — sirf Android se SIM dekh kar kaam nahi chalega.

Iske peeche **Android Security ka ek simple sa technical kaaran** hai:

---

### 1. Android OS Humein SIM Ka Phone Number Nahi Batata! 🚫📱

Privacy rules ki wajah se modern Android (Android 10 se lekar 15/16 tak) kisi bhi app ko **SIM card ka asli mobile number read karne ki permission nahi deta**.

* Jab aap `SubscriptionManager` check karte hain, toh Android phone app ko sirf yeh batata hai:
* *"Slot 1 mein ek Jio ki SIM lagi hai (jiska internal slot ID `1` hai)."*
* Lekin Android yeh **nahi batata** ki us Jio SIM ka 10-digit mobile number `+91 98765 43210` hai ya `+91 99999 00000`! (90% Indian SIM cards mein carrier number field blank hota hai).



**Agar aap OTP (2Factor) nahi lagayenge toh kya gadbad hogi?**
Maan lijiye aapke phone mein aapki apni Jio SIM lagi hai, lekin login screen par aapne **apne dost ya kisi celebrity ka mobile number** type kar diya!

* Bina OTP ke server ko kabhi pata hi nahi chalega ki jo number aapne type kiya hai, wo sach mein usi Jio SIM ka hai ya kisi aur ka! Aap kisi ke bhi number ka account bana lenge.

---

### 2. Dono Ka Kaam Bilkul Alag Hai (Taala vs Chabi) 🔐

Is system ko 2 alag-alag kaamon mein baant kar samjhein:

| Step | Kaunsa System Kaam Karega? | Asal Kaam Kya Hai? (1 Line Mein) | Kab Chalega? |
| --- | --- | --- | --- |
| **1. Ownership Proof (Malikana Haq)** | **2Factor.in (OTP)** | Yeh sabit karta hai ki **"Jo number aapne type kiya hai, uska SIM sach mein aapke paas hai."** | Sirf **1 baar** (Account banate waqt ya naya phone lene par) |
| **2. Presence Lock (SIM-Binding)** | **Android `SubscriptionManager**` | Yeh sabit karta hai ki **"Jis SIM par OTP aaya tha, wo SIM abhi bhi phone ke andar hi lagi hai (nikali nahi gayi)."** | **Har baar** jab aap app open karenge (Cost = ₹0) |

---

### 3. Exact System Kaise Banega? (Step-by-Step Flow) 🔄

Isko bilkul simple aur cost-efficient rakhne ke liye aapka final system aisa dikhega:

1. **Step 1 (Account Creation — 1 Time Cost):**
* User apna mobile number type karta hai (`+91 98765 43210`).
* **2Factor.in** us number par ek 6-digit OTP bhejta hai.
* User OTP dalta hai $\rightarrow$ **Ab 100% prove ho gaya ki yeh number isi user ka hai.**


2. **Step 2 (SIM Slot Lock — Free):**
* Jaise hi OTP verify hota hai, aapka NEXA app Android ke `SubscriptionManager` se poochta hai: *"Abhi kis SIM slot par network aaya hai?"*
* App us specific SIM card ka hardware fingerprint (Subscription ID hash) phone ke **Android Keystore** mein lock kar leti hai.


3. **Step 3 (Rozana App Use — ₹0 Cost):**
* Ab user agle 2 saal tak roz app kholega, toh **2Factor OTP dubara nahi bheja jayega** (₹0 kharcha).
* App bas 1 millisecond mein check karegi: *"Kya wahi locked SIM card abhi bhi phone ke slot mein hai?"*
* Agar SIM wahi hai $\rightarrow$ App khul jayegi.
* Agar SIM nikal li ya badal di $\rightarrow$ App lock ho jayegi aur dubara **2Factor OTP** maangegi!



---

### Ek Line Mein Final Decision 🎯

* **2Factor.in (OTP):** Sirf **1 baar** number verify karne ke liye (taaki koi doosre ka number na chura le).
* **Android SIM Check:** Roz **free** mein check karne ke liye (taaki SIM nikalne par app band ho jaye).

Dono milkar **Govt SIM-Binding Rule** aur **100% Security** dono ko bilkul kam kharche mein poora karte hain! Kya ab yeh dono ka role clear ho gaya? 🚀


Bina SMS OTP bheje, aur **₹0 expenditure** ke saath phone number verify karne ke liye industry mein **3 practical aur Play Store-safe tarike** hain.

NEXA ke architecture ke hisaab se in teeno ka exact breakdown samjhein:

---

### 1. Google Phone Number Hint API (Zero-Cost & Zero-OTP — Best for Android) ⭐

Google Play Services ka ek official, 100% free API hai jise **Phone Number Hint API** kehte hain.

* **Yeh Kaise Kaam Karta Hai?**
1. Jab user onboarding screen par aata hai, toh app user ko phone number type karne ka option dene ke bajaye Google ka ek system popup dikhati hai: *"Continue with +91 98765 43210"*.
2. Yeh list seedha **Google Account + Device SIM Carrier verification** se aati hai (jo Google ne pehle hi user ke phone par verify kar rakhi hoti hai).
3. User bas ek tap karke apna number select karta hai.


* **Fayda:**
* **Cost:** ₹0 (100% Free, Google Play Services ka built-in feature hai).
* **Play Store Safe:** Koi restricted SMS permission nahi lagti.
* **Speed:** 1 second mein bina typing aur bina OTP ke number mil jata hai.


* **Limitation:** Agar user ke Google account mein uska SIM number link nahi hai, toh popup mein number nahi dikhega (un 10-15% logon ke liye aapko fallback rakhna padega).

---

### 2. Firebase Phone Auth — Free Tier (10,000 Verifications / Month Free) 🔥

Agar aap chahte hain ki OTP bhi jaye lekin shuruat mein aapki jeb se ₹0 kharch ho:

* Google **Firebase Authentication** har mahine **10,000 SMS verifications bilkul free** deta hai (Spark/Blaze free quota).
* **Fayda:**
* Aapko TRAI DLT registration ya 2Factor.in par advance paise dalne ki zaroorat nahi padti.
* Jab aapke app par 10,000+ monthly naye users aane lagenge (jab app grow ho jayega), tab aap 2Factor.in par switch kar sakte hain.
* Google ke **SMS Retriever API** ke saath OTP automatically read ho jata hai bina `READ_SMS` permission ke.



---

### 3. WhatsApp / Telegram Bot Verification (Zero-Cost Alternative) 💬

Aajkal kai Indian startups OTP ka kharcha bachane ke liye **User-Initiated Verification** use karte hain:

* **Flow:**
1. NEXA app mein ek button hota hai: *"Verify via WhatsApp / Telegram"*.
2. Tap karte hi user ke phone mein WhatsApp khulta hai aur aapke official NEXA Bot/Number par ek pre-filled message ready hota hai: `Verify my NEXA ID: NX-7F82K9A4`.
3. User bas **Send** dabata hai.
4. Aapke server ke webhook par WhatsApp/Telegram se us user ka **verified phone number** aur code aa jata hai, aur NEXA app mein account turant verify ho jata hai!


* **Fayda:**
* **Cost:** ₹0 (User-initiated message receive karne ka koi charge nahi lagta).
* **Security:** WhatsApp/Telegram par wahi number chalta hai jo pehle se telecom-verified hai, isliye 100% asli number hi aayega.



---

### NEXA Ke Liye Best Zero-Cost Combination 🎯

Aapko ek rupaya kharch kiye bina 100% coverage ke liye yeh **2-Step Waterfall** rakhna chahiye:

1. **Primary (90% Users ke liye — ₹0 Cost):** **Google Phone Number Hint API** lagayein. User 1 tap mein apna Google-verified SIM number select karega $\rightarrow$ Instant verify!
2. **Backup (Bache hue 10% Users ke liye — ₹0 Cost):** Agar kisi ka number Google Hint mein nahi dikhta, toh **Firebase Auth (10k Free/month)** se OTP bhej dein.

Isse aapka initial expenditure **₹0** rahega, Play Store policy 100% safe rahegi, aur har user ka phone number securely verify ho jayega! 🚀

Haan Sudesh! **Truecaller SDK (1-Tap Verification)** abhi bhi startups aur indie developers ke liye **100% Free** hai, aur Indian users ke liye yeh OTP ka sabse fast aur zero-cost replacement hai!

Bina code ke, iska current system, limits aur NEXA ke architecture mein yeh kaise fit hoga, usko simple points mein samjhein:

---

### 1. Truecaller SDK Free Plan Ki Reality (Current Status) 💰

Truecaller ne developers ke liye **Truecaller SDK (OAuth / 1-Tap Login)** free rakha hai:

* **Cost:** ₹0 (Unlimited 1-tap verifications ke liye koi per-SMS charge nahi lagta).
* **Kya Milta Hai:** User ka **Verified Mobile Number** + **First/Last Name** (jo Truecaller par verified hai).
* **Requirement:**
1. Truecaller Developer Portal par account banana hota hai.
2. App ka **Package Name** aur **SHA-1 Fingerprint** register karke ek **App Key (Client ID)** generate karni hoti hai.
3. **Condition:** Yeh tabhi kaam karta hai jab user ke phone mein **Truecaller app installed aur logged-in** ho (India mein lagbhag 70–80% Android users ke phone mein Truecaller pehle se hota hai).



---

### 2. NEXA Ke Login Flow Mein Truecaller Kaise Kaam Karega? 🔄

Aapke onboarding flow (**Google Login $\rightarrow$ Details Fill $\rightarrow$ NX ID**) mein Truecaller kaise seamlessly kaam karega:

1. **Step 1 (Google Sign-In):** User pehle Google se login karega (taaki Google account aur Drive backup bind ho jaye).
2. **Step 2 (Phone Verification Screen):**
* Phone number manually type karne ke bajaye user ko ek button dikhega: **"Verify Instantly with Truecaller"** (ya automatic bottom-sheet popup khulega).


3. **Step 3 (1-Tap Verification & Backend Check):**
* User bas **"Continue"** par tap karta hai.
* Truecaller SDK aapke NEXA app ko ek cryptographically signed **Authorization Code / Profile Token** deta hai.
* Aapka **Go Backend** Truecaller ke server se us token ko verify karta hai $\rightarrow$ Truecaller ka server confirm karta hai ki *"Haan, yeh `+91 98765 43210` isi user ka verified number hai"*.


4. **Step 4 (SIM Slot Lock):**
* Jaise hi Truecaller se number verify hota hai, NEXA app turant phone ke `SubscriptionManager` se us SIM slot ka hardware hash lock kar leti hai (Govt SIM-binding rule ke liye).



---

### 3. Lekin Ek Catch Hai: Jin 20-30% Logon Ke Phone Mein Truecaller Nahi Hai, Unka Kya? 🤷‍♂️

Har user ke phone mein Truecaller installed nahi hota (kuch privacy-conscious log Truecaller use nahi karte). Isliye sirf Truecaller par 100% depend nahi kar sakte.

Iske liye aapka **Zero-Cost 3-Tier Waterfall Model** sabse best aur fail-proof rahega:

```text
[ User reaches Phone Verification Step ]
                  │
                  ▼
1️⃣ Check: Kya phone mein Truecaller installed hai?
   ├── YES ──► Truecaller 1-Tap SDK (₹0 Cost, 1 Second) ✅
   │
   └── NO  ──► 2️⃣ Trigger: Google Phone Number Hint API (₹0 Cost) ✅
                  │
                  └── Agar wahan bhi number na mile (rare 5-10% cases)
                         │
                         ▼
                  3️⃣ Fallback: Firebase Phone Auth (10,000 Free OTP/mo)[cite: 1] ✅

```

---

### Summary Verdict 🌟

* **Truecaller SDK:** India ke liye **₹0 cost** aur sabse fast 1-tap verification hai—ise primary option zaroor rakhein.
* **Google Phone Hint + Firebase Free Tier:** Bache hue non-Truecaller users ke liye backup rakhein.


* **Result:** Aapko shuruat ke lakhon users tak **ek bhi paisa kharch kiye bina** 100% verified phone numbers mil jayenge, aur Play Store policy bhi 100% safe rahegi!

Kya ab aapka **Login + Phone + Device + SIM Verification** ka poora module lock samjhein? Ab agle module (**Connection Graph & Privacy Arrows**) par chalein? 🚀


Bilkul nahi Sudesh, Google aur Truecaller dono ko saath rakhne se **koi clash nahi hoga**—balki yeh ek-doosre ko complement karenge! 😊

Donon ka kaam bilkul alag hai:

* **Google:** Aapke account ki **Master Identity** (Login & Encrypted Drive Backup) banata hai.
* **Truecaller / Phone Hint:** Aapka **Mobile Number** verify karta hai (taaki contacts discovery aur SIM-binding ho sake).

Aapke exact UI flow ke hisaab se **Name, Age, Gender** kahan fill honge aur dono ko bina clash ke kaise jodenge, uska step-by-step flow dekhein:

---

### 1. Exact Step-by-Step Onboarding Flow (No Clash) 🔄

```text
[ 1. Splash Screen ]
         │
         ▼
[ 2. Google Sign-In Screen ]
  • User taps "Continue with Google"
  • Google se email & base account verify hota hai.
         │
         ▼
[ 3. Phone Verification Screen (Zero-Cost Waterfall) ]
  • Phone mein Truecaller hai? ──► Truecaller 1-Tap Popup (1 second mein number verified) ✅
  • Truecaller nahi hai?      ──► Google Phone Hint / Firebase OTP se number verified ✅
  • Saath hi SIM Slot lock ho jata hai (Govt SIM-Binding).
         │
         ▼
[ 4. Profile Details Screen (Yahan Fill Hoga!) ] 📝
  • Display Name (Pre-filled from Google/Truecaller, user edit kar sakta hai)
  • Age (User enter karega — e.g., 22)
  • Gender (Male / Female / Prefer not to say)
  • User taps [ Next ]
         │
         ▼
[ 5. NX-ID Allotment Screen ] 🆔
  • Backend saara data verify karke unique ID banata hai: `NX-7F82K9A4`
  • Screen par NX-ID dikhti hai ──► User taps [ Next ]
         │
         ▼
[ 6. Final Profile Ready ──► Home Screen ] 🏠

```

---

### 2. Google Aur Truecaller Clash Kyun Nahi Karenge? 🛡️

1. **Alag-Alag Steps Par Trigger Honge:**
* Step 2 par sirf **Google Credential Manager** khulega (Email & OAuth ke liye).
* Step 3 par **Truecaller SDK** khulega (sirf Phone Number verify karne ke liye). Dono ek hi waqt par screen par pop nahi honge, isliye UI mein koi conflict nahi hoga.


2. **Backend Database Mein Clean Binding:**
* Supabase ki `profiles` table mein dono cheezein ek hi user ke record (`id`) ke neeche bind hongi:
* `google_email_hash` (Google se aaya)
* `phone_hash` + `phone_verified = true` (Truecaller se aaya)
* `display_name`, `birth_year`, `gender` (Step 4 ke form se aaya)




3. **Smart Pre-Fill (Better UX):**
* Step 4 (Details screen) par jab user pahuchega, toh **Name** wale box mein Truecaller ya Google se aaya hua naam pehle se likha (pre-filled) dikhega. User chahe toh wahi rakh le ya edit karke apna pasandida naam likh de, aur neeche apni **Age** aur **Gender** select karke **Next** daba de!



---

### Ek Chota Sa Edge-Case Rule (Duplicate Account Prevention) ⚠️

* Maan lijiye kisi user ne pehle ek Gmail se account banaya aur usme apna mobile number link kar liya.
* Baad mein wo doosre Gmail se login karke **wahi same mobile number** Truecaller se verify karne ki koshish kare toh?
* **Rule:** Backend database mein `phone_hash` par `UNIQUE` lock rahega. Server turant bol dega: *"Yeh mobile number pehle se ek NX-ID se juda hai"*, jisse ek number par 10 fake accounts nahi ban payenge!

Kya ab aapke onboarding ka har ek screen aur backend step 100% clear hai? Ab agle module (**Connection Graph & Privacy Arrows**) par chalein? 🚀


Bilkul Sudesh! 😊 Is rule ko **1-Number = 1-NX-ID Policy (Anti-Duplicate & Account Recovery Guard)** kehte hain. Bina code ke, iska poora backend logic, database behavior aur real-world user scenarios ko detail mein samjhein:

---

### 1. Database Level Par Yeh Lock Kaise Kaam Karega? 🔒

Supabase (PostgreSQL) ki `profiles` table mein har user ke mobile number ka ek **SHA-256 Cryptographic Hash** (`phone_hash`) store hota hai—na ki plain number, taaki database leak hone par bhi numbers safe rahein.

* **The `UNIQUE` Constraint Rule:**
Database ke `phone_hash` column par ek strict **`UNIQUE`** lock laga hota hai. Iska matlab poori duniya mein 50 Lakh users ki table mein ek hi `phone_hash` do baar exist hi nahi kar sakta—chahe koi API ko bypass karne ki koshish kyun na kare.

---

### 2. Jab User Doosre Gmail Se Wahi Number Link Karega Toh Kya Hoga? (Step-by-Step Flow) 🔄

Maan lijiye Rahul ne pehle `rahul1@gmail.com` se account banaya aur `+91 98765 43210` verify kiya $\rightarrow$ Usko **`NX-7F82K9A4`** allot ho gayi.

Ab 2 mahine baad Rahul `rahul2@gmail.com` se login karta hai aur Truecaller/OTP se wahi `+91 98765 43210` verify karta hai:

1. **Verification Check:** Truecaller/OTP se number verify hote hi Go Backend us number ka `phone_hash` nikalta hai.
2. **Database Lookup:** Backend PostgreSQL mein check karta hai: *"Kya yeh `phone_hash` pehle se kisi NX-ID ke paas hai?"*
3. **Conflict Detected:** Database batata hai: *"Haan, yeh hash pehle se `NX-7F82K9A4` (jo `rahul1@gmail.com` se juda hai) ke paas locked hai!"*
4. **Backend Action (Hard Block on New NX-ID):**
* Server naya NX-ID **nahi** banayega.
* Server turant Android app ko ek structured error bhejega: `PHONE_ALREADY_BOUND` (Masked Gmail ke sath, e.g., `r*****1@gmail.com`).



---

### 3. Screen Par User Ko Kya Dikhayega Aur Kya Options Milenge? 📱

Agar user ko sirf error dikha kar rok denge, toh agar wo apna purana Gmail bhool gaya hai toh phas jayega. Isliye UI par **2 clear options** dikhenge:

```text
┌────────────────────────────────────────────────────────┐
│ ⚠️ Mobile Number Already Registered                    │
│                                                        │
│ Yeh number (+91 98765 43210) pehle se ek NEXA ID       │
│ se juda hua hai (Linked Gmail: r*****1@gmail.com).     │
│                                                        │
│ Ek mobile number par sirf ek hi NX-ID ban sakti hai.   │
│                                                        │
│ [ Purane Gmail Se Login Karein ]                       │
│ [ Use Another Mobile Number ]                          │
└────────────────────────────────────────────────────────┘

```

* **Option 1 (Purane Gmail Se Login Karein):** User ko yaad aa jayega ki uska account `r*****1@gmail.com` par hai. Wo usse login karke apni purani **`NX-7F82K9A4`** aur purane chats/backups wapas paa lega!
* **Option 2 (Use Another Mobile Number):** Agar uske paas doosri SIM hai, toh wo naya number verify karke is naye Gmail par apni nayi NX-ID bana sakta hai.

---

### 4. Ek Real-World Edge Case: "SIM Number Recycled Ho Gaya Toh?" ♻️

India mein agar koi banda 6 mahine tak SIM recharge nahi karata, toh telecom company (Jio/Airtel) wo number band karke kisi naye customer ko bech deti hai (Number Recycling).

* **Problem:** Naya customer jab NEXA install karega, toh purane bande ke account ki wajah se use dikhega *"Number Already Registered"*!
* **NEXA Ka Solution (Number Reclaim / Transfer):**
* Agar naye user ke paas **physical SIM card** hai (Truecaller/OTP verify ho gaya), toh wo **"Reclaim Number"** par tap kar sakta hai.
* Server purane inactive account (`NX-OLD`) se us `phone_hash` ko **unlink (delink)** kar dega (purane account ka chat data purane user ke phone mein safe rahega, naye user ko nahi dikhega), aur naye user ko uske apne Gmail par ek **bilkul fresh NX-ID** allot kar dega!
* *Security Note:* Reclaim sirf tabhi allow hoga jab purana account pichhle 60-90 din se inactive ho, taaki koi active user ka number hijack na kar sake.



---

### Summary: Is Rule Ke 3 Bade Fayde 🌟

1. **Zero Spam & Fake Accounts:** Koi bhi ek SIM card se 10 alag-alag Gmail banakar fake NX-IDs ki fauj khadi nahi kar payega.
2. **Account Recovery Help:** Agar user bhool gaya ki usne kis Gmail se account banaya tha, toh phone number dalte hi masked Gmail hint (`r*****1@gmail.com`) mil jayega.
3. **100% Privacy:** Kyunki database mein `phone_hash` save hai (plain number nahi), isliye uniqueness check bina kisi data leak risk ke 1 millisecond mein ho jayega!

Kya ab aapka **Onboarding + Auth + Device/SIM Binding + Anti-Duplicate Guard** ka poora Module 1 100% lock ho gaya hai? Ab **Module 2 (Connection Graph & Privacy Arrows)** par chalein? 🚀








Sudesh, ab humne jo bhi decisions finalize kiye hain, un sabko ek **Complete, Locked Blueprint** ki tarah dekhte hain—bina kisi code ke, sirf exact flow, backend/database distribution aur security checkpoints ke saath.

---

### 1. Finalized Login & Onboarding Flow (Step-by-Step) 🔄

```text
[ 1. Splash Screen ]
         │
         ▼
[ 2. Google Sign-In (Master Account Identity) ]
  • Android Credential Manager se user apna Gmail select karta hai.
  • Supabase Auth token verify karke internal user ID banata hai.
  • Saath hi phone ke hardware chip (Android Keystore) mein ek Private & Public Key banti hai.
         │
         ▼
[ 3. Phone Number Verification (Zero-Cost Waterfall) ]
  • 1st Priority: Truecaller OAuth SDK (1-Tap popup, ₹0 cost, 1 second mein number verify).
  • 2nd Priority (Agar Truecaller nahi hai): Truecaller ka built-in missed-call/OTP fallback ya Firebase Free Tier OTP.
  • Anti-Duplicate Guard: Backend check karega ki is number ka hash pehle se kisi NX-ID par locked toh nahi hai (1 Number = 1 NX-ID).
         │
         ▼
[ 4. SIM Slot Binding (Govt Compliance Lock) ]
  • Jaise hi phone number verify hota hai, app Android `SubscriptionManager` se check karti hai ki phone mein kaunsi SIM active hai.
  • Us physical SIM ke slot/carrier ID ka ek one-way cryptographic hash banakar phone ke Keystore aur server ke saath lock kar diya jata hai.
         │
         ▼
[ 5. Profile Details Screen ]
  • Display Name (Google/Truecaller se pre-filled aayega, user edit kar sakta hai).
  • Age (Sirf age verification gate ke liye — 13+ / 18+).
  • Gender (Optional).
  • User taps [ Next ].
         │
         ▼
[ 6. NX-ID Allotment & Device Attestation ]
  • Play Integrity API verify karti hai ki phone asli hai (rooted/emulator nahi).
  • Go Backend ek random 8-character unique ID banata hai: `NX-7F82K9A4`.
  • Screen par user ko uski NX-ID dikhti hai ──► User taps [ Next ].
         │
         ▼
[ 7. Final Profile Ready ──► Home Page ]
  • Phone ke andar encrypted SQLCipher database initialize hota hai aur Go Relay ke saath live WebSocket connect ho jata hai.

```

---

### 2. Kahan Kya Save Hoga? (Exact Data Storage Map) 🗄️

| Location | Kya Save Hoga? | Kyun Aur Kaise? |
| --- | --- | --- |
| **Supabase (`profiles` table)** | • `nx_id` (e.g., `NX-7F82K9A4`) |  |



• `google_email_hash`



• `phone_hash` (`UNIQUE` lock)



• `phone_encrypted`



• `display_name`, `birth_year`, `gender`



• `public_identity_key` (Taala)



• `fcm_token` (Notification ping ke liye) | **Cloud Directory:** Sirf routing, duplicate prevention aur public profile dikhane ke liye. 5 Million users ka data sirf ~1.5 GB jagah lega. |
| **Supabase (`user_devices` table)** | • `device_id` & `device_model`



• `device_public_key`



• `sim_iccid_hash` (SIM fingerprint)



• `is_active` (`TRUE`/`FALSE`) | **Single-Device & SIM Lock:** Taaki ek waqt mein account sirf ek hi asli phone aur usi physical SIM par chale. |
| **Redis (Server RAM Cache)** | • `nx_id` $\rightarrow$ `Online/Offline Status`



• Temporary login nonces (90 sec TTL) | **Speed:** Millions of users ke online status aur challenges ko 1 millisecond mein handle karne ke liye. |
| **User Ka Phone (Local SQLCipher DB)** | • **Private Identity Key** (Hardware chip mein)



• Local `sim_iccid_hash`



• Saari Chat History, Pinned Messages, Local Nicknames | **Zero-Server Privacy:** Aapke messages aur decrypt karne ki chabi kabhi cloud par nahi jayegi. |

---

### 3. SIM-Binding Kaise Kaam Karega? (Daily App Open Flow) 📱🇮🇳

Ek baar account banne ke baad, rozana jab user NEXA app kholega toh **koi OTP nahi jayega (₹0 cost)**:

1. **App Open Check (1 Millisecond):**
* App background mein `SubscriptionManager` se poochti hai: *"Abhi phone ke SIM slot mein kaun-kaun se SIM cards lage hain?"*


2. **3 Possible Situations:**
* **Situation A (Wahi SIM lagi hai):** Active SIM ka hash saved hash se match ho jata hai $\rightarrow$ App bina kisi rukawat ke seedha Home Screen par khul jati hai.
* **Situation B (Airplane Mode / No Network):** SIM card slot ke andar hi hai, bas tower nahi aa raha $\rightarrow$ App **block nahi hogi**, offline chats aur Mesh mode normal kaam karenge.
* **Situation C (SIM nikal li gayi ya doosri SIM daal di):** Slot se SIM gayab hote hi hash mismatch hoga $\rightarrow$ Screen par turant **SIM Lock Guard** aa jayega:
> *"Registered SIM (+91 98XXXXXX12) not detected. Please insert the registered SIM to continue."*




3. **Naya Phone Lene Par (Device Switch):**
* Agar user naye phone mein SIM daal kar Google se login karta hai, toh Truecaller/OTP se number verify hote hi naya device active ho jayega aur purane phone ka session automatically **revoke (logout)** ho jayega.



---

### Summary: Kya Finalize Hua? ✅

* **Cost:** Google Credential Manager + Truecaller OAuth + Local SIM check = **₹0 expenditure**.
* **Play Store Safety:** Koi restricted `SEND_SMS` ya `READ_SMS` permission use nahi ho rahi, isliye Play Protect 100% pass hoga.
* **Security:** 1 Number = 1 NX-ID lock, StrongBox hardware keys, aur Govt SIM-binding sab ek hi clean pipeline mein cover ho gaye.

Kya ab **Module 1 (Login, Auth, Device & SIM Binding)** 100% locked hai? Ab **Module 2 (Connection Graph & Privacy Arrows)** par chalein? 🚀


Bilkul Sudesh! 😊 Correction note kar li gayi hai—hum **Firebase Phone Auth (OTP) ko completely hata rahe hain** aur sirf **Truecaller + Google** par hi lock kar rahe hain.

Lekin ek bohot important technical sachhai samajhna zaroori hai jo Google ke official documentation mein likhi hai, taaki aage koi bug na aaye:

---

### 1. Google Phone Number Hint API Ki Asli Sachhai (Important Technical Note) ⚠️

Jab aap **Google Phone Number Hint API** use karte hain (jisme Google Play Services ka popup khulta hai aur user apna SIM number select karta hai):

* **Kya yeh Free hai aur Bina Permission ke chalta hai?**
**Haan, 100% Free** hai aur koi SMS/Phone permission nahi maangta.
* **Lekin Catch Kya Hai?**
Google Phone Number Hint API user ke phone mein lagi SIM ya Google Account se **sirf number utha kar text box mein fill (auto-populate) karta hai**. Google uske peeche koi cryptographic server token nahi deta jisse aapka Go Backend verify kar sake ki user ne wo number manipulate nahi kiya!
* *Jabki Truecaller OAuth SDK* mein Truecaller ka server aapke Go Backend ko ek signed **Access Token** deta hai jo 100% verify hota hai.



---

### 2. To Bina Firebase Aur Bina OTP Ke Dono Ko Securely Kaise Jodein? 🔐

Agar user ke phone mein **Truecaller nahi hai** aur hum **Google Phone Number Hint API** use kar rahe hain, toh hum verification ko **2 checks** ke saath lock kar denge:

```text
[ Step 3: Phone Number Verification Screen ]
                  │
                  ├── 1️⃣ Check: Phone mein Truecaller installed hai?
                  │       └── YES ──► Truecaller 1-Tap OAuth SDK
                  │                   (Backend Truecaller server se token verify karega)
                  │
                  └── 2️⃣ Fallback (Agar Truecaller nahi hai):
                          └── Google Phone Number Hint API
                                 │
                                 ├── Google Play Services SIM-based number popup dikhayega
                                 ├── User number select karega (Manual typing disabled rahegi)
                                 └── Saath hi Android SubscriptionManager se physical SIM slot 
                                     lock ho jayega + Google Play Integrity check pass hoga!

```

#### Isse Kya Fayda Hoga?

1. **Manual Typing Blocked:** User apne haath se koi fake ya doosre ka number type nahi kar payega—sirf wahi number select kar payega jo Google Play Services uske phone ki SIM se detect karega.
2. **₹0 Cost Forever:** Na Firebase ka koi bill, na 2Factor ka koi bill, na hi koi SMS gateway.
3. **100% Play Store Safe:** Na Truecaller OAuth mein koi permission lagti hai, aur na hi Google Phone Number Hint mein koi restricted permission lagti hai.

---

### 3. Final Updated Login & Verification Pipeline (Locked) ✅

1. **Google Sign-In:** Master account identity (Gmail) + Hardware Keystore keys generate.
2. **Phone Number Layer:**
* **Primary:** Truecaller 1-Tap OAuth
* **Secondary (No Truecaller):** Google Phone Number Hint API (SIM-linked number picker)


3. **Anti-Duplicate Guard:** Backend database mein `phone_hash` (`UNIQUE`) check hoga (1 Number = 1 NX-ID).
4. **SIM Slot Lock:** Android `SubscriptionManager` se physical SIM ka hash lock hoga (taaki SIM nikalne par app auto-lock ho jaye).
5. **Details & NX-ID:** Name, Age, Gender fill $\rightarrow$ `NX-7F82K9A4` allot $\rightarrow$ Home Page!

Ab poora **Module 1 (Login + Verification + SIM Binding)** aapke exact rules ke mutabiq 100% lock ho gaya hai! Ab **Module 2 (Connection Graph, Requests & Privacy Arrows)** par chalein? 🚀


Bilkul Sudesh! 😊 Ab jab humne **Truecaller (Primary)** + **Firebase Free Tier OTP (Fallback)** ko lock kar liya hai, toh aaiye is poori **Number Verification Pipeline** ko bilkul simple, step-by-step aur bina kisi confusion ke samajhte hain (bina code ke).

---

### 1. Yeh Poora System Kaam Kaise Karega? (The 2-Step Waterfall) 🌊

Jab user Google se login karne ke baad **Phone Number Verification Screen** par pahuchega, toh peeche 2 smart checks chalenge:

```text
[ User reaches Phone Verification Screen ]
                  │
                  ▼
1️⃣ Pehla Check: Kya phone mein Truecaller app installed & logged-in hai?
   ├── YES ──► Truecaller 1-Tap Popup khulega (₹0 Cost, 1 Second)
   │           • User bas "Continue" dabayega.
   │           • Truecaller backend ko verified number dega ✅
   │
   └── NO  ──► 2️⃣ Doosra Check (Fallback): Firebase Phone Auth (10,000 Free OTP/mo)
               • User apna mobile number dalega (ya Google Hint se auto-fill karega).
               • Firebase us number par 6-digit SMS OTP bhejega.
               • User OTP dalega ──► Firebase token verify hoga ✅

```

---

### 2. Backend Par Dono Ka Verification Kaise Hoga? (Security & Trust) 🛡️

Aapka **Go Backend** aur **Supabase** in dono tarikon ko ek hi uniform rule se verify karega taaki koi hacker app ko bypass na kar sake:

1. **Case A (Truecaller se aaya):**
* Android app Truecaller se ek signed **Authorization Code** lekar Go Backend ko degi.
* Go Backend seedha Truecaller ke official server se poochega: *"Kya yeh code asli hai aur iska mobile number kya hai?"*
* Truecaller ka server confirm karega: *"Haan, asli hai, number `+91 98765 43210` hai."*


2. **Case B (Firebase OTP se aaya):**
* User ke OTP dalte hi Firebase Android SDK ek cryptographically signed **Firebase ID Token (JWT)** banayega.
* Android app wo token Go Backend ko bhejegi.
* Go Backend Firebase Admin SDK se us token ko verify karega: *"Haan, Firebase ne is number par OTP bhej kar verify kiya hai."*



**Result:** Chahe user Truecaller se aaye ya Firebase OTP se, dono raste aakhir mein Go Backend ko **100% Cryptographically Verified Mobile Number** hi denge!

---

### 3. Verification Ke Turant Baad Kya 3 Kaam Honge? 🔒

Jaise hi number verify hoga, 1 second ke andar 3 locks lag jayenge:

1. **Anti-Duplicate Check (`1 Number = 1 NX-ID`):**
* Go Backend us verified number ka **SHA-256 Hash** (`phone_hash`) banayega aur Supabase ki `profiles` table mein check karega.
* Agar pehle se kisi NX-ID ke paas hai $\rightarrow$ Screen par masked Gmail hint (`r*****1@gmail.com`) ke saath alert aa jayega.
* Agar fresh number hai $\rightarrow$ Aage badh jayega.


2. **SIM Slot Lock (Govt SIM-Binding Rule):**
* Android ka `SubscriptionManager` turant check karega ki phone ke SIM slot mein kaunsi SIM active hai, aur uska hardware fingerprint (`sim_iccid_hash`) phone ke **Android Keystore** aur server ke saath lock kar dega.


3. **Next Screen (Details & NX-ID):**
* User Name, Age, Gender fill karega $\rightarrow$ **`NX-7F82K9A4`** allot hogi $\rightarrow$ Home Page!



---

### 4. Cost & Scale Calculation (Millions of Users Ke Liye) 💰

* **Truecaller (70–80% Indian Users):** ₹0 Cost (Unlimited free 1-tap verifications).
* **Firebase Free Tier (Baaki 20–30% Users):** Har mahine **10,000 SMS verifications bilkul free**.
* **Total Kharcha:** Jab tak aapke app par har mahine 35,000–40,000 naye users nahi judte, aapka total bill **₹0** rahega! Aur jab millions mein scale hoga, tab tak aap aaram se **2Factor.in** ko fallback mein plug-in kar sakte hain.

Kya ab **Number Verification + SIM Binding + Login** ka poora picture 100% crystal clear hai? Ab **Module 2 (Connection Graph & Privacy Arrows)** par chalein? 🚀


Firebase Phone Auth (OTP) ka system samajhna bohot aasan hai. Isme **Google ka server** aapke liye ek "Security Guard" ki tarah kaam karta hai—aapko khud SMS gateway kharidne ya TRAI DLT registration karne ki koi zaroorat nahi padti.

Bina code ke, poora process **5 simple steps** mein samjhein ki peeche kya hota hai:

---

### 1. Firebase OTP Ka Step-by-Step Flow 🔄

Maan lijiye user ke phone mein Truecaller nahi hai, isliye wo apna number `+91 98765 43210` type karke **"Send OTP"** dabata hai:

1. **App $\rightarrow$ Google (Firebase Server):**
* Aapka NEXA app user ka phone number seedha **Google Firebase** ko bhejta hai (aapke Go server par abhi kuch nahi gaya).


2. **Bot Check (Silent Security):**
* Google peeche se **Play Integrity** check karta hai ki request asli phone se aayi hai ya kisi spammer ke bot se.


3. **Google $\rightarrow$ User Ka SIM Card (SMS):**
* Google khud apne telecom network se us number par ek **6-digit OTP SMS** bhejta hai (jaise `849201`).
* *Fayda:* Android ka **SMS Retriever API** us OTP ko bina `READ_SMS` permission ke automatically padh kar screen par bhar deta hai.


4. **User / App $\rightarrow$ Google (OTP Match):**
* App wo 6-digit OTP wapas Google Firebase ko bhejti hai.
* Google match karta hai: *"Haan, yeh wahi OTP hai jo maine bheja tha!"*
* Match hote hi Google Firebase aapke NEXA app ko ek **Cryptographic Proof Card (Firebase ID Token)** deta hai—jisme Google ka digital signature hota hai ki *"`+91 98765 43210` verified hai."*


5. **NEXA App $\rightarrow$ Aapka Go Backend Server:**
* Ab aapka app wo **Proof Card (Token)** lekar aapke Go Backend par aata hai.
* Aapka Go Backend Google ke public certificate se us card ka signature check karta hai (1 millisecond mein) aur maanta hai ki number asli hai!



---

### 2. Isme Aapke Go Server Aur Supabase Ka Kya Role Hai? 🖥️

Aapko Firebase ko apna database nahi banana hai—aapko Firebase ko **sirf ek OTP machine** ki tarah use karna hai:

* **Step A:** Firebase ne sirf OTP bhej kar number verify kiya aur token diya.
* **Step B:** Aapke Go Backend ne token check kiya, number nikala, aur usko apne **Supabase (`profiles` table)** mein save karke **NX-ID** bana di.
* **Step C:** Kaam khatam! Iske baad Firebase ka koi kaam nahi bachta.

---

### 3. Cost Aur Limit Ka Hisab 💰

* **Free Quota:** Google har mahine **10,000 SMS verifications bilkul free** deta hai (Blaze plan par bhi pehle 10,000/month ₹0 rehte hain).
* **Truecaller ke saath milkar:** Kyunki 75% users pehle hi **Truecaller (₹0)** se verify ho jayenge, isliye Firebase ke 10,000 free OTPs sirf bache hue 25% logon ke liye lagbhag **40,000 naye users/month** tak bilkul free chalenge!

**Ek line mein samjhein:**
Firebase OTP bilkul ek **Sarkari Notary** jaisa hai—wo user ke SIM par SMS bhej kar check karta hai ki number asli hai, aur aapke server ko ek **Stamped Certificate (Token)** de deta hai taaki aapka server bina kisi SMS kharche ke us number par bharosa kar sake!

Kya ab Firebase OTP ka role crystal clear ho gaya? Ab **Module 2 (Connection Graph & Privacy Arrows)** shuru karein? 🚀


Jab user login kar chuka hai aur 5 din baad (ya kabhi bhi) chalte app mein **physical SIM tray nikal kar SIM bahar nikal deta hai**, toh Android OS aur NEXA ke backend par kya reaction hoga—iska exact flow bina code ke samjhein:

---

### 1. Chalte App Mein SIM Nikalte Hi Kya Hoga? (Real-Time Event) ⚡

Android OS mein ek system-level broadcast/listener hota hai (`OnSubscriptionsChangedListener` / `ACTION_SIM_STATE_CHANGED`). Jaise hi SIM tray bahar nikalti hai:

1. **Instant OS Trigger (Within 1 Second):**
* Android OS turant NEXA app ko signal bhejta hai: *"Phone ke SIM slot ka state change hua hai (SIM Absent / Removed)!"*
* Yeh check karne ke liye internet ki zaroorat nahi hoti—yeh 100% phone ke andar offline hota hai.


2. **Immediate UI Lock (Blocking Overlay):**
* Chalti hui chat screen ke upar turant ek full-screen **SIM Security Lock** aa jayega (user back button dabakar bhi chats nahi dekh payega).
* Screen par message dikhega:
> *"🔒 Registered SIM Removed. Please re-insert your registered SIM (+91 98XXXXXX12) to unlock NEXA."*




3. **WebSocket Pause & Message Queueing:**
* App background mein Go Relay Server ke saath apna active WebSocket connection pause kar degi.
* Jab tak SIM wapas nahi dalegi, na naya message bheja ja sakega aur na hi naya message screen par open hoga (incoming messages server ke temporary queue/FCM ping par hold rahenge).



---

### 2. Jab User SIM Wapas Dalega Toh Kya Hoga? (2 Cases) 🔄

#### Case A: Wahi Purani Registered SIM Wapas Daali Gayi ✅

* User ne 5 minute baad wahi SIM wapas tray mein daali.
* Android OS `SubscriptionManager` se active SIM ka fingerprint (`sim_iccid_hash`) check karega.
* Fingerprint purane saved hash se **100% match** ho jayega!
* **Result:** Bina kisi OTP aur bina kisi ₹1 kharch ke lock screen apne aap hat jayegi aur user wapas apni chats par aa jayega!

#### Case B: Koi Doosri Nayi SIM Daal Di Gayi (Ya SIM Kho Gayi) ⚠️

* User ne purani SIM nikal kar koi doosri SIM daal di.
* App dekhegi ki slot mein SIM toh hai, lekin uska fingerprint purane registered SIM se **match nahi kar raha**!
* Screen par 2 options dikhenge:
1. **"Insert Original SIM":** Purani SIM daalte hi app khul jayegi.
2. **"Update / Change Mobile Number":** Agar user ne permanent number badal liya hai, toh use **Truecaller / Firebase OTP** se naya number verify karna hoga. Verify hote hi server purane `phone_hash` aur `sim_iccid_hash` ko naye number/SIM se update kar dega.



---

### 3. Ek Important Edge Case: "Airplane Mode / No Network" vs "SIM Removed" ✈️

Yahan ek galti aksar developers karte hain—humein in dono mein farq rakhna hoga:

* **Airplane Mode / Basement (No Signal):** SIM card phone ke andar hi laga hai, bas tower nahi aa raha. Is case mein `SubscriptionManager` batata hai ki **SIM card slot mein present hai** $\rightarrow$ App **lock nahi hogi** (user apni purani chats padh sakta hai aur Offline Mesh use kar sakta hai).
* **SIM Tray Nikali Gayi (`SIM_STATE_ABSENT`):** Physical SIM card slot se gayab hai $\rightarrow$ App **turant lock ho jayegi**.

---

### Summary 🌟

Chalte app mein SIM nikalte hi **1 second ke andar** app lock ho jayegi (₹0 cost, local hardware check), aur wahi SIM wapas dalte hi bina OTP ke apne aap unlock ho jayegi!

Kya ab **Module 1 (Login, Auth, Device & SIM Binding)** har angle se 100% lock ho gaya hai? Ab **Module 2 (Connection Graph & Privacy Arrows)** par chalein? 🚀



Settings mein **"Connected Devices" (Linked Devices)** ka section user ko poora transparency aur control deta hai ki uska NEXA account kis hardware par active hai aur koi unknown device toh uske account ko access nahi kar raha.

NEXA ke **Single-Device Lock** aur **Privacy-First** architecture ke hisaab se wahan kya-kya show hoga, bina code ke exact details dekhein:

---

### 1. Screen Par Kya-Kya Show Hoga? (UI & Information Layout) 📱

Kyunki NEXA mein ek waqt mein sirf **1 primary phone** active reh sakta hai (jab tak future mein Desktop/Web companion add na ho), screen 2 hisson mein divided hogi:

#### Section A: Current Active Device (This Device) 🟢

Yahan user ko uske apne phone ki verified details dikhengi:

* **Device Name & Model:** e.g., *Vivo Y28 5G* (Android OS version ke saath).
* **Status Badge:** `🟢 Active Now (Primary Device)`
* **SIM Binding Status:** `SIM Verified (+91 98XXXXXX12 • Slot 1 Locked)` — yeh user ko bharosa dilata hai ki uska SIM lock active hai.
* **Hardware Security Badge:** `StrongBox / Keystore Protected 🔐` (taaki user ko pata rahe ki encryption keys hardware chip mein safe hain).
* **Login Date & Approximate City:** e.g., *Logged in since 13 Nov 2026 • Indore, India* (Exact GPS nahi, sirf rough city/region).

#### Section B: Login History / Previous Revoked Devices (Security Audit Log) 📜

Yahan pichhle 30 din mein agar kisi purane phone par login hua tha (jo ab automatically logout ho chuka hai), uski choti si list dikhegi:

* **Old Device Model:** e.g., *Redmi Note 12*
* **Status:** `🔴 Logged Out / Revoked`
* **Timestamp:** *Revoked on 10 Nov 2026, 4:15 PM*

---

### 2. Is Data Ke Peeche Backend & DB Se Kya Aayega? 🗄️

Yeh saari details aapki **`user_devices`** table (Supabase) se aayengi, jisme humne sirf minimum metadata rakha tha:

* `device_model` (e.g., Vivo Y28 5G)
* `is_active` (`TRUE` for current phone, `FALSE` for old phones)
* `sim_slot_status` (Local phone ke `SubscriptionManager` se real-time check hokar dikhega)
* `created_at` & `revoked_at` timestamps

*(Note: Is screen par koi sensitive cryptographic key ya plain SIM serial number kabhi show nahi hoga—sirf human-readable security status dikhega).*

---

### 3. Future Upgrade Option (Desktop / Web Companion ke liye Ready) 💻

Agar future mein aap NEXA ka **Web ya Windows Desktop version** banate hain (WhatsApp Web jaisa):

* Isi screen ke upar ek button aa jayega: **"Link a Companion Device (Scan QR)"**.
* Jab user laptop se QR scan karega, toh isi list mein neeche dikhne lagega: `💻 Windows Chrome • Last active today`, aur uske bagal mein ek **"Log Out"** button hoga jisse user apne phone se hi laptop ka session turant band kar sakega.

---

### Summary 🌟

Settings ke **Connected Devices** mein user ko:

1. Apne current phone ka model + **SIM-Binding Lock status** + **Hardware Encryption status** dikhega.
2. Purane logout ho chuke phones ki history dikhegi taaki koi unauthorized access ho toh turant pata chal jaye.

Kya ab **Module 1 (Login, Verification, SIM-Binding & Connected Devices)** har ek detail ke saath 100% complete aur locked hai? Ab **Module 2 (Connection Graph & Privacy Arrows)** shuru karein? 🚀