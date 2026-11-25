Existing User Login hone par user ko sirf wahi screens aur details dikhni chahiye jo uski identity confirm karein aur security lock set karein—bina dobara Name, Age, ya Gender bharwaye!

Screen-by-screen exact kya-kya show hoga, yeh sequence dekhein:

---

### Screen 1: Welcome Screen (Login Entry) 👋

* **NEXA Logo & Tagline**
* Ek hi primary button: **`Continue with Google`** (ya **`Sign in with Existing Account`**)
* User tap karke apna purana registered Gmail select karega.

---

### Screen 2: Account Found & Profile Preview Card (Identity Confirmation) 🆔

Jaise hi Google token backend par match hota hai (aur server dekhta hai ki `NX-7F82K9A4` pehle se bana hai), user ko ek clean **Glassmorphic Card** par uski purani details show hongi:

* **Profile Avatar & Saved Name:** e.g., *Sudesh*
* **Permanent NEXA ID:** `NX-7F82K9A4`
* **Masked Registered Mobile:** `+91 98XXXXXX12`
* **Message:** *"Welcome back! Verify your registered SIM to unlock your account on this device."*
* **Action Button:** **`Verify & Continue`** (Saath mein neeche chota link: *"Lost access to this number? Update Number"* — emergency recovery ke liye).

---

### Screen 3: Registered Number & SIM Verification (2-Second Check) 📱🔐

* **Prompt:** *"Ensure your SIM (+91 98XXXXXX12) is inserted in this phone."*
* **1st Priority Popup:** **Truecaller 1-Tap Verification** card (`+91 98XXXXXX12` auto-detected).
* **Fallback Screen:** Agar Truecaller nahi hai, toh **6-Digit OTP Input** screen (Firebase Auth se).
* Verify hote hi background mein Android `SubscriptionManager` is phone ke SIM slot ko lock kar lega.

---

### Screen 4: Device Switch Warning (Sirf Tab Dikhega Jab Purana Phone Active Tha) ⚠️📲

Agar aapka account kisi purane phone par abhi bhi logged-in hai, toh ek **Security Alert Modal** show hoga:

* **Title:** *"Active Session Detected"*
* **Details:** *"Your account is currently active on **Redmi Note 12** (Last active: Today, 4:10 PM). Continuing will immediately log out that device."*
* **Buttons:** **`Log Out Old Device & Continue`** | **`Cancel`**

---

### Screen 5: Encrypted Chat Backup Restore (Sirf Tab Dikhega Jab Google Drive Backup Mile) ☁️📦

App user ke Google Drive (`AppData` folder) ko check karegi:

* **Agar Backup Milta Hai:**
* **Title:** *"Restore Your Encrypted Chats"*
* **Details:** *"Backup found: 12 Nov 2026 • 45 MB (End-to-End Encrypted)"*
* **Input (Agar user ne backup password/recovery key lagayi thi):** *"Enter your Backup Passphrase / 12-Word Key"*
* **Buttons:** **`Restore Chats`** | **`Skip (Start Fresh)`**


* **Agar Backup Nahi Hai (ya Off tha):** Yeh screen automatically skip ho jayegi!

---

### Screen 6: Permissions & Direct Home Screen! 🏠✅

* Agar naya phone hai toh ek baar **Contacts & Notifications Permission** card dikhega (`Allow` / `Not Now`).
* **Direct NEXA Home Screen:**
* Aapki saari purani **Connections / Contacts list** aur **Groups** pehle se synced milenge.
* Agar backup restore kiya hai toh saari purani chats bhi wapas aa jayengi; warna fresh E2EE chat start ho jayegi!
* Top ya Profile mein **`🟢 SIM Verified • Keystore Protected`** active dikhega.



---

### Ek Nazar Mein: Login Par Kya NAHI Show Hoga 🚫

* Name, Age, Gender ka form **nahi** dikhega (kyunki server par pehle se saved hai).
* Nayi `NX-ID` generate **nahi** hogi (wahi purani `NX-7F82K9A4` wapas milegi).