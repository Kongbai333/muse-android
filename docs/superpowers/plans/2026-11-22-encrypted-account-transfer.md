# Encrypted Account Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship reviewable Android password-protected account/Cookie/static-proxy export and safe candidate import interoperable with the PC implementation.

**Architecture:** A standard-JCA envelope authenticates one bounded logical JSON payload. Separate bundle, Cookie adapter, cancellable activation flow and Android UI reuse existing profiles/proxy vaults; unpublished candidates never enter account scheduling. Central MuseWeb transfer locking covers foreground, quota and studio navigation.

**Tech Stack:** Java 17 source/JDK 21, Android API 26+, AndroidX Webkit 1.9.0, platform org.json/JCA; pure Java contract checks plus test-only JSON/JUnit where needed.

**Spec:** `docs/superpowers/specs/2026-11-22-encrypted-account-transfer-design.md`; wire authority `docs/protocols/account-transfer-v1.md`.

## Global Constraints

- PBKDF2-HMAC-SHA256 600000 iterations, 16-byte salt, 32-byte key; AES-256-GCM 12-byte nonce/16-byte tag; validate Java UTF-8 Unicode behavior against independent vectors before freeze.
- No real account/Cookie/proxy data, authentication traffic, signing/version change, PC file changes, new workers, pushes, PRs, merges or release.
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

- [ ] Write fixture/reference generator using installed Python hashlib/cryptography only on synthetic data; write Java checks for fixed ASCII/Chinese+emoji, random roundtrip, wrong password/tamper/header/cost/length rejection and bounds.
- [ ] Run `pwsh scripts/check-account-transfer.ps1 -JavaHome <JDK21>`; expect missing AccountTransferCrypto before implementation (record RED).
- [ ] Implement standard JCA, canonical header/AAD, strict UTF-8/encoding/limits and array cleanup; run checks GREEN.
- [ ] Send absolute spec/fixture/Java-generated random package paths to PC and protocol-review evidence to coordinator; retain a ledger of actual producers/consumers, consume PC-generated Rust package when delivered.
- [ ] Review diff, commit protocol/core/fixtures locally only after checks pass.

### Task 2: Logical schema and Cookie representation

**Files:** Create `AccountTransferBundle.java`, `AccountTransferCookies.java`, `checks/AccountTransferBundleCheck.java`; modify `app/build.gradle`/check scripts only for host-test runtime if needed.

**Interfaces:** `AccountTransferBundle.parse(byte[]):Bundle`, `Bundle.toBytes():byte[]`; data-only Account/Proxy/Cookie items with package refs; `fromCookieHeader(String,String):Account`. Cookie adapter reads a selected account's native manager and stages into candidate named profile.

- [ ] Write failing checks for schema/unknown fields, bounds, dangling/duplicate refs, source URL/domain/path validation, shared proxy mapping, raw special characters, expired/unknown/session attrs and header parsing.
- [ ] Run focused check; record expected RED. Implement field validation and platform-boundary adapter without network reads; run GREEN.
- [ ] Confirm current profile vs Default and separate GET_COOKIE_INFO/MULTI_PROFILE checks with fake boundaries/static integration contracts; native code must compile against 1.9.0.
- [ ] Commit focused data/adapter changes after checks pass.

### Task 3: Cancellable transactional candidate activation and route lock

**Files:** Create `AccountTransferFlow.java`, `checks/AccountTransferFlowCheck.java`; modify `AccountStore.java`, `ProxyStore.java`, `MuseWeb.java`.

**Interfaces:** Flow backend prepares per-account candidates asynchronously, stages Cookie callbacks, commits all candidates once, rolls back only new resources, releases route lock once; `cancel()` invalidates all later callbacks. `AccountStore.candidate(name,proxyId)` creates an unpublished account; `activateImported(List<Account>)` atomically publishes. MuseWeb has begin/prepare/end transfer APIs and normal pump rejects transfer navigation.

- [ ] Write RED checks proving route→cookie→flush→commit→activation ordering, single shared node creation, all-or-nothing publish, cancellation at each callback, duplicate/stale completion, storage/proxy failure and release exactly once.
- [ ] Implement controller and narrow existing-store/MuseWeb integration. Never call ordinary add/accountChanged before candidate commit. Run flow and source integration checks GREEN.
- [ ] Commit candidate/gate changes after checks pass.

### Task 4: Native UI and bounded file/password lifecycle

**Files:** Create `AccountTransferUi.java`; modify `MainActivity.java`, `AccountSidebar.java`, `README.md`; add focused integration checks.

**Interfaces:** UI `exportAccounts()`, `importAccounts()`, `importCookieText()`, `onActivityResult(...)`, `close()`; background executor runs file IO/crypto only and native profile/proxy operations remain on UI thread; lifecycle cancellation invalidates every posted continuation.

- [ ] Write RED integration contracts for actions/result dispatch, no sensitive saved state/log, background crypto, bounded reads, candidate-only preview and lifecycle cleanup.
- [ ] Implement export selection/password confirmation/encrypted save; import encrypted read/password/preview/new candidates; legacy header with explicit proxy choice. Explain system/device address, Cookie fidelity and target strict route conversion before activation.
- [ ] Run focused checks GREEN; compile app and fix only changes needed for native API correctness; document flow/capability limits.
- [ ] Commit UI integration after focused checks pass.

### Task 5: End-to-end review and available platform validation

**Files:** Check scripts/fixtures as needed; local review evidence/ledger; final project-external report.

**Interfaces:** Protocol SHA, Java-generated package consumed by Rust, Rust-generated package consumed by Java, all targeted checks and build outcomes recorded with commands.

- [ ] Run bidirectional real-implementation interop (not only fixed fixtures), crypto negatives, bundle/flow checks, all existing Java/browser checks.
- [ ] Run `gradlew.bat :app:assembleDebug :app:lintDebug :app:writeContractCheckClasspath` with JDK21/SDK35; run `scripts/check-contracts.ps1`. Run available native synthetic smoke only in a clean test profile/emulator; never connect to real auth/proxy.
- [ ] Self-review full branch in a separate pass; reproduce important findings RED→GREEN and record any untested platform or limitation. Do not claim Android device or live account tests not run.
- [ ] Record final HEAD/branch/diff/tests/interop and report to coordinator; no push/PR/merge/release.
