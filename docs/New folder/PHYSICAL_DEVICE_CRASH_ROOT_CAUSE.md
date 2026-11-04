# NEXA — Physical Device Startup Crash: Root Cause Analysis

## 1. Physical Device & Environment Identification

The startup crash was reproduced and diagnosed live on the physically connected Android hardware via ADB:

| Parameter | Value |
|---|---|
| **Device Model** | V2315 (vivo Y28 5G) |
| **Manufacturer** | vivo |
| **Brand / Product** | vivo / V2315T |
| **Android OS Version** | Android 15 (VanillaIceCream) |
| **API Level** | 35 |
| **Build Fingerprint** | `vivo/1901_in/2012:15/AP3A.240905.015.A2/compiler10151909:user/release-keys` |
| **CPU ABI** | `arm64-v8a` |
| **Kernel Page Size** | `4096` bytes (4 KB) |
| **Package ID** | `com.nexa.app` |
| **Target SDK** | 35 |
| **Min SDK** | 26 |

---

## 2. Exact Crash Classification

- **Exception Type**: `java.lang.RuntimeException: Unable to create application com.nexa.app.NexaApplication`
- **Underlying Cause**: `java.lang.IllegalArgumentException: Cannot set backoff criteria on an idle mode job`
- **Origin**: Framework / AndroidX WorkManager `PeriodicWorkRequest.Builder.buildInternal$work_runtime_release`
- **Execution Thread**: Main UI Thread (`FATAL EXCEPTION: main`, PID: 17595)
- **Lifecycle Phase**: `Application.onCreate()` (before `MainActivity.onCreate()` or any Jetpack Compose frame rendering).

---

## 3. Full Verbatim Logcat Stack Trace

Captured directly from the physical vivo Y28 5G device logcat stream:

```text
11-04 13:01:22.071 17595 17595 E AndroidRuntime: FATAL EXCEPTION: main
11-04 13:01:22.071 17595 17595 E AndroidRuntime: Process: com.nexa.app, PID: 17595
11-04 13:01:22.071 17595 17595 E AndroidRuntime: java.lang.RuntimeException: Unable to create application com.nexa.app.NexaApplication: java.lang.IllegalArgumentException: Cannot set backoff criteria on an idle mode job
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.app.ActivityThread.handleBindApplication(ActivityThread.java:8609)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.app.ActivityThread.-$$Nest$mhandleBindApplication(Unknown Source:0)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:2614)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.os.Handler.dispatchMessage(Handler.java:107)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.os.Looper.loopOnce(Looper.java:247)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.os.Looper.loop(Looper.java:369)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.app.ActivityThread.main(ActivityThread.java:9952)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at java.lang.reflect.Method.invoke(Native Method)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:627)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1049)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: Caused by: java.lang.IllegalArgumentException: Cannot set backoff criteria on an idle mode job
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at androidx.work.PeriodicWorkRequest$Builder.buildInternal$work_runtime_release(PeriodicWorkRequest.kt:332)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at androidx.work.WorkRequest$Builder.build(WorkRequest.kt:269)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at com.nexa.app.data.worker.ScheduledBackupWorker$Companion.scheduleNightlyBackup(ScheduledBackupWorker.kt:64)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at com.nexa.app.NexaApplication.onCreate(NexaApplication.kt:376)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.app.Instrumentation.callApplicationOnCreate(Instrumentation.java:1341)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	at android.app.ActivityThread.handleBindApplication(ActivityThread.java:8603)
11-04 13:01:22.071 17595 17595 E AndroidRuntime: 	... 9 more
```

---

## 4. Origin of the Crash

### A. Location in Source Code
- **File**: `app/src/main/java/com/nexa/app/data/worker/ScheduledBackupWorker.kt`
- **Class**: `com.nexa.app.data.worker.ScheduledBackupWorker.Companion`
- **Method**: `scheduleNightlyBackup(context: Context)`
- **Faulty Code (Lines 55–64)**:
  ```kotlin
  val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.UNMETERED)
      .setRequiresCharging(true)
      .setRequiresDeviceIdle(true) // <--- Demands device idle mode
      .build()

  val periodicRequest = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(24, TimeUnit.HOURS)
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES) // <--- FORBIDDEN on idle jobs
      .build() // <--- THROWS java.lang.IllegalArgumentException
  ```

### B. Invocation Trace
- **File**: `app/src/main/java/com/nexa/app/NexaApplication.kt`
- **Method**: `onCreate()`
- **Line 376**:
  ```kotlin
  if (backupPrefs.autoBackupEnabled) {
      com.nexa.app.data.worker.ScheduledBackupWorker.scheduleNightlyBackup(this)
  }
  ```

---

## 5. Architectural Deep-Dive: Why it Occurred

### A. Android JobScheduler & WorkManager Constraint Incompatibility
Android's underlying system daemon `JobScheduler` treats `requiresDeviceIdle(true)` jobs with strict semantics: they may only execute while the device is in an inactive idle state (screen off, user not interacting, Doze maintenance windows).

If a custom backoff retry policy (such as `EXPONENTIAL, 15, TimeUnit.MINUTES`) is requested, the scheduler would be mandated to trigger a retry 15 minutes after a failure regardless of whether the user has picked up their phone. Because these two requirements are fundamentally contradictory under Android power-management architecture, `androidx.work.PeriodicWorkRequest.Builder.buildInternal()` enforces an explicit assertion:
```kotlin
if (workSpec.constraints.requiresDeviceIdle() && 
    (workSpec.backoffPolicy != BackoffPolicy.EXPONENTIAL || 
     workSpec.backoffDelayDuration != WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS)) {
    throw IllegalArgumentException("Cannot set backoff criteria on an idle mode job")
}
```
Attempting to build such a `PeriodicWorkRequest` throws an immediate, synchronous `IllegalArgumentException`.

### B. Why Unit Tests Did Not Catch It
1. **Robolectric / Mocked WorkManager**: Standard unit test configurations in Android CI mock `WorkManager` or do not invoke `NexaApplication.onCreate()` end-to-end.
2. **Preference State**: In test fixtures, `backupPrefs.autoBackupEnabled` defaults to false unless explicitly mocked. On the physical device where user preferences had persisted with auto-backup enabled, the conditional block was evaluated to true immediately upon application process creation.
3. **Absence of UI Initialization**: Because the exception was thrown in `Application.onCreate()`, `ActivityThread.handleBindApplication` trapped the exception and terminated the process before `MainActivity` was launched and before Jetpack Compose could compose the first frame.

### C. Myth vs. Reality: SQLCipher & 16 KB Page-Size
- **Myth**: The crash was caused by `libsqlcipher.so` ELF LOAD segment alignment or `SupportOpenHelperFactory` initialization failure.
- **Fact**: Live ADB inspection proved that SQLCipher 4.6.1 is 16 KB aligned (`p_align = 0x4000`), loaded without error (`ok`), and the database key was accepted with status code `0` (`SQLiteConnection: Database keying operation returned: 0`).
- The kernel on the vivo Y28 5G is running with `PAGE_SIZE = 4096` (4 KB), meaning 16 KB page-size enforcement was not active on this kernel.
- The startup crash was 100% caused by the WorkManager illegal argument exception in `NexaApplication.onCreate()`.

---

## 6. Summary of Resolution Strategy
1. Remove `.setBackoffCriteria(...)` from `ScheduledBackupWorker.Companion.scheduleNightlyBackup()`, allowing default WorkManager backoff handling for idle-mode periodic jobs.
2. Wrap background worker scheduling calls (`ScheduledBackupWorker`, `RequestSyncWorker`, `RuleSyncWorker`) in defensive `try-catch` blocks within `NexaApplication.onCreate()` so that any non-critical background scheduling failure logs a warning rather than killing the main UI process.
3. Deploy update via `adb install -r` without wiping app storage or data, preserving all encrypted SQLCipher databases, Keystore master keys, Double Ratchet sessions, and credentials.
