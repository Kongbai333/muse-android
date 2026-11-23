# Android Account Card Polish

> Execute inline in the existing isolated worker. The supplied design is already authorized; no helpers or new runtime environment.

**Goal:** Two truthful account labels, semantic green progress, four equal action icons and persisted light/dark/system modes.
**Architecture:** Add optional independent invite counters to the existing field cache, a small pure presentation formatter and a theme-mode model. Reuse original WebView GET, budget, uiMode handling and native-view rebuild path.
**Tech Stack:** Existing Java/Android Views/AppCompat1.6.1, Node and host Java checks; no new dependency.
**Spec:** User-approved account-card-polish design; decisions below only settle ordinary implementation details.

## Constraints and decisions

- Base9f654240f7f201a5c47e5a068866bfe373685d24; newfix/android-account-card-polish. No remote writes, real data, generation POST, device install or repeated emulator search.
- Keep code/remaining in a native read-only invitation details dialog; optional passive redeemed/earned stay available there. Labels themselves show compact extra numbers and actualuse_count/max_uses, no old prefixes. Existing sync note identifies cached/unequal-time fields.
- Add per-fielduse_count/max_uses values/times only. Existing schema2 cache stays backward compatible; null/missing never clears values or becomes0/infinity. No migrationv1 change.
- Four equal48dp-high cells,14dp padding and FIT_CENTER create identical20dp square icon areas despite old14/20/22dp intrinsic vectors. Fourth opens three original import/export flows; cancel is inert. Session new/open studio business stays intact.
- Theme preference storeslight/dark/system strings. Migrate an existing explicit theme-darkroomboolean; absent preference defaults system. AppCompat maps modes, effective activity resources supply actualNIGHT, and existingapplyTheme rebuilds native views. Remove recreate fallback; same-color mode changes still refresh the mode icon.
- New green only for normal filled TickBar segments, with separate day/night color resources; retain≥80 risk accent and unknown/unlimited neutral tracks. No global accent change.

## Steps

- [x] RED: real parser/cached counter persistence and normal paint assertions failed as expected; missing three-mode/toolbar/controller entries were separately RED. Labels/model checks cover0/null/compact/independent time; chooser checks retain cancellation and all original dispatch protections.
- [x] Implement optional counters/presentation/two labels and read-only details, cache disclosure in sync note. No QuotaSync change needed: nestedinvite is already passed whole. GET/epoch/route/budget functions are byte-identical.
- [x] Implement semantic green, four equal cells/three-way transfer chooser, legacy migration/tri-state selector/icons/config. Activity now records its renderednight separately from globalNIGHT, so early global resource loads cannot suppress a required config rebind. OriginalapplyTheme/buildInterface only add that record; WebView attachment code unchanged.
- [x] GREEN:12 integration cases (old113grid assertions retained,6chooser expanded to7),20 targeted reader/parser cases,29cache+14label assertions. Actual preference/controller host checks14each, paint/toolbar/read-only dialog bodies tested. Full-opacity day/night green contrast≥3 against BG/raised/emptytrack; original riskaccent/neutral branches retained. SingleassembleDebug25s (17executed/16up-to-date), package/signature/metadata/resources/observer hashes passed. No unrelated suite or new environment search.
- [x] Prepare0.1.29/code31 and current README usage/tag examples. Repo API identity200 and targettag404 confirm availability after Git read reset; preserve raw results. This local theme/version commit is the deliverable; notes/evidence/ready outside repo. Native rendering/config-provider remain untested; no remote writes.
