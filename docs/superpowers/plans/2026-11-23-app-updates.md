# Android App Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline. No helpers per dispatch.

**Goal:** Check official stable GitHub releases, download a verified higher-version same-signer APK on user intent, and hand installation to Android confirmation.
**Architecture:** Pure release/identity policy and streaming download core, standalone bounded HTTPS transport, native PackageInstaller boundary/private receiver, and a small controller reusing existing dialogs/sidebar.
**Tech Stack:** Existing Java17/Android26+/AndroidX, JCA SHA256, HttpsURLConnection, org.json and PackageInstaller; no new runtime dependency.
**Spec:** docs/superpowers/specs/2026-11-23-app-updates-design.md

## Global Constraints
- OfficialrepoID1467211251, no tokens or Muse cookies/proxy access, stable only, preserve0.1.27/code29 and v1 protocol.
- Metadata1MiB/checksum4KiB/APK128MiB, HTTPS/defaultport/explicitGitHub hosts/max5redirects; fixed private paths, cancel/half-file safety.
- Check package name, higher code, version and identical signer sets before session; user action and system unknown-source permission remain mandatory normal path.
- No push/main/tag/dispatch/PR/Release, no real user sessions or actual installed-user-data migration, no helper.

## Review Focus
- Metadata and binary from different versions/repos cannot be mixed.
- Cancel/restart/stale callbacks cannot install partial/stale files or delete user data.
- Signature/package/code failures stop before any system installation session.
- target35 mutable status callback is explicit and receiver not exported; only own session accepted.
- API/offline/rate failure and dialogs do not block core app or trigger account-specific requests.

### Task1: Stable release/source/identity policy
Files: AppUpdateRelease.java, checks/AppUpdateReleaseCheck.java.
Interfaces: choose(Map repository,Map release,String installed):AppUpdateRelease; newer(String,String); checksum(byte[],String); validateIdentity(Apk,Apk,AppUpdateRelease); Asset/Apk records; Failure carries safe code only.
- [x] RED tests for numeric versions, drafts/prereleases/old releases, repo ID, exact APK/sha assets, URL/size/digest, wrong package/code/version/signer.
- [x] Implement policy; focused javac/java GREEN31.

### Task2: Bounded HTTP and cancellable verified file
Files: AppUpdateHttp.java, AppUpdateDownload.java, checks/AppUpdateDownloadCheck.java.
Interfaces: Http implements Download.Transport.open(Asset):Response; metadata(String,int):byte[]; cancel(); Download.run(release,ownedDir,transport,cancel,progress):Verified(File,String sha256). Installer rechecks the same authenticated checksum both before parsing and while copying its session.
- [x] RED real-byte/temp-directory cases for successful stream/hash/rename, wrong hash/length, interrupted/cancelled read, no installable half-package and safe paths.
- [x] Implement standard HTTPS/manual redirects and streaming core, GREEN27. Real HTTP control flow against fake URL connections GREEN14; oversized Retry-After overflow was reproduced RED and clamped before multiplication. Fake connections/transport are explicitly not live network/TLS/device verification.

### Task3: Native permission/session/UI integration
Files: AppUpdateInstaller.java, AppUpdateInstallReceiver.java, AppUpdateUi.java; MainActivity.java, AccountSidebar.java, AndroidManifest.xml, focused source contracts.
Interfaces: UI check(boolean), resume(), pause(), onActivityResult(...), close(); installer stage(Verified,Release,AtomicBoolean):int then foreground commit(int), own-session status dispatch, permission/result handling.
- [x] RED native/source contracts for startup/manual entry, detached public GitHub requests, private mutable receiver, unknown-source and pending action/cancel, no uninstall/user data deletion.
- [x] Implement guarded native APIs and existing-dialog UI, targeted native javac/API correctness and focused GREEN4. Actual selected permission/cancel/end method-body harness GREEN12 with fake UI/installer and real owned-file cleanup, explicitly not device execution. Prepare theme commit before final shared native build.

### Task4: Final affected verification and held-release handoff
Files: README, release notes and external android-findings/android-ready, workflow only to add new checks.
- [x] Run the complete Gradle build/lint/classpath and existing mandatory checks once on ce77ccc:54s build,19 Java programs,39 Node cases plus lifecycle12, store13 and JSON96. After separate review found a passive503 response could refresh old quota/time, reproduce RED and apply the one-line HTTP-success guard; run affected observer/resource packaging checks only, with final hashes/results recorded outside the repo. Do not repeat unchanged Java/full regressions.
- [x] Separate self-review and prepare the held #15/#16/#14 handoff with exact tested heads, code29/main ancestry/frozen protocol, first-upgrade and device limits. Hidden invitation reader is explicitly unresolved and the full no-click first-read sub-goal is not claimed complete; coordinator acceptance is required before lifting release hold.

Ruling: stage writes/fsyncs/closes the own session on IO, then returns a ticket for foreground commit. This avoids launching a system consent flow after an Activity has paused. Permission results before onResume are held as permission_ready; real method-body checks cover deferral and generation-safe cleanup. A process-wide operation lock is released only after canceled IO cleanup, so a destroyed Activity cannot remove a newer operation's files.
