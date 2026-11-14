# Gradle Resource Archive Corruption Report

Date: 2026-11-12

## Summary and root cause

The failing `:app:packageDebug` invocation reported an invalid generated
resource archive: the central-directory filename length for
`res/anim/abc_fade_in.xml` was 2 bytes while its local-file-header filename
length was 24 bytes. This ZIP metadata inconsistency is the immediate cause of
the packaging failure.

The archive that produced the error was no longer available in its failing
state when investigated: by the time it was inspected, the header lengths
already agreed. A forced resource-processing run reproduced a valid archive,
and a full no-cache rebuild after clearing only the affected intermediate
completed successfully. This supports a transiently corrupted generated
intermediate, not a malformed source resource or dependency artifact.

The historical trigger for the transient corruption cannot be established from
the retained evidence. In particular, the failed archive bytes were not
preserved, so it is not possible to distinguish a transient/interrupted write
from an external concurrent writer. No historical concurrent build was
observed during this investigation. No persistent Gradle/AGP incompatibility or
filesystem/antivirus cause was demonstrated. No build-tool or dependency
versions were changed.

## Environment and compatibility audit

| Component | Observed configuration |
|---|---|
| Gradle wrapper | Gradle 9.6.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin Gradle Plugin / Compose plugin | 2.2.10 |
| KSP | 2.2.10-2.0.2 |
| Gradle launcher JVM | Eclipse Temurin 17.0.20.1 |
| Gradle daemon JVM | Android Studio JBR 25.0.4 |
| Android SDK | `C:\Users\suryw\AppData\Local\Android\Sdk` |
| Android platform | `android-37.0`; `compileSdk = 37` |
| Installed Build Tools | 36.0.0; project does not pin `buildToolsVersion` |
| AAPT2 | AGP-provided `aapt2-9.4.1-15978811-windows` |

The AGP/Gradle/JDK combination completed a forced debug packaging build. The
build emitted a non-fatal Kotlin warning: Kotlin 2.2.10 does not yet support a
JDK 25 target and falls back to JVM target 24. This warning is unrelated to the
resource ZIP error and was not changed as part of this fix.

`gradle.properties` disables configuration cache, Kotlin incremental
compilation, and parallel execution. It does not enable the Gradle build cache;
verification commands also explicitly used `--no-build-cache`. The app has no
resource-packing overrides; its existing packaging configuration only sets
`jniLibs.useLegacyPackaging = false`.

Relevant resource tasks include `packageDebugResources`,
`mergeDebugResources`, and `processDebugResources`. The final forced build
executed `processDebugResources` and `packageDebug`, rather than treating them
as up-to-date.

## Resource and dependency findings

- `res/anim/abc_fade_in.xml` is provided by
  `androidx.appcompat:appcompat:1.6.1`.
- The dependency is selected through Coil 2.7.0; CameraX requests AppCompat
  1.1.0, which Gradle resolves to 1.6.1. The dependency insight report showed
  normal conflict resolution, not inconsistent selected versions.
- The cached 1.6.1 AAR contains the source resource (925 bytes). The generated
  `.ap_` contains exactly one compiled entry with that name; the source AAR was
  not modified.
- No duplicate-resource error occurred during `mergeDebugResources` or the
  final build. The archive and APK each contain one entry with this name.

## Fix applied

Stopped the idle Gradle daemons and removed only the generated directory
`app/build/intermediates/linked_resources_binary_format/debug/processDebugResources`.
No source files, dependency caches, or project configuration were changed.
Then reran resource processing and debug packaging with task reruns and the
build cache disabled.

## Commands and results

Commands executed:

```text
.\gradlew.bat --version
.\gradlew.bat --status
.\gradlew.bat :app:processDebugResources --rerun-tasks --no-build-cache --info
.\gradlew.bat :app:packageDebug --no-build-cache --info
.\gradlew.bat :app:dependencyInsight --dependency androidx.appcompat --configuration debugRuntimeClasspath
.\gradlew.bat buildEnvironment
.\gradlew.bat --stop
.\gradlew.bat :app:packageDebug --rerun-tasks --no-build-cache
```

Final result: `BUILD SUCCESSFUL in 7m 53s`. The forced run executed both
`:app:processDebugResources` and `:app:packageDebug`.

The regenerated linked-resource archive was fully opened and all entries were
read. It contains 491 entries; the `abc_fade_in.xml` central-directory and
local-file-header name lengths both measure 24 bytes. The generated debug APK
was also fully opened and all entries were read (695 entries). No ZIP read
errors or resource-processing errors remained.

The H: drive had approximately 142.8 GB free at inspection. No filesystem
errors were reported by the build. Antivirus involvement was not established;
Windows security settings were not changed. No separate test task was run
because only generated outputs were regenerated and the forced debug build
successfully recompiled and packaged the variant.

## Files changed

- `GRADLE_RESOURCE_ARCHIVE_CORRUPTION_REPORT.md` — added as requested.
- Generated files under `app/build/` were regenerated; these are ignored build
  outputs.
- No Gradle configuration, dependency declaration, resource source, or
  application source was changed.
