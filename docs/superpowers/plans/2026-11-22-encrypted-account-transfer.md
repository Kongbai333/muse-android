# Encrypted Account Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship reviewable Android password-protected account/Cookie/static-proxy export and safe candidate import interoperable with the PC implementation.

**Architecture:** A standard-JCA envelope authenticates one bounded logical JSON payload. Separate bundle, Cookie adapter, cancellable activation flow and Android UI reuse existing profiles/proxy vaults; unpublished candidates never enter account scheduling. Central MuseWeb transfer locking covers foreground, quota and studio navigation.

**Tech Stack:** Java 17 source/JDK 21, Android API 26+, AndroidX Webkit 1.9.0, existing org.json stores and standard JCA; bounded transfer JSON plus pure Java, Node and explicit native-boundary contract checks.

**Spec:** `docs/superpowers/specs/2026-11-22-encrypted-account-transfer-design.md`; wire authority `docs/protocols/account-transfer-v1.md`.

## Global Constraints

- PBKDF2-HMAC-SHA256 600000 iterations, 16-byte salt, 32-byte key; AES-256-GCM 12-byte nonce/16-byte tag; validate Java UTF-8 Unicode behavior against independent vectors before freeze.
- No real account/Cookie/proxy data, authentication traffic, signing/version change, PC product file changes, new workers, PRs, merges or release. Latest user authorization allows ordinary feature-branch push only after both clients report ready and the coordinator releases that barrier; the coordinator owns exact workflow dispatch.
- Existing worktree/branch only; initial HEAD `315fd3d849783d30fba16b8a4a79196124fec537`, clean. Keep no plaintext/password configuration or temporary files.
- New IDs by default; shared package proxies retained; no endpoint-only merge, silent direct fallback, Cookie expiry invention, IP features or source session parsing.
- User explicitly approved execution in this worker: no repeated execution/design approval pause; self-review instead of dispatching a reviewer because helpers are forbidden.

## Review Focus

- Unicode passwords/invalid surrogates and exact raw plus/percent/equal credentials must survive Java/Rust (Task 1/2).
- Invalid encrypted headers/size/cost must fail before expensive derivation or state changes (Task 1).
- Crash/cancel/stale callback cannot publish candidates or reopen a blocked page (Task 3).
- Native feature mismatches/unknown host-only/old headers must not read another profile or invent expiry (Task 2/4).
- Storage/proxy failure must not mutate unrelated accounts/shared nodes or silently schedule background requests (Task 3/5).

---

### Task 1: Freeze protocol and verify standard crypto interoperability

**Files:** Create `docs/protocols/account-transfer-v1.md`, `checks/fixtures/account-transfer-v1/`, `checks/AccountTransferCryptoCheck.java`, `scripts/check-account-transfer.ps1`, `app/src/main/java/com/muse/workspace/mobile/AccountTransferCrypto.java`.

**Interfaces:** `encrypt(byte[] plaintext,char[] password):byte[]`, `decrypt(byte[] envelope,char[] password):byte[]`; package-private deterministic test overload. Public operation errors contain no input values. MAX_PLAINTEXT_BYTES=4194304, MAX_FILE_BYTES=6291456.

- [x] Write fixture/reference generator using installed Python hashlib/cryptography only on synthetic data; write Java checks for fixed ASCII/Chinese+emoji, random roundtrip, wrong password/tamper/header/cost/length rejection and bounds.
- [x] Run `pwsh scripts/check-account-transfer.ps1 -JavaHome <JDK21>`; expect missing AccountTransferCrypto before implementation (record RED).
- [x] Implement standard JCA, canonical header/AAD, strict UTF-8/encoding/limits and array cleanup; run checks GREEN.
- [x] Send absolute spec/fixture/Java-generated random package paths to PC and protocol-review evidence to coordinator; retain a ledger of actual producers/consumers, consume PC-generated Rust package when delivered.
- [x] Review diff, commit protocol/core/fixtures locally only after checks pass (`c5c162607b371fcdbb86c874c9055610eb6dbd3e`).

### Task 2: Logical schema and Cookie representation

**Files:** Create `AccountTransferBundle.java`, `AccountTransferJson.java`, `AccountTransferCookieText.java`, `AccountTransferCookies.java`, `checks/AccountTransferBundleCheck.java`; update focused check scripts. No new runtime dependency is needed.

**Interfaces:** `AccountTransferBundle.parse(byte[]):AccountTransferBundle`, `AccountTransferBundle.toBytes():byte[]`; nested Account/Proxy/Cookie data items with package refs; `AccountTransferBundle.fromCookieHeader(String,String):Account`. Cookie adapter reads a selected account's native manager and stages into a new candidate named profile, rejecting any existing profile collision.

- [x] Write failing checks for schema/unknown fields, bounds, dangling/duplicate refs, source URL/domain/path validation, shared proxy mapping, raw special characters, expired/unknown/session attrs and header parsing.
- [x] Run focused check; record expected RED. Implement field validation and platform-boundary adapter without network reads; run GREEN.
- [x] Confirm current profile vs Default and separate GET_COOKIE_INFO/MULTI_PROFILE checks with fake boundaries/static integration contracts; native code compiles against 1.9.0.
- [x] Include data/adapter changes in the coherent implementation commit after checks pass.

### Task 3: Cancellable transactional candidate activation and route lock

**Files:** Create `AccountTransferFlow.java`, `checks/AccountTransferFlowCheck.java`; modify `AccountStore.java`, `ProxyStore.java`, `MuseWeb.java`.

**Interfaces:** `AccountTransferFlow.Backend` supplies begin/createProxy/route/cookies/commit/rollback/end; `cancel()` invalidates later callbacks. `AccountStore.candidate(AccountTransferFlow.Candidate):Account` maps the flow-owned fresh UUID/proxy binding without publishing; `activateImported(List<Account>):void` atomically publishes. `MuseWeb.beginTransfer():boolean`, `prepareTransferRoute(Account,boolean,Consumer<String>):void`, `endTransfer():void` cover the central pump. Rollback deletes only profiles/nodes actually created by the operation.

- [x] Write RED checks proving route→cookie→flush→commit→activation ordering, single shared node creation, all-or-nothing publish, cancellation at each callback, duplicate/stale completion, storage/proxy failure and release exactly once.
- [x] Implement controller and narrow existing-store/MuseWeb integration. Never call ordinary add/accountChanged before candidate commit. Run flow and source integration checks GREEN.
- [x] Include candidate/gate changes in the coherent implementation commit after checks pass.

### Task 4: Native UI and bounded file/password lifecycle

**Files:** Create `AccountTransferUi.java`; modify `MainActivity.java`, `AccountSidebar.java`, `README.md`; add focused integration checks.

**Interfaces:** UI `exportAccounts()`, `importAccounts()`, `importCookieText()`, `onActivityResult(...)`, `close()`; background executor runs file IO/crypto only and native profile/proxy operations remain on UI thread; lifecycle cancellation invalidates every posted continuation.

- [x] Write RED integration contracts for actions/result dispatch, no sensitive saved state/log, background crypto, bounded reads, candidate-only preview and lifecycle cleanup.
- [x] Implement export selection/password confirmation/encrypted save; import encrypted read/password/preview/new candidates; legacy header with explicit proxy choice. Explain system/device address, Cookie fidelity and target strict route conversion before activation.
- [x] Run focused checks GREEN; compile app and fix only changes needed for native API correctness; document flow/capability limits.
- [x] Include UI integration in the coherent implementation commit after focused checks pass.

### Task 5: End-to-end review and available platform validation

**Files:** Check scripts/fixtures as needed; local review evidence/ledger; final project-external report.

Additional checks: `scripts/check-account-transfer-native.ps1` runs the real Cookie adapter against explicit OS doubles; `checks/check-account-transfer-store.cjs` executes actual store transaction method bodies with fake storage; `checks/check-account-transfer-json.cjs` cross-validates strict JSON semantics against Node's standard JSON implementation. These do not claim Android-device execution.

**Interfaces:** Protocol SHA, Java-generated package consumed by Rust, Rust-generated package consumed by Java, all targeted checks and build outcomes recorded with commands.

- [x] Run bidirectional real-implementation interop (not only fixed fixtures), crypto negatives, bundle/flow checks, all existing Java/browser checks.
- [x] Run `gradlew.bat :app:assembleDebug :app:lintDebug :app:writeContractCheckClasspath` with JDK21/SDK35; run `scripts/check-contracts.ps1`. Available native-boundary checks use explicit OS doubles; actual device/profile/provider/SAF smoke is deferred because no device was supplied and emulator installation is outside scope. Never connect to real auth/proxy.
- [x] Self-review full branch in a separate pass; reproduce important findings RED→GREEN and record any untested platform or limitation. Do not claim Android device or live account tests not run.
- [x] Validate optional display-only workflow `build_request_id` and run-name without changing existing trigger/signing/release behavior.
- [ ] Capture final committed HEAD with fresh regression and external ready report; await the dual-client push barrier, with no PR/merge/tag/release.

Execution note: Tasks 2–4 are one cohesive implementation commit so the native UI, store boundary and checks cannot land in a half-integrated state. Final ready evidence is captured after that commit outside the repository; the remaining delivery checkbox is deliberately left open until that evidence exists. Local green results do not claim device execution, accepted Muse identity or remote CI success.
