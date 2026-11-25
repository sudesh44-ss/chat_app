# **NEXA – Module 1: Final Authentication & Onboarding Architecture 🔐**

Is poore document ko combine karke NEXA ka login, registration, phone verification, device security, SIM verification aur NX-ID system ek final architecture mein samajhte hain.

Iska main objective hai:

1. Har user ka ek permanent NEXA account ho.
2. Google account se user ki master identity establish ho.
3. Phone number verify ho.
4. Ek phone number se multiple NX-ID na ban sakein.
5. Account ek samay par sirf ek active device par chale.
6. Messages aur private data server par store na hon.
7. Naya phone lene par secure account migration ho.
8. Authentication ka unnecessary recurring cost na ho.

## **1. Final onboarding flow**

## **NEXA Registration Journey**

1. **Splash Screen**

   App launch, existing session aur onboarding status check.
2. **Google Sign-In**

   Android Credential Manager se Google account select. Supabase Auth account authenticate karega.
3. **Phone Number Verification**

   Primary: Truecaller verification. Fallback: Firebase Phone Authentication OTP.
4. **Device Verification**

   Android Keystore key generation, Play Integrity attestation aur server-side device registration.
5. **SIM Verification / Binding**

   Available Android SIM information aur verification capabilities ke hisaab se device association.
6. **Profile Details**

   Display Name, Age aur optional Gender. Name pre-filled ho sakta hai, lekin user edit kar sakega.
7. **NX-ID Generation**

   Go Backend unique, permanent NX-ID generate aur database mein register karega.
8. **Final Profile Ready**

   Local encrypted database initialize, identity setup complete.
9. **Home Screen**

   Authenticated session aur Go Relay WebSocket connection establish.

**Important:** Google Login aur Phone Verification do alag authentication layers hain. Google account verify hone ka matlab phone number verify hona nahi hai.

## **2. Kaunsa technology kis kaam ke liye hoga?**

| Technology                | Final responsibility                                   |
| ------------------------- | ------------------------------------------------------ |
| Google Credential Manager | Google sign-in                                         |
| Supabase Auth             | Master account authentication                          |
| Truecaller SDK            | Primary phone verification                             |
| Firebase Phone Auth       | OTP fallback                                           |
| Android Keystore          | Private cryptographic keys                             |
| Play Integrity API        | App/device integrity checks                            |
| Go Backend                | Authentication orchestration, NX-ID, device management |
| PostgreSQL                | Account and device records                             |
| Redis                     | Temporary challenges, sessions and online presence     |
| SQLCipher + Room          | Encrypted local application database                   |
| Firebase Cloud Messaging  | Offline device wake-up notifications                   |

## **3. Database mein kya save hoga?**

**A. Supabase PostgreSQL – Account Directory**

- `user_uuid`
- `nx_id`
- `google_email_hash`
- `phone_hash`
- `phone_encrypted`
- `phone_verified`
- `display_name`
- `birth_year` or age eligibility status
- `gender` (optional)
- `public_identity_key`
- `account_status`

  **B. PostgreSQL – User Devices**
- `device_id`
- `user_uuid`
- `device_public_key`
- `device_model`
- `sim_binding_status`
- `is_active`
- `last_seen_at`

  **C. Redis – Temporary Information**
- Login challenges
- Verification nonces
- Active session information
- Online/offline presence

  **D. User's Phone – Local Storage**
- Private cryptographic keys (Keystore)
- Encrypted chat history
- Contacts
- Local profile settings
- Message attachments and metadata
- Local SIM-binding information, where supported

**Core privacy rule:** Chat messages, private keys aur message decryption keys Go Relay ya Supabase par plaintext mein store nahi honge.

## **4. Phone number verification – final decision**

Selected architecture

[Sonic Branding Agency — Audio Identity for Global Brands](https://images.openai.com/static-rsc-4/vNZBRyA3FGdqCIqa2iQeRE5e8rWm9_b7pEphCQ6xpzPkGmG4bPa4jpQvHQBjD-Ua-3-MCSrXOdL9rpi7UIwl5VDLoTijn6EEGVi9QHl4YB6quKO-nVlCVMM6aK0YRFlio67uepFlnVpJEKIpAKOZM9f7KpWs-5FzKJeOmvNqUAE?purpose=inline)

**Primary: Truecaller OAuth SDK**

Existing Truecaller users

Primary

- User taps Continue.
- Truecaller provides an authorization response.
- Go Backend verifies the response.
- Verified mobile number is linked to the NEXA account.

[Firebase color icon in PNG, SVG](https://images.openai.com/static-rsc-4/MScrU19a-6I0F9eIY3ff1kxhMI_Tqi3DFdtN_dAbwVeHoQxGal7bodxHIdsCFKPLrPuqNhWUkHnL_NqraUSAdx9qq4lENsG8hNPWK3Me1HkXeo6aVX32P5AsLsol0hpEoSNSwtNY5fgB411ph-L7PJWi6gdW5F-YADYNHO_aNZE?purpose=inline)

**Fallback: Firebase Phone Auth**

Users who cannot use Truecaller

Fallback

- User enters mobile number.
- Firebase sends OTP.
- User verifies OTP.
- Go Backend verifies the Firebase ID Token.
- Verified number is linked to NEXA.

Truecaller officially documents its SDK as free with no verification limits for its supported one-tap flow. Its non-Truecaller missed-call verification has separate permission requirements.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://docs.truecaller.com\&sz=32)

Truecaller for Developers+1



**Google Phone Number Hint API:** Isko optional convenience feature rakhenge, authentication method nahi. Yeh number suggest karta hai, number ownership ka cryptographic proof nahi deta.

**Firebase cost clarification:** Firebase Phone Auth SMS ko universally free 10,000/month assume nahi karna hai. Current pricing SMS sent ke basis par hai. Isliye actual billing estimate launch se pehle verify karenge.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://firebase.google.com\&sz=32)

Firebase



## **5. One Number = One NX-ID policy**

Yeh NEXA ka permanent account rule hoga.

Example:

**First account**

`rahul1@gmail.com`

`+91 98765 43210`

# **NX-7F82K9A4**

Registered

Same phone number, different Gmail

**Second registration attempt**

`rahul2@gmail.com`

`+91 98765 43210`

Registration blocked

Number already belongs to an existing NX-ID.

Backend rules:

- `phone_hash` par UNIQUE constraint.
- Number verification ke baad hi account binding.
- Duplicate registration par new NX-ID generate nahi hogi.
- User ko masked account hint diya ja sakta hai.
- Existing account recovery ka option milega.

Ek important security improvement: Phone numbers ka simple SHA-256 hash use karne ke bajaye server-secret HMAC use karna better hoga. Phone numbers ka search space chhota hota hai, isliye plain hashing se dictionary attacks ka risk rehta hai.

**Number recycling:** Agar telecom operator purana number kisi naye customer ko de deta hai, toh automatic 60–90 day inactivity ke baad number transfer nahi hoga. Iske liye separate recovery, ownership re-verification aur account protection process chahiye.

## **6. Device Verification – account ko phone se bind karna**

Yeh phone number verification se alag security layer hai.

**Android Keystore**

- Device-specific cryptographic key pair generate.
- Private key device ke andar protected rahegi.
- Public key Go Backend ko register hogi.

**Google Play Integrity**

- App authenticity check.
- Device integrity check.
- Server-side nonce verification.
- Modified app aur suspicious environments ke liye risk assessment.

**Proof of Possession**

- Go Backend random challenge bhejega.
- Phone apni private key se sign karega.
- Backend public key se signature verify karega.
- Successful verification par device active register hoga.

Play Integrity genuine device aur recognized application ke signals deta hai, lekin har rooted device ko 100% detect karne ki guarantee nahi deta. Isko tiered security system ke roop mein use karna chahiye.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://developer.android.com\&sz=32)

Android Developers+2



**Technical correction:** StrongBox har phone mein available nahi hota aur Ed25519 hardware support bhi universally guaranteed nahi hai. Key algorithm device compatibility ke hisaab se select karna hoga. Hardware-backed key available na ho toh defined fallback policy honi chahiye.

## **7. SIM Binding – important technical correction**

Document mein ICCID hash ko har app launch par check karne ka idea hai. Isko exactly waise implement nahi kar sakte.

Android 10 onward ordinary third-party apps ko ICCID access restricted hai. Subscription ID available ho sakta hai, lekin woh globally permanent physical SIM identifier nahi hai.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://developer.android.com\&sz=32)

Android Developers+1



Isliye final approach:

- SIM presence aur available subscription information check karna.
- Supported information ko device association ke saath use karna.
- SIM change detect hone par risk assessment karna.
- Sensitive account actions ke liye re-verification maangna.
- Network unavailable hone par legitimate offline functionality block nahi karna.
- SIM information unavailable ho toh user ko galat tareeke se locked out nahi karna.

**SIM removal = automatic permanent account lock** ka rule nahi rakhenge.

SIM binding ko additional security signal rakhenge, account ownership ka sole proof nahi.

## **8. Naya phone lene par kya hoga?**

NEXA mein ek samay par ek hi active phone allowed hoga.

## **Device Migration Flow**

**Old Device**

Device 1 – Active

Active

New phone authentication

**New Device**

Google + Phone verification + Device verification

Verification

**Backend Migration**

1. Existing account identify.
2. New device verify.
3. Old device approval or secure recovery.
4. New device activate.
5. Old device session revoke.
6. Old WebSocket connection disconnect.

**New Device**

Device 2 – Active

Active

Old device becomes revoked. Its locally stored encrypted data is not automatically deleted.

### **Important: Backup and recovery**

Naya phone activate hone ka matlab purane chats automatically transfer hona nahi hai.

Iske liye separately:

- Google Drive encrypted backup.
- Backup encryption key recovery.
- Old device approval, where available.
- Secure recovery when old device is unavailable.
- Backup restore on new phone.

Yeh NEXA ke local-first architecture ke saath compatible rahega.

## **9. Millions of users ke liye scaling**

Architecture ka distribution:

**Android Client**

Kotlin + Jetpack Compose + Room + SQLCipher

**Go Backend**

Authentication + Device Management + Relay

**Supabase**

PostgreSQL account directory

**Redis**

Temporary state and presence

Scaling ke liye:

- PostgreSQL indexes and connection pooling.
- Atomic unique constraints for NX-ID and phone binding.
- Redis TTL for temporary data.
- Rate limiting for registration and login.
- Backend horizontal scaling.
- Database backups and recovery.
- Monitoring, alerts and abuse detection.

**Cost ke baare mein:** 5 million users ka database 1.5 GB hi hoga ya Supabase ka $25 plan sufficient hoga, aisa abhi guarantee nahi kar sakte. Storage ke alawa database connections, queries, backups, network traffic aur compute capacity bhi matter karte hain.

## **10. Final decisions – kya lock hoga aur kya pending rahega?**

## **NEXA Module 1 – Decision Register**

Architecture selected

**Final technology choices**

- Google Credential Manager
- Supabase Auth
- Truecaller OAuth – primary verification
- Firebase Phone Auth – fallback
- Go Backend
- PostgreSQL
- Redis
- Android Keystore
- Play Integrity API
- Room + SQLCipher
- FCM
- Unique NX-ID
- One Number = One NX-ID
- Single Active Device\

  **Implementation decisions requiring validation**
- Supported hardware cryptographic algorithms.
- Android SIM information and binding limitations.
- Truecaller integration and Play Store permissions.
- Firebase OTP actual regional pricing.
- Secure device migration and recovery.
- Age eligibility and privacy requirements.
- Production database capacity and cost.

## **11. Meri final recommendation**

NEXA ke liye authentication ko 5 independent security layers mein rakhenge:

| Layer                    | Purpose                             |
| ------------------------ | ----------------------------------- |
| 1. Google                | Account identity                    |
| 2. Truecaller / Firebase | Phone ownership verification        |
| 3. Android Keystore      | Device cryptographic identity       |
| 4. Play Integrity        | App/device trust assessment         |
| 5. Backend authorization | Account, device and session control |

In sabka kaam alag hoga. Ek layer fail hone par doosri layer ka security purpose replace nahi karegi.

Final architecture recommendation

**Module 1 ka naam:** Authentication, Identity & Device Security.

Iske andar registration, login, phone verification, NX-ID, duplicate prevention, device binding, SIM risk detection aur account recovery aayenge.

**Ek important distinction:** Yeh architecture design decision hai, production-tested implementation ya legal compliance certification nahi. Development se pehle listed technical limitations validate karni hongi.

Agla module hoga **Module 2 – Connection Graph, Contacts Discovery, Connection Requests & Privacy Arrows.**

Is tarah hum NEXA ko module-by-module design karenge, bina unnecessary features aur overlapping responsibilities ke.



**Module 1 abhi 100% lock nahi hua hai.** Login, identity, device verification aur Connected Devices ka overall design define ho chuka hai, lekin aapke naye SIM-removal flow mein kuch important technical corrections zaroori hain.

Sabse important correction: **Android par normal third-party app har device mein SIM ka ICCID/serial number read nahi kar sakti.** Isliye `sim_iccid_hash` ko 100% reliable fingerprint maan kar automatic unlock design karna safe nahi hai.

## **1. SIM removal flow mein kya change hoga?**

| Aapka proposed behaviour                      | Technical reality / recommended design                                                                                    |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| SIM nikalte hi 1 second mein guaranteed event | SIM-state change detect ho sakta hai, lekin exact 1-second guarantee nahi deni chahiye.                                   |
| ICCID hash se original SIM match              | Ordinary apps ko ICCID access generally restricted hai.                                                                   |
| SIM nikalte hi full-screen lock               | SIM removal ko security signal rakhein; ise account compromise ka proof na maanein.                                       |
| Wahi SIM daalte hi automatic unlock           | Reliable ICCID verification ke bina guarantee nahi ki ja sakti.                                                           |
| Doosri SIM par OTP se number update           | Number change ke liye verified recovery/change-number flow rakhein; SIM change ko account ownership transfer na samjhein. |

**Recommended NEXA behaviour:**

1. Android SIM/subscription state change detect kare.
2. App local security state update kare: `SIM_STATE_CHANGED` / `SIM_ABSENT`.
3. App network par dependent na rahe; local chats ko automatically inaccessible na banaye.
4. User ki configured security policy ke hisaab se sensitive actions ke liye device credential/biometric verification maangi ja sakti hai.
5. SIM wapas aane par state re-check ho. Agar reliable identity verification available nahi hai, toh automatic unlock ka claim na ho.
6. Airplane Mode ya network signal loss ko SIM removal na samjha jaye.

Ek aur important point: **SIM removal par poori chat history lock karna attacker ko simple denial-of-service ka option de sakta hai.** Local-first NEXA mein local data ki security SIM ke bajaye Android Keystore, app authentication aur encrypted database se aani chahiye.

## **2. Connected Devices: kya sahi hai aur kya update hoga?**

Is section ka concept sahi hai. Lekin data ko **live device state**, **server session state** aur **security history** mein divide karna chahiye.

| UI field             | Data kahan se aayega                | Important correction                                                  |
| -------------------- | ----------------------------------- | --------------------------------------------------------------------- |
| Device model         | Android device info                 | Local se read; server par last registered value                       |
| Android version      | Android OS                          | App/backend par last reported version                                 |
| Active Now           | Go backend session + heartbeat      | Sirf `is_active = true` se live status prove nahi hota                |
| SIM status           | Android local telephony state       | Backend ka real-time SIM fingerprint nahi                             |
| Phone number         | Verified account metadata           | Masked form mein show hoga                                            |
| Hardware security    | Keystore key properties/attestation | StrongBox tabhi show ho jab actual key StrongBox-backed verify hui ho |
| Login / revoked date | Backend audit records               | Timestamp server-side maintain hoga                                   |
| Approximate city     | IP-based geolocation, if enabled    | GPS nahi; approximate aur potentially inaccurate                      |

### **Recommended screen structure**

**Connected Devices**

**This Device**

Primary

**Vivo — Device Model**

Android version • Current device

Session connected

Phone verification

**Verified •••• 1212**

SIM state

**Present / Not verified**

Key protection

**Hardware-backed / TEE / Software**

Last authenticated

**13 Nov 2026**

**Previous Device Sessions**

**Previous Android Device**

Revoked • 10 Nov 2026, 4:15 PM

Revoked

Illustrative layout — actual values device aur server verification par depend karenge.

**Backend structure mein ek improvement:** `user_devices` ko current device registry ke liye rakhein aur `device_session_events` ya `device_login_history` ko login, re-authentication, revocation aur security events ki history ke liye. Isse ek device ki purani sessions aur revocation history overwrite nahi hogi.

Hardware badge ke liye Android Keystore attestation use ki ja sakti hai. Android documentation hardware security level ko `Software`, `TrustedEnvironment` aur `StrongBox` mein distinguish karti hai. Isliye har phone par StrongBox badge dikhana galat hoga.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://developer.android.com\&sz=32)

Android Developers+1



## **3. Android SIM detection ki actual limitation**

Android documentation mein `OnSubscriptionsChangedListener` active subscriptions mein changes notify karta hai. Lekin modern `ACTION_SIM_CARD_STATE_CHANGED` protected/System API hai aur uske liye privileged permission required hai. Isliye normal NEXA app ko har Android phone par physical SIM tray removal ka guaranteed event milne ka assumption nahi rakhna chahiye.&#x20;

[image](https://www.google.com/s2/favicons?domain=https://developer.android.com\&sz=32)

Android Developers+1



Iska practical meaning:

- SIM removal detection ko best-effort security signal rakhein.
- `SIM_ABSENT`, `SIM_NOT_READY`, `SIM_IO_ERROR` aur `UNKNOWN` ko alag states samjhein.
- Dual SIM aur eSIM ko support karein.
- Airplane Mode/no signal ko SIM removal se alag rakhein.
- App restart ya process kill ke baad bhi current state dobara evaluate karein.
- SIM state change se Go Relay ka connection automatically pause karna mandatory na banayein. Session ko device-key authentication aur server-side revocation se control karein.

## **4. Module 1 ka final lock status**

Architecture defined — technical validation pending

| Area                              | Status                                     |
| --------------------------------- | ------------------------------------------ |
| Google Authentication             | Defined                                    |
| Phone Number Verification         | Defined                                    |
| NX-ID Identity                    | Defined                                    |
| Device Key & Verification         | Defined                                    |
| Single Active Device              | Defined                                    |
| SIM Binding                       | Requires revised design                    |
| SIM Removal Response              | Best-effort detection only                 |
| Device Migration & Recovery       | Defined, implementation validation pending |
| Connected Devices UI              | Defined                                    |
| Device Login / Revocation History | Separate audit records required            |
| Keystore / StrongBox              | Capability-based verification required     |
| Backend & Local Data Separation   | Defined                                    |

**Module 1 ko architecture level par freeze kar sakte hain**, lekin implementation complete hone aur real Android devices par testing ke baad hi ise fully validated bolenge.

### **Final decisions jo lock hone chahiye**

1. SIM ICCID ko mandatory device identity nahi banayenge.
2. SIM removal se local chat history automatically lock nahi hogi.
3. SIM status ek additional security signal hoga, primary authentication factor nahi.
4. Account security device-specific cryptographic keys par based hogi.
5. Connected Devices mein server session status aur local SIM status alag show honge.
6. StrongBox/TEE status actual verification ke according show hoga.
7. Old device revocation history separately maintain hogi.
8. Number change ke liye secure re-verification aur recovery process mandatory hoga.

## **5. Ab Module 2 shuru karein**

Next module

# **Module 2 — Connection Graph & Privacy Arrows**

Is module mein hum define karenge:

1. NX-ID se user discovery
2. Contacts matching aur privacy
3. Connection Requests
4. Incoming / Outgoing Requests
5. Connection Graph
6. Privacy Arrows — kaun kisko discover ya contact kar sakta hai
7. Block, Remove Connection aur Report
8. Nearby Discovery integration
9. Local database vs Supabase vs Go Backend responsibilities
10. Abuse prevention, rate limits aur privacy protections

**Haan, ab Module 2 par chalte hain.** Module 1 ke upar diye gaye corrections ko final architecture decisions mein include karna hoga; purane ICCID-based automatic lock/unlock flow ko final specification mein nahi rakhna chahiye.