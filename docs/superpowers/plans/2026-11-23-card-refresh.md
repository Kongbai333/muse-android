# Android Card Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. No helpers per dispatch.

**Goal:** Preserve last successful card fields/times on transient failures and refresh the current account automatically without changing its route.
**Architecture:** A bounded pure-Java field cache feeds the existing native card; the observer retains trusted origin/session generations and bounded named-action retry; QuotaSync remains the sole native probe owner.
**Tech Stack:** Existing Java17/Android26+, AndroidX WebView, strict generic JSON helper and Node/Java contracts; no new runtime dependency.
**Spec:** docs/superpowers/specs/2026-11-23-card-refresh-design.md

## Global Constraints

- No real credentials/profiles/auth requests or invite reveal/claim/generation, no new Muse HTTP client, no push/main/tag/dispatch/release.
- Preserve0.1.27/code29, original static proxy/profile isolation and frozen encrypted protocol.
- Missing/unavailable source cannot be called complete automatic hidden invite retrieval; public model alone is not an endpoint contract.
- User authorized owner defaults and inline work; no additional approval or helper review.

## Review Focus

- Partial fields must not fabricate success time for old independent fields.
- Valid0/unlimited and old-cycle values must remain distinct from unknown/current values.
- Deletion/route change/logout and late callbacks must not contaminate another account.
- No added code clicks or requests an unverified invite action.
- Automatic current-account work must respect studio/transfer/proxy/lifecycle gates.

### Task1: Persistent independent field model and stale display

Files: new AccountCardState.java, checks/AccountCardStateCheck.java; modify AccountQuota.java, checks/MobileParityCheck.java.
Interfaces: AccountCardState.read(Map<String,Object>,long), merge(Map<String,Object>,long):AccountCardState, rows():Map<String,Object>, quota(long), inviteCode(), inviteRemaining(), observed(String), lastSuccess(); old AccountQuota.read signature retained. QuotaSync bridges its existing JSONObject codec, so the integer-only transfer JSON/wire is not changed to accept quota decimals.

- [x] Write failing synthetic checks for legacy migration, transient empty merge/time preservation, partial quota/extra/invite, valid0/unlimited, old timestamps and stale/cross-cycle labels; run focused javac/java RED.
- [x] Implement validated per-field timestamp model and visible stale semantics; run same GREEN (18 checks).

### Task2: Observer status, bounded retry and passive invitation

Files: muse-observer.js, checks/check-observer.cjs (targeted appended tests).
Interfaces: snapshot still returns quota/status/avatar; add safe explicit fields and invite status, retain existing module exports and origin/sessionEpoch.

- [x] Write failing Node behavior cases for403 keeping quota,429 no immediate retry, named-action discovery retry limits and no invite click; run only new cases RED.
- [x] Implement bounded policy and passive extraction, preserving proven subscription payload semantics; run focused GREEN. Record unproven hidden invite reader separately.

### Task3: Native merge, card state and current-account scheduling

Files: QuotaSync.java, QuotaCard.java, MainActivity.java, AccountSidebar.java; narrow native/source checks.
Interfaces: QuotaSync.card(String):AccountCardState; existing value/state retained; refresh/cancel/remove preserve generation ownership; host calls one current-account automatic path.

- [x] Add RED checks for native persistence using field model, invite receipt, cancellation/timeout ownership and foreground gating.
- [x] Wire independent merges and stale labels, serialize evaluation, invalidate canceled callbacks; add lifecycle/current-account throttled triggers without background account switching; GREEN focused checks (4 source boundaries; 13 real pure-Java retry policy cases). Full observer plus native boundaries:28 pass.
- [x] Review diff and prepare the separate #15 commit, update android-findings with exact source limitations, then proceed to #16. Targeted javac against the real existing Android/AndroidX compile classpath succeeded; final Gradle/native build and mandatory existing contracts remain deferred until both additions are complete.

Ruling (superseded by R-A1 below): the original policy indefinitely paused automatic attempts on429 or3 ordinary failures. This was too broad for transient failures;403/auth pauses remain intentional.

### Review R-A1: Recover automatically after transient cooldowns

Files: QuotaRefreshPolicy.java, checks/QuotaRefreshPolicyCheck.java; spec above. Keep the existing allow/started/finished/loginReady/paused/remove interfaces and all QuotaSync/MainActivity ownership gates.

- [x] Replace permanent-pause expectations with real-clock-input behavioral RED cases:429 recovers at expiry including after end's cancel notification; first two failures wait1minute, then15/30/60minute bounded attempts; repeated failures stay capped and success resets; manual/login cannot bypass a hard rate limit. Retain403/auth/account isolation tests.
- [x] Run focused javac plus the policy check's `rate` and `failure` cases; preserve both expected behavior failures and exit codes outside the repo. Compilation0, each behavior case1 on the original policy.
- [x] Reserve indefinite automaticPaused for403; keep rateUntil monotonic, add capped timed ordinary-failure backoff, and prevent cancel from shortening a reservation. No caller, timer, routing, cache or authentication change.
- [x] Run the full focused policy program GREEN (46 checks) and the unchanged reviewer's original probe, also against actual Gradle-produced policy classes. One incremental assembleDebug succeeded22s; package/debug signature/metadata/checksum passed. Previous full verification remains history.
- [x] Finish only bounded cached module/reference-repo invitation tracing and record the remaining gap in external findings/ready. Reference fixed commit f7f5be5 has a UI-driven Settings quota reader and local dispatch use_count, not a verified invitation reader. No push/main/tag/CI/Release;#14hold remains. This local theme commit is the review deliverable.

Ruling: this review explicitly limits verification to affected policy and incremental build, so the previous19-program full run is not repeated. The existing60second foreground pulse consumes a recovery opportunity only when host/page/route/profile gates and the single-probe guard allow work.

### Authenticated-contract follow-up: Automatic read-only invitation GET

Base eb468dbe; spec's new reader section is binding, already authorized for inline execution. Preserve version/signing/updater/transfer andR-A1. No helpers or real account access; no remote writes.

Files: muse-observer.js (fixed GET, independent budget and nullable parser), QuotaSync.java (independent native policy, snapshot flags, terminal receipt and bounded close), QuotaCard.java (honest unknown labels), checks/check-observer.cjs and check-card-refresh-integration.cjs (real observer behavior and extracted actual native boundaries).

Interfaces: `refreshInvite(manual=false)`, `snapshot(force=false,showAvatar=true,readInvite=true,manualInvite=false)`; snapshot adds `invite_retry_after_ms`, while invite_status has waiting/syncing/retry_wait/ok/forbidden/logged_out/rate_limited/request_failed/unrecognized. Native `receiveInvite(Probe,JSONObject)` records one terminal outcome; no values/credentials are logged.

- [x] Add behavioral RED for hidden-panel automatic GET, exact method/options, null/0/partial merge, independent success/failure budgets,401/403/429 expiry, transport/size/timeout and oldroute/epoch. Use only fake fetch/clock, not public/auth requests.
- [x] Implement the fixed GET and invalidation with separate serial/controller/clock; preserve quota values/status/time and existing manual DOM path. Reuse model parser with an explicit valid-empty mode. Manual retries wait at least30s; hard429 is never bypassed.
- [x] Wire independent native invitation budget across WorkPages, wait boundedly for its result without failing successful quota, retain cancellation/route gates;14 checks execute actual native method bodies with OS boundaries replaced. Field model now honors independent JS field timestamps (21 checks).
- [x] Run affected observer/native checks (47 Node cases) plus focused field model/R-A1 checks only (21/46 also consume actual Gradle classes); one incremental assembleDebug26s,6 tasks executed/27up-to-date. Packaging/debug signature/metadata/checksum and packaged observer hash passed. Preserve old logs.
- [x] Update external ready/findings/release draft with precise source versus PC real-session evidence; this local theme commit is the deliverable. No push/main/tag/CI/Release.

Ruling: existing quota/avatar-only fixtures now explicitly pass readInvite=false, so their fake fetch counters still test the original boundary;15 dedicated new observer cases exercise the default automatic invitation path. NativeJava changes are real (budget receipt and per-field time merge); this is not a JS-only repackage. No fresh full19-program/lint/updater regression was claimed.
