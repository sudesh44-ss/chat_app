# File Security Gate Implementation Report

## 1. Executive summary

NEXA now treats files received from peers as untrusted until the receiving device independently classifies and scans them. Incoming files are written to app-private quarantine; passing a scan does not itself make a file available through the safe-vault FileProvider. Release is explicit, authorization is checked again by the repository, and the bytes are verified before promotion.

## 2. Objectives

- Make receiver-side inspection authoritative; sender-reported scan results are informational only.
- Keep received and inconclusive files isolated from normal file-sharing surfaces.
- Require a classification-appropriate authorization before releasing a quarantined file.
- Avoid silent acceptance when scanning, hashing, or format inspection cannot complete.

## 3. Security model

The receiver applies a zero-trust model to transferred bytes. Hash verification establishes integrity against the expected transfer hash, not benignness. Classification and scanning determine the local scan outcome, while the user-controlled release authorization is a separate decision. A sender's status cannot substitute for either receiver step.

## 4. Threat boundaries

The gate addresses untrusted peer-provided content, extension spoofing, known executable signatures, malformed or unsupported formats, interrupted scans, and changes between scan and release. It does not claim antivirus-grade detection or protection against every malicious document, exploit, or parser flaw.

## 5. File classification

`FileReleaseClassifier` distinguishes normal supported files, APKs, executables, and unsupported formats. It considers file extensions and content signatures, including APK/JAR container structure. Unsupported formats remain restricted rather than being treated as clean.

## 6. Receiver scanning

`FileSecurityManager` and `DualScanEngine` perform receiver-side integrity and security checks. The dual-scan inbound path verifies the expected SHA-256 hash, applies file classification, checks supported archive/package structure, and invokes the existing YARA scan. Dangerous or mismatched content is rejected; unsupported or incomplete inspection is inconclusive.

## 7. APK and archive inspection

APK handling validates bounded ZIP structure, the Android manifest entry, and configured entry/resource constraints before relying on the existing signature checks. Ordinary ZIP files use the archive scanner. The APK implementation does not unpack and deeply scan every compressed APK entry; this is a known limitation.

## 8. Quarantine storage

Incoming P2P payloads are staged under app-private `filesDir/security/incoming_quarantine`. Other security intake paths likewise use app-private quarantine locations rather than public or cache-backed storage. Quarantined files are not exposed by the safe-vault FileProvider path.

## 9. P2P receive lifecycle

The P2P receiver persists scan state and classification, verifies size and hash, and retains rejected or inconclusive content in quarantine for controlled handling. Only an authorized release operation can copy content to the safe vault. A scan interrupted in the scanning state can be recovered without assuming that it passed.

## 10. Release authorization policy

`FileReleasePolicy` binds the requested authorization to both the persisted classification and scan outcome. Clean normal files, APKs, and executables have distinct authorization values; failed-scan risk overrides are also classification-specific. Unsupported files and inconclusive/error outcomes do not satisfy the release policy.

## 11. Release integrity and promotion

Before release, the repository re-hashes the quarantined source, compares its persisted classification, creates a unique staging file inside the safe vault, verifies the copied hash, and atomically renames it into place. A mismatch or failed promotion prevents a successful release.

## 12. User confirmation

The transfer UI presents sequential confirmation prompts for risk-bearing releases, including suspicious files and APKs. The repository independently enforces the authorization, so UI confirmation alone cannot bypass the persisted scan and classification checks. Recreating the UI resets the confirmation sequence.

## 13. Quarantine cleanup

Explicit purge operations shred/remove the quarantined transfer content. Successful release removes the quarantine copy where possible; if cleanup fails after promotion, the retained private copy is logged rather than hiding the condition.

## 14. Database and restart behavior

Room database version 25 adds persisted file classification through migration 24→25. Scan and release state operations are stored with transfer metadata so the receiver can enforce the same policy after process restarts.

## 15. File sharing and signaling

The FileProvider configuration exposes only `vault/safe/`. Sender-reported scan status is preserved as unverified context and cannot mark an inbound file as receiver-approved.

## 16. Alternate intake paths

The alternate sandbox path now retains rejected and inconclusive results in quarantine and checks the hash before release. The swarm transfer path no longer automatically promotes a scan-passed file. Its OnDemand UI does not yet provide a fully wired release-confirmation flow, so that path remains an integration gap rather than an equivalent end-user release experience.

## 17. Validation and remaining limitations

Validation completed:

- Targeted `testDebugUnitTest` for `FileReleaseClassifierTests`, `DualGateSecurityUnitTests`, `NexaThreeLayerFileSecurityTests`, and `P2PFileTransferUnitTests`: passed.
- `assembleDebug`: passed.
- `git diff --check` reported trailing whitespace in existing modified files. The worktree contained substantial pre-existing edits, so unrelated formatting was not rewritten as part of this task.

Remaining limitations include the lack of deep per-entry APK malware inspection, the existing scanner's detection limits, and the incomplete OnDemand release-confirmation UI. Unsupported formats remain blocked or inconclusive rather than being granted a clean verdict.
