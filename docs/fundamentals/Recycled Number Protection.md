India mein SIM recycling (90 din inactive rehne par telecom operator number kisi naye user ko allot kar deta hai) bohot common hai. Agar isko sahi se handle na kiya jaye, toh **privacy leak** (purane dost ke messages naye stranger ko chale jana) ya **account lock** ho sakta hai.

NEXA ke **Google Master Identity + `1 Number = 1 NX-ID` + Hardware Keystore** architecture mein Recycled Number ke **total 6 possible scenarios** bante hain. Har ek scenario aur uska exact defense mechanism samjhein:

---

### Scenario 1: Naya User Us Recycled Number Se Naya Account Banaye (New Sign-Up) 🆕📱

* **Situation:** Purane user (Rahul) ne 6 mahine se SIM band rakhi thi. Airtel/Jio ne woh number naye user (Vikram) ko de diya. Ab Vikram Google Sign-In ke saath us number se NEXA par naya account bana raha hai.
* **System Action (Backend & DB):**
1. Server check karta hai ki kya is `phone_hash` par pehle se koi account hai—ha, Rahul ka account (`NX-OLD-111`) tha.
2. Lekin Vikram ka **Google Account (`google_email_hash`)** aur **Hardware Device Key** alag hai, aur uske paas abhi active physical SIM hai!
3. **Reclaim/Transfer Rule:** Server purane account (`NX-OLD-111`) se us `phone_hash` ko detach (unlink) kar deta hai, aur Vikram ke liye ek **bilkul naya `NX-NEW-999` ID** generate karke us par woh number link karta hai.
4. **Zero Data Leak:** Rahul ki purani chats, groups, aur connections Vikram ko **bilkul nahi milte**—Vikram ka account 100% fresh start hota hai.



---

### Scenario 2: Aapke Phonebook Mein Purana Number Save Hai, Aur Naya User NEXA Join Kare 👥⚠️

* **Situation:** Aapki contact list mein `"Rahul Dost"` ke naam se `+91 9876543210` save hai (jiski purani ID `NX-OLD-111` thi). Ab Vikram ne usi number se `NX-NEW-999` banaya.
* **System Action (Aapke Phone Par):**
1. Jab aapka phone contact hash sync karta hai, toh server batata hai ki `+91 9876543210` ab `NX-NEW-999` se juda hai.
2. **Chat Split Guard (No Merge!):** Aapka NEXA app Rahul ki purani chat (`NX-OLD-111`) ko Vikram (`NX-NEW-999`) ke saath **kabhi merge nahi karega**!
3. **UI Warning Banner:** Purani chat (`NX-OLD-111`) read-only archive ho jayegi aur uske neeche ek clear banner aayega:
> *"⚠️ This phone number is now registered to a new NEXA account (`NX-NEW-999`). Previous chat history remains separate for your privacy."*


4. **Encrypted SMS Lock:** Aapke local SQLCipher DB mein purane Rahul (`NX-OLD-111`) ke saath is number ka **SMS fallback link turant break** ho jayega, taaki aap galti se bhi offline SMS Vikram ko na bhej dein!



---

### Scenario 3: Purane User (Rahul) Ka Account Abhi Bhi Purane Phone Mein Logged-In Hai 📵🔒

* **Situation:** Rahul ne apna purana number band kar diya tha (ab naya number chalata hai), lekin uske purane phone ke NEXA app mein `NX-OLD-111` abhi bhi khula hai (Wi-Fi par).
* **System Action (SIM-Binding & Number Reclaim):**
1. Jaise hi naya banda (Vikram) physical SIM laga kar number reclaim karta hai, Go Backend ko pata chal jata hai ki `phone_hash` ka maalik badal gaya hai.
2. Rahul ke purane phone par ek silent server signal jata hai: **`NUMBER_RECLAIMED`**.
3. Rahul ke purane phone par NEXA app lock ho jayegi aur screen par prompt aayega:
> *"⚠️ Your registered number (+91 9876543210) has been activated on a new SIM/account. Link a new mobile number to continue using your `NX-OLD-111` account."*


4. **Account Recovery (Google Master Identity Ka Fayda):** Kyunki Rahul ka **Google Sign-In** uske paas hai, isliye woh apna naya mobile number verify karke apni purani **`NX-OLD-111` identity aur groups ko bacha sakta hai**!



---

### Scenario 4: Aap Offline Hain Aur Purane Dost Ko Encrypted SMS Bhej Dein 📡❌

* **Situation:** Vikram ne aaj subah hi recycled number se naya account banaya, lekin aapka internet 2 din se band hai (toh aapke phone ko abhi tak pata nahi chala ki number badal gaya hai). Aap offline SMS (`NX1:...`) bhej dete hain.
* **System Action (Cryptographic Safety Net):**
1. Woh SMS telecom tower se Vikram ke phone par pahunchega.
2. Lekin Vikram ke NEXA app ke paas aapki **E2EE Shared Secret Key nahi hai** (kyunki key `NX-OLD-111` ke hardware chip mein thi, `NX-NEW-999` ke paas nahi!).
3. **Result:** Vikram ke phone par woh `NX1:...` packet **decrypt hi nahi hoga**—Vikram ka app usko silently drop/reject kar dega! Aapka private message stranger ke haath bilkul nahi lagega.



---

### Scenario 5: Purane User Ke Pending Offline Messages (Redis Dead-Drop Mailbox) 📬🗑️

* **Situation:** Kisi ne Rahul (`NX-OLD-111`) ko internet par 5 messages bheje the jab woh offline tha (jo Redis RAM mailbox mein pade the), aur isi beech Vikram ne number reclaim kar liya.
* **System Action:**
* Redis mailbox **`NX-ID` (`NX-OLD-111`)** ke naam par bana hota hai, phone number ke naam par nahi!
* Isliye Vikram (`NX-NEW-999`) ko woh 5 pending messages **kabhi nahi milenge**. Jab Rahul apna naya number link karke `NX-OLD-111` wapas active karega, tabhi woh messages Rahul ko milenge (ya 7 din mein expire ho jayenge).



---

### Scenario 6: Group Chats Mein Purane Number Ka Kya Hoga? 👥🛡️

* **Situation:** Rahul (`NX-OLD-111`) 15 groups ka member tha. Number recycle hokar Vikram (`NX-NEW-999`) ke paas chala gaya.
* **System Action:**
* Kyunki Group membership **`NX-ID`** se judi hoti hai (phone number se nahi), isliye Vikram kisi bhi group mein auto-add **nahi** hoga.
* Groups mein Rahul (`NX-OLD-111`) hi member rahega (jab tak woh naya number link na kare ya Admin use remove na kare).



---

### Ek Nazar Mein: Yeh 6 Scenarios Kaise Handle Hote Hain? 🌟

1. **Har Account Ki Asli Pehchan `NX-ID` + `Google Account` Hai** (Phone number sirf ek attachable/detachable link hai).
2. **Naye user ko hamesha nayi `NX-ID` milti hai**, isliye purani chats, groups, ya pending messages kabhi leak nahi hote.
3. **E2EE Keys hardware chip mein lock hoti hain**, isliye agar offline SMS galti se naye user ke phone par chala bhi jaye, toh bhi woh decrypt nahi ho sakta!



India mein SIM recycling (90 din inactive rehne par telecom operator number kisi naye user ko allot kar deta hai) bohot common hai. Agar isko sahi se handle na kiya jaye, toh **privacy leak** (purane dost ke messages naye stranger ko chale jana) ya **account lock** ho sakta hai.

NEXA ke **Google Master Identity + `1 Number = 1 NX-ID` + Hardware Keystore** architecture mein Recycled Number ke **total 6 possible scenarios** bante hain. Har ek scenario aur uska exact defense mechanism samjhein:

---

### Scenario 1: Naya User Us Recycled Number Se Naya Account Banaye (New Sign-Up) 🆕📱

* **Situation:** Purane user (Rahul) ne 6 mahine se SIM band rakhi thi. Airtel/Jio ne woh number naye user (Vikram) ko de diya. Ab Vikram Google Sign-In ke saath us number se NEXA par naya account bana raha hai.
* **System Action (Backend & DB):**
1. Server check karta hai ki kya is `phone_hash` par pehle se koi account hai—ha, Rahul ka account (`NX-OLD-111`) tha.
2. Lekin Vikram ka **Google Account (`google_email_hash`)** aur **Hardware Device Key** alag hai, aur uske paas abhi active physical SIM hai!
3. **Reclaim/Transfer Rule:** Server purane account (`NX-OLD-111`) se us `phone_hash` ko detach (unlink) kar deta hai, aur Vikram ke liye ek **bilkul naya `NX-NEW-999` ID** generate karke us par woh number link karta hai.
4. **Zero Data Leak:** Rahul ki purani chats, groups, aur connections Vikram ko **bilkul nahi milte**—Vikram ka account 100% fresh start hota hai.



---

### Scenario 2: Aapke Phonebook Mein Purana Number Save Hai, Aur Naya User NEXA Join Kare 👥⚠️

* **Situation:** Aapki contact list mein `"Rahul Dost"` ke naam se `+91 9876543210` save hai (jiski purani ID `NX-OLD-111` thi). Ab Vikram ne usi number se `NX-NEW-999` banaya.
* **System Action (Aapke Phone Par):**
1. Jab aapka phone contact hash sync karta hai, toh server batata hai ki `+91 9876543210` ab `NX-NEW-999` se juda hai.
2. **Chat Split Guard (No Merge!):** Aapka NEXA app Rahul ki purani chat (`NX-OLD-111`) ko Vikram (`NX-NEW-999`) ke saath **kabhi merge nahi karega**!
3. **UI Warning Banner:** Purani chat (`NX-OLD-111`) read-only archive ho jayegi aur uske neeche ek clear banner aayega:
> *"⚠️ This phone number is now registered to a new NEXA account (`NX-NEW-999`). Previous chat history remains separate for your privacy."*


4. **Encrypted SMS Lock:** Aapke local SQLCipher DB mein purane Rahul (`NX-OLD-111`) ke saath is number ka **SMS fallback link turant break** ho jayega, taaki aap galti se bhi offline SMS Vikram ko na bhej dein!



---

### Scenario 3: Purane User (Rahul) Ka Account Abhi Bhi Purane Phone Mein Logged-In Hai 📵🔒

* **Situation:** Rahul ne apna purana number band kar diya tha (ab naya number chalata hai), lekin uske purane phone ke NEXA app mein `NX-OLD-111` abhi bhi khula hai (Wi-Fi par).
* **System Action (SIM-Binding & Number Reclaim):**
1. Jaise hi naya banda (Vikram) physical SIM laga kar number reclaim karta hai, Go Backend ko pata chal jata hai ki `phone_hash` ka maalik badal gaya hai.
2. Rahul ke purane phone par ek silent server signal jata hai: **`NUMBER_RECLAIMED`**.
3. Rahul ke purane phone par NEXA app lock ho jayegi aur screen par prompt aayega:
> *"⚠️ Your registered number (+91 9876543210) has been activated on a new SIM/account. Link a new mobile number to continue using your `NX-OLD-111` account."*


4. **Account Recovery (Google Master Identity Ka Fayda):** Kyunki Rahul ka **Google Sign-In** uske paas hai, isliye woh apna naya mobile number verify karke apni purani **`NX-OLD-111` identity aur groups ko bacha sakta hai**!



---

### Scenario 4: Aap Offline Hain Aur Purane Dost Ko Encrypted SMS Bhej Dein 📡❌

* **Situation:** Vikram ne aaj subah hi recycled number se naya account banaya, lekin aapka internet 2 din se band hai (toh aapke phone ko abhi tak pata nahi chala ki number badal gaya hai). Aap offline SMS (`NX1:...`) bhej dete hain.
* **System Action (Cryptographic Safety Net):**
1. Woh SMS telecom tower se Vikram ke phone par pahunchega.
2. Lekin Vikram ke NEXA app ke paas aapki **E2EE Shared Secret Key nahi hai** (kyunki key `NX-OLD-111` ke hardware chip mein thi, `NX-NEW-999` ke paas nahi!).
3. **Result:** Vikram ke phone par woh `NX1:...` packet **decrypt hi nahi hoga**—Vikram ka app usko silently drop/reject kar dega! Aapka private message stranger ke haath bilkul nahi lagega.



---

### Scenario 5: Purane User Ke Pending Offline Messages (Redis Dead-Drop Mailbox) 📬🗑️

* **Situation:** Kisi ne Rahul (`NX-OLD-111`) ko internet par 5 messages bheje the jab woh offline tha (jo Redis RAM mailbox mein pade the), aur isi beech Vikram ne number reclaim kar liya.
* **System Action:**
* Redis mailbox **`NX-ID` (`NX-OLD-111`)** ke naam par bana hota hai, phone number ke naam par nahi!
* Isliye Vikram (`NX-NEW-999`) ko woh 5 pending messages **kabhi nahi milenge**. Jab Rahul apna naya number link karke `NX-OLD-111` wapas active karega, tabhi woh messages Rahul ko milenge (ya 7 din mein expire ho jayenge).



---

### Scenario 6: Group Chats Mein Purane Number Ka Kya Hoga? 👥🛡️

* **Situation:** Rahul (`NX-OLD-111`) 15 groups ka member tha. Number recycle hokar Vikram (`NX-NEW-999`) ke paas chala gaya.
* **System Action:**
* Kyunki Group membership **`NX-ID`** se judi hoti hai (phone number se nahi), isliye Vikram kisi bhi group mein auto-add **nahi** hoga.
* Groups mein Rahul (`NX-OLD-111`) hi member rahega (jab tak woh naya number link na kare ya Admin use remove na kare).



---

### Ek Nazar Mein: Yeh 6 Scenarios Kaise Handle Hote Hain? 🌟

1. **Har Account Ki Asli Pehchan `NX-ID` + `Google Account` Hai** (Phone number sirf ek attachable/detachable link hai).
2. **Naye user ko hamesha nayi `NX-ID` milti hai**, isliye purani chats, groups, ya pending messages kabhi leak nahi hote.
3. **E2EE Keys hardware chip mein lock hoti hain**, isliye agar offline SMS galti se naye user ke phone par chala bhi jaye, toh bhi woh decrypt nahi ho sakta!


Sudesh, recycled numbers aur number-change ke aas-paas **5 aur tricky real-world scenarios** hain jo aksar apps ko tod dete hain (especially Dual-SIM, number swap, lost SIM, ya harassment prevention mein). Har ek scenario aur NEXA ka exact solution samjhein:

---

### Scenario 7: Purana User Bina "Change Number" Kiye Naya Number Le Le (Lost/Blocked SIM Case) 📵🆘

* **Situation:** Rahul ka phone/SIM chori ho gaya, aur usne purani SIM block karwake ek bilkul naya number le liya (usko purani SIM se OTP nahi mil sakta).
* **System Action (Google Master Identity Rescue):**
1. Rahul naye phone mein NEXA install karke apna wahi **Google Account (`google_email_hash`)** sign-in karta hai.
2. Server pehchan leta hai ki yeh `NX-RAHUL-111` hai, lekin purani SIM active nahi hai.
3. Rahul **"Lost Old Number / Link New Number"** option choose karta hai $\rightarrow$ Naye number par Truecaller/OTP verify karta hai $\rightarrow$ Server 24-hour security cooldown (ya Trusted Contacts verification) ke saath purane number ko unlink karke naya number `NX-RAHUL-111` se jod deta hai!
4. **Fayda:** Rahul ki `NX-ID`, groups, aur Google Drive encrypted backup bach jata hai, aur purana chori hua number kisi hacker ke kaam nahi aata.



---

### Scenario 8: Purana User (Rahul) Wapas Apna Purana Number Reclaim Kar Le (2-3 Din Baad) 🔁

* **Situation:** Rahul ki SIM 90 din band rehne se cancel ho gayi thi, lekin telecom store jakar Rahul ne documents dekar **wohi purana number dubara issue** karwa liya (kisi aur ko allot hone se pehle).
* **System Action:**
* Jab Rahul wapas usi number ko verify karta hai, toh backend dekhta hai ki `google_email_hash` aur `NX-ID` (`NX-RAHUL-111`) dono same hain!
* Server bina kisi chat split ya key reset ke seedha `NX-RAHUL-111` ko wapas active kar deta hai—doston ko koi warning banner bhi nahi dikhega.



---

### Scenario 9: Do Users Aapas Mein Numbers Swap Kar Lein (Family / Husband-Wife SIM Exchange) 🔄👨‍👩‍👧

* **Situation:** Ghar mein husband ne apni SIM wife ko de di aur wife ne apni SIM husband ko de di—ab dono apne-apne NEXA accounts mein number update karna chahte hain.
* **System Action (Deadlock Prevention):**
* Agar dono seedha "Change Number" karenge, toh `UNIQUE (phone_hash)` constraint ki wajah se error aa sakta hai ("Number already in use").
* **Solution:** Jab user "Change Number" par kisi already-registered number ko dalta hai, toh server check karta hai ki kya us number ka physical SIM abhi isi phone mein laga hai (`SubscriptionManager` + OTP).
* Physical SIM verify hote hi server purane account se number detach karke naye account par attach kar deta hai—bina kisi database deadlock ke!



---

### Scenario 10: Naya User (Priya) Recycled Number Lene Ke Baad Apna Number Hide Rakhna Chahti Hai 🔒👩

* **Situation:** Priya ko ek recycled number mila hai, aur usse darr hai ki purane maalik ke doston ke paas yeh number pehle se save hoga, isliye woh sabko apni contact list mein dikhne lagengi.
* **System Action (Default Privacy Shield for Recycled/New Numbers):**
* Jab bhi koi naya account banta hai, Onboarding ke waqt hi ek crisp privacy toggle dikhega:
> **"Discovery by Phone Number:"** `[ Everyone with my number ]` vs **`[ Only People I Save (Recommended) ]`**


* Agar Priya **`Only People I Save`** rakhti hai, toh purane maalik ke hazaron doston ke phonebook mein uska number save hone ke bawajood **kisi ko bhi Priya ka NEXA account, profile photo, ya status nahi dikhega**!



---

### Scenario 11: Purane Dost Ke Saath Shared Groups Mein "Ghost Member" Ka Kya Hoga? 👥👻

* **Situation:** Rahul (`NX-RAHUL-111`) ne SIM chhod di, naya number nahi liya, aur uska purana number Priya (`NX-PRIYA-999`) ko mil gaya. Ab Group mein kaun dikhega?
* **System Action:**
* Group mein **`NX-RAHUL-111`** hi dikhega (Priya kabhi group mein nahi dikhegi).
* Agar Rahul 60–90 din tak apni `NX-RAHUL-111` par koi naya number link nahi karta (account dormant rehta hai), toh group mein uske naam ke aage chota sa badge aa jayega: **`Inactive Account`** (taaki group members ko pata rahe ki yeh account ab active SIM se juda nahi hai).



---

### Ek Nazar Mein: Number Lifecycle Ka Complete Shield 🛡️

1. **Lost SIM:** Google Master Identity se naya number link karke `NX-ID` recover ho jayegi.
2. **SIM Swap:** Physical SIM verification (`SubscriptionManager`) se purane account ka lock cleanly hat kar naye account par lag jayega.
3. **Zero Harassment:** `Only People I Save` discovery filter se recycled number lene wale naye user ko purane contacts kabhi dhundh hi nahi payenge!

Ab **Login, SIM-Binding, Internet E2EE, Encrypted SMS, Contacts Graph, aur Recycled Number Protection** har ek scenario ke saath 100% airtight lock ho chuke hain! Ab agla module **Offline Bluetooth / Wi-Fi Direct Mesh Network** shuru karein ya **P2P File & Media Transfer (WebRTC + R2)**? 🚀