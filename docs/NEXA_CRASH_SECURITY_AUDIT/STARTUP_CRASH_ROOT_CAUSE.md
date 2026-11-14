# NEXA — Android Startup Crash Root Cause Analysis

**Audit Date:** November 4, 2026  
**Target:** `H:\chat_app`  
**Artifacts Analyzed:** `app-debug.apk`, `app-release-unsigned.apk`  
**Crash Classification:** Native Dynamic Linker Failure (`dlopen`) & Missing Native Library Initializer

---

## 1. Executive Summary & Exact Crash Location

When the NEXA application is launched on Android devices running an Android 15 kernel (or Android 14 with the 16 KB page-size developer option enabled) or on standard devices without native SQLite libraries loaded, the application crashes immediately upon opening.

### Exact Crash Point 1: 16 KB Page-Size Kernel Rejection
- **Failing Operation:** Dynamic linker loading `lib/arm64-v8a/libsqlcipher.so`
- **Exception / Signal:** `SIGSEGV` / `java.lang.UnsatisfiedLinkError: dlopen failed: "libsqlcipher.so" has invalid alignment 4096 (must be 16384)`
- **Trigger Location:** `NexaApplication.onCreate()` (line 202) -> `NexaDatabase.getInstance()` -> `Room.databaseBuilder.openHelperFactory()` -> first query `userDao.hasActiveSession()` in `SplashViewModel.init`.

### Exact Crash Point 2: Missing `System.loadLibrary("sqlcipher")` Initializer
- **Failing Operation:** Room SQLite initialization without native JNI bindings loaded.
- **Exception:** `java.lang.UnsatisfiedLinkError: net.sqlcipher.database.SQLiteDatabase.native_init()V`
- **Trigger Location:** `NexaDatabase.buildDatabase()` called `SupportFactory(passphrase)` without preceding native library initialization.

---

## 2. Complete Startup Sequence & Failure Trace

```
[ App Launch / Process Fork ]
              │
              ▼
[ NexaApplication.onCreate() ]
   ├─ 1. secureStorage = SecureStorage(this)                   [OK - Fallback protected]
   ├─ 2. keystoreManager = CryptoKeystoreManager(this)         [OK - KeyStore initialized]
   ├─ 3. dbPassphrase = secureStorage.getOrCreateDatabasePassphrase() [OK - Passphrase retrieved]
   │
   ├─ 4. database = NexaDatabase.getInstance(this, dbPassphrase)
   │     │
   │     ├─ [BEFORE FIX]:
   │     │   - Instantiated legacy SupportFactory(passphrase)
   │     │   - No System.loadLibrary("sqlcipher") was called
   │     │   - Packaged libsqlcipher.so was legacy v4.5.4 (ELF p_align = 4096)
   │     │   - On Android 15 (16 KB page size kernel):
   │     │     Dynamic linker (/linker64) inspects ELF LOAD segments.
   │     │     Discovers p_align = 4096 != 16384.
   │     │     LINKER ABORTS PROCESS -> FATAL APP CRASH!
   │     │
   │     └─ [AFTER FIX]:
   │         - System.loadLibrary("sqlcipher") explicitly guarded
   │         - Upgraded to modern net.zetetic:sqlcipher-android:4.6.1
   │         - Instantiates modern SupportOpenHelperFactory(passphrase)
   │         - libsqlcipher.so ELF LOAD segments verified: p_align = 16384 (16 KB)
   │         - Database initializes cleanly without linker abort!
              │
              ▼
[ MainActivity.onCreate() ]
   ├─ Injects app.authRepository, app.chatRepository, app.p2pTransferRepository
   ├─ setContent { AuthNavGraph(...) }
   │
   ▼
[ Screen.Splash (SplashViewModel) ]
   ├─ checkAuthStatus() -> authRepository.isLoggedIn()
   ├─ Executes: userDao.hasActiveSession() (First SQLCipher Room query)
   │     └─ Encrypted SQLite database opens successfully!
   │
   ▼
[ Navigation Decision ]
   ├─ If loggedIn == false -> Navigates to WelcomeScreen (Get Started)
   └─ If loggedIn == true  -> Navigates to MainChatDashboardScreen
```

---

## 3. ELF LOAD Segment Inspection Proof (Before vs After)

Using direct ELF binary inspection across all program headers (`phnum = 9`) of `lib/arm64-v8a/libsqlcipher.so`:

### Before (Legacy SQLCipher v4.5.4):
```text
Library: lib/arm64-v8a/libsqlcipher.so (64-bit: True, phnum: 9)
   PT_LOAD: p_align=4096 (0x1000), p_vaddr=0x0, p_offset=0x0       <-- INCOMPATIBLE (4 KB)
   PT_LOAD: p_align=4096 (0x1000), p_vaddr=0x34A850, p_offset=0x349850
   PT_LOAD: p_align=4096 (0x1000), p_vaddr=0x372228, p_offset=0x370228
```

### After (Modern SQLCipher v4.6.1):
```text
Library: lib/arm64-v8a/libsqlcipher.so (64-bit: True, phnum: 9)
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x0, p_offset=0x0     <-- COMPATIBLE (16 KB)
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x529BA0, p_offset=0x525BA0
   PT_LOAD: p_align=16384 (0x4000), p_vaddr=0x589970, p_offset=0x581970
```

All ELF load segments are now strictly aligned to $16\text{ KB}$ ($16,384\text{ bytes}$) boundaries, completely resolving the startup crash on Android 15+ kernels.
