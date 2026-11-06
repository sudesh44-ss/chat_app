NEXA Security Audit Lab — Complete Active Testing Plan

Authorized testing scope
 Existing project: No changes

NEXA ke liye hum ek dedicated Security Audit Lab establish karenge. Iska purpose attacker ke perspective se Android application, installed APK, Go backend, APIs, WebSocket, Supabase database, Cloudflare R2 aur E2EE implementation ki security test karna hai.

Sabse important condition: Testing active hogi, lekin existing NEXA project ko modify nahi kiya jayega. Attack simulation sirf isolated test environment, test accounts aur disposable test data par chalegi.
1. Testing architecture
NEXA Security Audit Lab
Security Testing Machine

Windows 11 + WSL2 / Linux + Docker


Android Lab
MobSF · JADX · Frida · ADB

Code Lab
Semgrep · gosec · Trivy · Gitleaks

API Lab
ZAP · Schemathesis · Burp

Database Lab
RLS tests · pgTAP



Isolated NEXA Staging Environment

Test Android App · Test Go Backend · Test Supabase · Test R2


Security Findings & Evidence

Vulnerabilities · Severity · Reproduction · Recommendations


Production NEXA server aur real users is testing pipeline ka target nahi honge.
2. Tools to install
Maine tools ko priority ke hisaab se arrange kiya hai.
Android application testing









1. MobSF
Must have
APK static and dynamic security analysis, manifest, permissions, cryptography and network configuration.

GitHub · Documentation











2. Frida
Must have
Installed app runtime inspection, method hooking and security-control verification.

GitHub · Documentation











3. JADX
APK decompilation and inspection of app logic, API endpoints and accidentally exposed secrets.
GitHub


Backend and code testing
Tool	Purpose	Repository
Semgrep	Kotlin + Go source scanning	GitHub
gosec	Go-specific security checks	GitHub
govulncheck	Reachable Go dependency vulnerabilities	GitHub
Trivy	Dependencies, Docker images and configuration	GitHub
Gitleaks	Git secrets and credentials	GitHub

Active attack simulation
Tool	Purpose	Repository
OWASP ZAP	API and HTTP security testing	GitHub
Schemathesis	API fuzz testing from OpenAPI	GitHub
Burp Community	Manual HTTP request inspection	Download
Nuclei	Template-based vulnerability checks	GitHub

Database and encryption testing
- pgTAP — PostgreSQL database testing.
- Supabase RLS documentation — User isolation and authorization.
- OWASP MASTG — Android security testing methodology.
- OWASP MASVS — Mobile security requirements.
3. Aapke computer aur Android device ke hisaab se setup
Aapke previously shared system configuration ke according:
- Windows 11
- Intel Core i3-9100
- 12 GB RAM
- No dedicated GPU
- Android 15 physical device: vivo Y28 5G
Is hardware par poora security lab ek saath run karna ideal nahi hoga. MobSF, Docker, Android Studio aur emulator ek saath RAM consume kar sakte hain.

Recommended setup:
- Windows 11 + WSL2 Ubuntu.
- Docker Desktop with WSL2 integration.
- Android Studio ka existing SDK aur ADB.
- MobSF container ko zaroorat ke waqt run karein.
- Ek Android emulator ya dedicated test device.
- Existing vivo phone ko primary device hi rakhein; bina zaroorat root na karein.
Step 1 — WSL2 aur Docker
PowerShell ko Administrator mode mein open karke:
wsl --install -d Ubuntu


Restart ke baad Docker Desktop install karein:
Docker Desktop downloadDocker Desktop settings mein:
- WSL 2 engine enable karein.
- Ubuntu distribution ke liye WSL integration enable karein.
Verify:
wsl --version
docker --version
docker compose version


Docker ka use primarily MobSF aur isolated test infrastructure run karne ke liye hoga. Supabase local stack bhi Docker-compatible container runtime maangta hai. 

Supabase Docs+1

Step 2 — Source-code scanning tools
Ubuntu terminal mein:
sudo apt update
sudo apt install -y git curl unzip python3 python3-pip pipx


Semgrep:
pipx install semgrep
semgrep --version


Go tools:
go install github.com/securego/gosec/v2/cmd/gosec@latest

go install golang.org/x/vuln/cmd/govulncheck@latest


Gitleaks aur Trivy ko unke official installation instructions se install karein:
- Gitleaks installation
- Trivy installation
Verify:
semgrep --version
gosec -version
govulncheck -version
gitleaks version
trivy --version


Important: In tools ko existing repository ke andar auto-fix mode mein mat chalana. Reports ko repository se bahar ek separate audit folder mein save karna.
Step 3 — MobSF installation
PowerShell ya WSL terminal mein:
docker pull opensecurity/mobile-security-framework-mobsf:latest


Run:
docker run -d \
  --name nexa-mobsf \
  -p 127.0.0.1:8000:8000 \
  opensecurity/mobile-security-framework-mobsf:latest


Open:

http://127.0.0.1:8000

Default login documentation mein mobsf / mobsf diya hai. First login par ise change karein. 

GitHub+1

MobSF mein kya upload karna hai?
- NEXA ka release APK.
- Debug APK ko separately, if needed.
- APK ki static analysis report.
Do not upload secrets
APK analysis se pehle ensure karein ki test build mein production credentials, private keys ya real user data embedded na ho.

Step 4 — JADX aur Frida
JADX GUI:
JADX releasesAPK ko open karke inspect karein:
- Hardcoded URLs.
- API keys and credentials.
- Authentication logic.
- Local database handling.
- Debug code.
- Cryptographic implementation references.
Frida Python tools:
pipx install frida-tools
frida --version


Android runtime testing ke liye ADB:
adb devices
adb shell getprop ro.product.cpu.abilist


Frida server ke traditional approach ko rooted device ki zaroorat hoti hai. Stock Android par Frida Gadget/repackaging route bhi hai. MobSF ka recent dynamic-analysis approach non-rooted devices ke liye bhi support provide karta hai, lekin Gadget mode app ki signature change karta hai. Isliye original installed app ko overwrite na karein; separate test build/package use karein. 

A world-class dynamic instrumentation toolkit+1

4. Active testing ke liye environment preparation
Yeh sabse important stage hai. Active attacks ko production se isolate karna hoga.
Separate test infrastructure
Component	Testing environment
Android app	Separate test APK
Go backend	Separate staging service
Supabase	Separate test project
PostgreSQL	Disposable test database
Cloudflare R2	Separate test bucket
FCM	Test Firebase configuration
Users	Two or more test accounts
Messages	Dummy encrypted messages

Aapke existing Render backend nexa-relay.onrender.com ko production maan kar direct attack testing mat karein.

Pehle uska separate staging instance banayein. Staging environment ko production database, R2 bucket aur credentials se completely separate rakhein.

Production credentials ko testing tools mein enter nahi karna hai.
5. Step-by-step active security testing
Recommended testing sequence
Phase 1 — Source Code Security Audit
Static testing
Sabse pehle Go aur Kotlin code inspect hoga.
Test	Tool
Insecure coding patterns	Semgrep
Go security issues	gosec
Go dependency vulnerabilities	govulncheck
Secret leakage	Gitleaks
Docker/dependency misconfiguration	Trivy

Suggested commands:
semgrep scan --config auto /path/to/nexa-copy

gosec -fmt text ./...

govulncheck ./...

gitleaks git --no-banner


Commands ko clean audit copy mein run karein. Gitleaks ka Git-history scan bhi karein, kyunki current files clean hone ka matlab ye nahi ki purane commits mein secrets nahi hain.

Check karna hai:
- Hardcoded secrets.
- Weak authentication checks.
- Insecure cryptographic usage.
- SQL injection.
- Unsafe file handling.
- Race conditions.
- Dependency vulnerabilities.
- Debug endpoints.
- Insecure configuration.
Phase 2 — Android APK Attack Testing
Static + dynamic testing
MobSF aur JADX se APK inspect karenge.

Test scenarios:
1. APK decompile karke API endpoints aur embedded secrets identify karna.
2. AndroidManifest permissions inspect karna.
3. Backup configuration check karna.
4. Local database encryption verify karna.
5. Keystore key protection inspect karna.
6. Authentication token storage inspect karna.
7. Certificate validation aur network security check karna.
8. Debuggable/release configuration inspect karna.
9. Logs mein sensitive data exposure check karna.
10. Exported Android components aur deep links inspect karna.
Installed application attack scenarios:
- Logged-out user protected screens access kar sakta hai?
- Session expire hone ke baad purana token work karta hai?
- Logout ke baad old session invalidate hota hai?
- App process restart ke baad sensitive information expose hoti hai?
- Local database copy karne par plaintext messages milte hain?
- Root/debugging detection controls correctly behave karte hain?
Frida se runtime methods aur sensitive data flow inspect karenge. OWASP MASTG ke test cases ko reference banayenge. 

OWASP Foundation+1

Phase 3 — Authentication Attack Testing
NEXA ka Google OAuth + Supabase Auth flow specifically test karna hoga.
Attack scenario	Expected secure behaviour
Invalid Google ID Token	Authentication rejected
Expired Supabase JWT	Protected request rejected
Modified JWT	Rejected
Reused revoked session	Rejected
OTP brute force	Attempt limit enforced
OTP replay	Rejected
Expired OTP	Rejected
Unauthorized NX-ID binding	Rejected
Old device session	Revocation policy enforced
Account switching	Previous account data inaccessible

Special attention:
- Google ID Token ko Supabase JWT samajhkar accept nahi hona chahiye.
- Supabase JWT ko E2EE identity proof nahi samajhna chahiye.
- OTP success aur Google authentication success alag verification stages hain.
- Device identity aur account identity ka binding secure hona chahiye.
Phase 4 — API Penetration Testing
Active testing
Install:
OWASP ZAP downloadAPI documentation available ho toh OpenAPI specification se Schemathesis use karein.

Test categories:
- Broken authentication.
- Broken object-level authorization (BOLA/IDOR).
- SQL injection.
- Improper input validation.
- Excessive data exposure.
- Rate-limit bypass.
- HTTP method misuse.
- Session expiration.
- Insecure error responses.
Two-account testing is mandatory.

Example:
- Account A ka test NX-ID.
- Account B ka test NX-ID.
- Account A se Account B ke protected records access karne ki koshish.
- Expected result: server authorization reject kare.
ZAP mein pehle passive analysis, phir isolated staging par controlled active scanning karein. Active scan real requests bhejta hai aur test data create/update kar sakta hai.
Phase 5 — WebSocket Attack Testing
NEXA ke messaging architecture mein WebSocket security bahut important hai.

Test cases:
1. Bina authentication WebSocket connection.
2. Expired token ke saath connection.
3. Modified authentication token.
4. Kisi doosre user ke identity ke naam par message send karna.
5. Unauthorized recipient ko message relay karna.
6. Replayed messages.
7. Duplicate delivery acknowledgements.
8. Oversized message payload.
9. Invalid packet structure.
10. Excessive connection attempts.
11. Reconnect ke baad old session reuse.
12. Multiple devices ke session isolation tests.
Expected result:
- Unauthorized connections reject hon.
- Sender identity server independently verify kare.
- Client-provided sender ID par blindly trust na ho.
- Message recipient authorization enforce ho.
- Payload size limits enforce hon.
- Rate limits effective hon.
- Server encrypted payload ko plaintext mein log na kare.
Phase 6 — Supabase / PostgreSQL Attack Testing
Database security
Is phase mein RLS sabse important hai.

Use:
- Supabase test project.
- pgTAP.
- Separate authenticated test users.
- Disposable test records.
Supabase ke current testing documentation mein RLS policies ko anonymous aur authenticated roles, CRUD operations aur negative cases ke through test karne ki recommendation hai. 

Supabase Docs+1

Test cases:
Test	Expected result
Anonymous profile read	Policy ke according denied
User A reads User B private data	Denied
User A updates User B profile	Denied
User A deletes User B record	Denied
Authenticated user accesses admin data	Denied
Invalid NX-ID lookup	No unauthorized disclosure
Public prekey lookup	Only intended public information
Private key access	Never available from database API

Har test mein positive aur negative dono cases run karein.

RLS policy file mein present hona enough nahi hai; actual JWT context mein behavior verify karna hai.
Phase 7 — Cloudflare R2 Attack Testing
Separate test bucket ke against:
- Unauthorized upload.
- Unauthorized download.
- Expired pre-signed URL.
- Reused URL.
- URL modification.
- File-size bypass.
- Content-type manipulation.
- Incorrect BLAKE3 hash.
- Cross-user file access.
- File deletion authorization.
- Temporary media expiry.
Expected result:
- R2 master credentials client mein expose nahi hon.
- Pre-signed URLs limited permissions aur expiry ke saath hon.
- User doosre user ki files access nahi kar sake.
- Hash verification fail hone par file accept na ho, agar hash validation required hai.
Phase 8 — E2EE / Cryptographic Security
Yeh NEXA ka sabse sensitive audit section hai.

Generic vulnerability scanner X3DH aur Double Ratchet ki correctness prove nahi kar sakta.

Isliye dedicated protocol testing karna hoga:
Area	Security verification
X25519	Key agreement correctness
Ed25519	Identity/signature verification
X3DH	Initial session establishment
Double Ratchet	Message key evolution
Replay protection	Old message rejection
Forward secrecy	Previous keys compromise ke baad protection
Device replacement	Old identity/session revocation
Backup	Private key recovery protection
Message tampering	Authentication failure
Multi-device	Independent device identity

Use established protocol test vectors, independent cryptographic review aur adversarial integration tests.

Critical: Custom encryption ko production security-approved declare nahi karna chahiye sirf isliye ki AES-256 ya X25519 use ho raha hai.
Phase 9 — Infrastructure and Deployment Testing
Trivy aur Nuclei se staging environment inspect karein.

Check:
- Exposed ports.
- Public Redis/PostgreSQL access.
- TLS configuration.
- Security headers.
- Docker image vulnerabilities.
- Unnecessary debug endpoints.
- Environment-variable exposure.
- Server error leakage.
- Dependency CVEs.
- Deployment configuration weaknesses.
Nuclei ko initially low request rate aur narrowly scoped target ke saath chalayein. Iske request-rate controls available hain. 

GitHub+1


6. Testing schedule
Aapke computer ki resource limitations ko dhyan mein rakhte hue ye practical schedule rahega.
Day	Work
1	Security lab setup + staging isolation
2	Go/Kotlin source code scanning
3	APK static analysis
4	Installed app runtime testing
5	Google OAuth + OTP attack tests
6	REST API penetration testing
7	WebSocket attack testing
8	Supabase RLS testing
9	R2 storage security testing
10	E2EE and key-management review
11	Infrastructure and deployment checks
12	Final verification and report

Yeh estimated schedule hai, guaranteed completion timeline nahi. E2EE aur WebSocket testing mein additional time lag sakta hai.
7. Security findings ko kaise classify karna hai?
Har finding ke saath evidence aur reproducibility honi chahiye.
Severity	Example
Critical	Authentication bypass, private key compromise
High	Cross-user data access, privilege escalation
Medium	Rate-limit bypass, sensitive metadata leakage
Low	Minor information disclosure
Informational	Hardening recommendation

Har finding mein ye information ho:
- Vulnerability ID.
- Component affected.
- Severity.
- Affected file/API.
- Attack prerequisites.
- Reproduction steps.
- Expected versus actual behavior.
- Evidence or sanitized request/response.
- Impact.
- Recommended remediation.
- Confidence level.
8. Final testing report
Audit ke end mein ye deliverables hone chahiye:
1. Executive Security Summary.
2. Android APK Security Report.
3. Installed App Runtime Report.
4. Go Backend Security Report.
5. REST API Penetration Report.
6. WebSocket Security Report.
7. Supabase RLS Report.
8. Cloudflare R2 Security Report.
9. E2EE Cryptographic Review.
10. Infrastructure and Docker Security Report.
11. Vulnerability Severity Matrix.
12. False Positive / Unverified Findings.
13. Overall Security Assessment.
Report aur test artifacts repository se bahar store karein. Test accounts, test data aur staging resources ko clearly label karein.

9. Testing se pehle ye 4 cheezein confirm karni hongi
Aapka existing NEXA project untouched rahega. Setup aur testing ke liye separate copies aur separate infrastructure use honge.
1. Separate staging Go backend

Already available

Abhi banana padega


2. NEXA release APK

APK available hai

Abhi generate karna padega


3. Android testing device

vivo Y28 5G physical device

Android Studio Emulator

Dono available hain


4. Separate Supabase testing project

Already available

Abhi banana padega

Submit testing setup
Meri final recommendation: Pehle staging environment aur MobSF + source scanners set up karein. Uske baad API, WebSocket aur database ke active tests start karein. E2EE ko alag specialist-level review dein. Is tarah NEXA ko attacker ke perspective se systematically test kar sakte hain, bina existing application ya production users ko risk mein daale.


Haan, NEXA Security Testing Lab ke liye VirtualBox use karna bahut achha approach hoga. Isse aap apne Windows 11 system aur original NEXA development environment ko alag rakh sakte hain.

Meri recommendation hai ki hum VirtualBox ke andar ek dedicated Kali Linux Security Testing Machine banayein.

## 1. Proposed setup

Windows 11 — Host Machine

Android Studio · NEXA Source Code · Existing Development Environment

VirtualBox

Kali Linux — Security Lab VM

Isolated

Android Testing

MobSF · JADX · Frida

Code Testing

Semgrep · gosec · Trivy

Network Testing

ZAP · Burp · Nuclei

Database Testing

PostgreSQL · pgTAP

Separate NEXA Staging Environment

Test Backend · Test Database · Test R2 · Test Accounts

## 2. VirtualBox mein kaunsa OS install karein?

[Kali Linux 2025.2 Released with Revamped Kali Menu, 13 New Hacking Tools - 9to5Linux](https://images.openai.com/static-rsc-4/xwGH7q0kTCWP8ORHvyP9v2WcaclKjWaj0oYFhZVPlUEYXWbnv_gEuZ-zJ121CROd76UmctTXee1V7uSYlvhalmPMEUAc5dvSI8_PTPyUtRCNOpxCsPSa2qra7pIIDdhzVo6X2z5ulhpmiah4dcoGqbUY9sU4Bw0AU_nUPwnnXeg?purpose=inline)

Kali Linux

Recommended

Security testing ke liye specialized Linux distribution. Ismein penetration-testing tools ka ecosystem available hai.

[Official Download](https://www.kali.org/get-kali/?utm_source=chatgpt.com)

[대학생이 리눅스 우분투를 2년간 써보면서 (Ubuntu 22.04) — 쓰기 전에 생각하자](https://images.openai.com/static-rsc-4/bF59MORMZ4qphvtglvWGYgBa5ni09ANAlMmB55QUd-o9yFozSeAyRZhIul-Yhtc7SobhfLUU-4MCQriFsymzoz_2dAFJUCIsw9UYvm3azBeLxrfJ1rODldsoPSSI2YcYF7uQIgpvcbzob4CNfV8QIOZBpxwtKnhhXzgklJ4o9pM?purpose=inline)

Ubuntu\
Alternative: agar aapko lightweight environment mein custom tools install karne hain.

[Official Download](https://ubuntu.com/download/desktop?utm_source=chatgpt.com)

Mere hisaab se Kali Linux better starting point hai, lekin har security tool Kali mein preinstalled nahi hota.

## 3. Aapke PC ke hisaab se VM configuration

Aapke Intel i3-9100, 12 GB RAM aur no-GPU system ko dhyan mein rakhkar:

| Setting        | Recommended           |
| -------------- | --------------------- |
| Virtualization | Intel VT-x enabled    |
| OS             | Kali Linux XFCE       |
| RAM            | 4 GB                  |
| CPU            | 2 cores               |
| Virtual Disk   | 50–60 GB              |
| Disk Type      | Dynamically allocated |
| Network        | NAT initially         |
| Display        | 128 MB video memory   |

12 GB RAM mein Windows aur Kali dono chalenge, lekin Android Studio, MobSF aur heavy scans ek saath mat chalaiye. MobSF ko host machine par Docker mein chalana ya VM ke andar separately run karna resources ke hisaab se decide karein.

Download: [Oracle VirtualBox](https://www.virtualbox.org/wiki/Downloads?utm_source=chatgpt.com).

## 4. VM use karne ke major benefits

1. Isolation: Testing tools aur unke dependencies Windows development environment se separate rahenge.
2. Snapshots: Kisi testing setup mein problem aaye toh previous VM state restore kar sakte hain.
3. Safe experimentation: Disposable test environment mein suspicious files aur testing tools inspect kar sakte hain.
4. Repeatability: Ek configured VM ko future audits ke liye reuse kar sakte hain.
5. Original project protection: Host par NEXA development setup ko untouched rakh sakte hain.

Lekin ek important distinction: VM host se complete security boundary nahi hai. Shared folders, clipboard, USB passthrough aur bridged networking ke through interaction possible hai. Isliye initially shared folders/clipboard disabled aur NAT networking rakhein.

## 5. NEXA testing ka workflow

| Stage | Work                                        |
| ----- | ------------------------------------------- |
| 1     | VirtualBox + Kali setup                     |
| 2     | Security tools installation                 |
| 3     | NEXA source code ki read-only copy transfer |
| 4     | APK static analysis                         |
| 5     | Go/Kotlin source scanning                   |
| 6     | Separate staging backend testing            |
| 7     | API and WebSocket active testing            |
| 8     | Supabase RLS and R2 testing                 |
| 9     | E2EE security review                        |
| 10    | Consolidated vulnerability report           |

Note: Active testing ke liye sirf VM sufficient nahi hai. NEXA ke liye separate staging backend, test Supabase project aur test R2 bucket bhi chahiye. NAT isolation production services ko automatically safe nahi banata.

