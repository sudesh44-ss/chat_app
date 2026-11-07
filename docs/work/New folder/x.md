Audit report ke findings ke according NEXA ka actual problem ab kaafi clear ho gaya hai. Google Login se zyada badi problem Reverse SMS verification infrastructure mein hai. Iske alawa WebSocket identity mismatch aur backend persistence ke bhi critical issues hain.

NEXA Production Readiness

Not production ready

# 3 critical blockers

1. Reverse SMS verification loop disconnected.
2. WebSocket identity public-key mismatch.
3. Backend identity storage is not persistent.

   Audit report ke code inspection findings par based. Runtime integration tests ko separately verify karna baaki hai.

## 1. Sabse pehle: Phone verification fail kyon ho rahi hai?

Audit mein iska specific root cause mila hai.

Current Reverse SMS flow

1. Android app

   User verification screen par pahunchta hai aur SMS send karta hai.
2. Incorrect destination

   Backend dummy number `+919800000000` return kar raha hai.

   Confirmed configuration issue
3. Telecom gateway

   SMS kisi functional receiving number par nahi pahunchta. Isliye inbound webhook trigger nahi hota.
4. Webhook authentication

   `GATEWAY_WEBHOOK_SECRET` missing hai. Request aane par bhi HMAC verification fail-closed karegi.
5. Android polling

   App 90 seconds tak `PENDING` status check karti hai.
6. Final result

   `Verification Expired` aur user Chat List tak nahi pahunch pata.

   Onboarding blocked

Source: Phone verification root-cause findings.&#x20;

Pasted text(20261106-134600).txt



Important: Sirf Render mein `INBOUND_SMS_RECEIVER_NUMBER` set kar dena sufficient nahi hai.

Aapko actual telecom receiving infrastructure bhi chahiye:

- Functional receiving phone number ya virtual number.
- SMS receive karne wala gateway/provider.
- Incoming SMS ko backend webhook tak forward karne ki capability.
- Provider-supported webhook authentication.
- Correct webhook URL aur payload parser.

Yaani Reverse SMS ka complete telecom loop establish karna hoga.

## 2. Baaki errors ka actual reason

| Issue            | Audit finding                                                                | Priority                 |
| ---------------- | ---------------------------------------------------------------------------- | ------------------------ |
| Redis            | Backend `REDIS_URL` read nahi karta; `REDIS_ADDR` se localhost use karta hai | P0                       |
| SMS Provider     | Legacy OTP service unavailable provider initialize kar rahi hai              | P0 configuration cleanup |
| FCM              | Project ID missing aur OAuth2 access-token exchange implemented nahi hai     | P1                       |
| PostgreSQL       | Go backend mein SQL integration nahi mili                                    | P0 persistence           |
| Identity storage | JSON file par dependent                                                      | P0                       |
| WebSocket        | Ed25519 public-key derivation mismatch                                       | P0                       |
| Chat List        | Local Room database aur empty state implemented                              | UI readiness positive    |

Sources:&#x20;

Pasted text(20261106-134600).txt

Pasted text(20261106-134600).txt



## 3. Ek aur serious problem: WebSocket identity mismatch

Ye issue phone verification fix karne ke baad saamne aayega.

Audit ke according:

- Onboarding key derivation SHA-256 bit-clamping use kar rahi hai.
- WebSocket identity true Ed25519 point multiplication use kar rahi hai.
- Dono public keys match nahi kar rahi hain.
- Backend registered identity ke saath comparison karta hai aur connection close kar deta hai.

Result: User Chat List screen tak pahunch bhi gaya toh Relay Disconnected ho sakta hai.&#x20;

Pasted text(20261106-134600).txt



Important architectural distinction: P-256 device-authentication key aur Ed25519 messaging identity ko ek hi key samajhkar replace mat karna. Dono ke purposes alag ho sakte hain. Agent ko key ownership, registration aur derivation alignment fix karna hai, existing E2EE compatibility break nahi karni.

## 4. PostgreSQL wala issue ignore nahi karna

Audit ke according:

- Go backend mein PostgreSQL driver/integration nahi hai.
- 16 SQL migrations backend execute nahi karta.
- Account, device aur session information `identity_store.json` mein store hoti hai.
- Render filesystem persistence guaranteed nahi hai.

Pasted text(20261106-134600).txt

Ek temporary solution Render Persistent Disk ho sakta hai, lekin meri recommendation PostgreSQL migration hai, kyunki aap Phase 2 mein already transactional persistence architecture plan kar rahe hain.

## 5. Ab implementation kis order mein honi chahiye?

Meri recommended sequence:

Phase A — P0

Make onboarding work

- Actual Reverse SMS receiving number.
- Telecom gateway integration.
- HMAC webhook secret.
- End-to-end verification test.
- Redis URL/TLS support.

Phase B — P0

Fix identity and connectivity

- Onboarding/WebSocket Ed25519 key alignment.
- Registered public-key validation.
- Device authentication regression tests.

Phase C — P0

Persistent backend

- PostgreSQL integration.
- Existing JSON identity migration.
- Transactional account/device/session storage.
- Redis challenge storage.

Phase D — P1

Production notifications

- FCM service-account authentication.
- Device token registration.
- Actual push delivery tests.

Final validation

Complete onboarding-to-chat test

- Fresh installation.
- Google login.
- Phone verification.
- Device registration.
- Session persistence.
- Chat List.
- WebSocket connection.

## 6. Ek important correction audit report mein

Report ke environment matrix mein `GOOGLE_CLIENT_ID` ke expected value ke roop mein `3com` diya gaya hai. Ye abbreviated value lagti hai. Isse literal production value mat samajhna. Android ke Web Client ID aur backend audience ka exact match verify karna zaroori hai. Audit Google verification ko functional batata hai, lekin ise actual production login test se confirm karna hoga.&#x20;

Pasted text(20261106-134600).txt



Isi tarah, FCM ko initial Chat List access ke liye mandatory nahi banana chahiye. Aapka new user empty Chat List dekh sake, bhale hi FCM abhi configure na hua ho.

## 7. Ab agent ko kya karna chahiye?

Audit reports generate ho chuki hain. Ab ek saath sab implement karne ke bajay Phase A: Reverse SMS + Redis + onboarding unblock se shuru karna best rahega.





Meri ek zaroori baat: Is waqt sabse pehle actual SMS receiving gateway identify karna hai. Aapke paas koi functional receiving number aur telecom provider/gateway already hai, ya abhi sirf NEXA backend aur Android client hi configured hain?