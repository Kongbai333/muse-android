# Account Sidebar Layout Fix

> Execute inline in the existing isolated worker; no helpers. The user already approved the concrete layout and routine implementation.

**Goal:** Reduce account-tab stacking without changing business callbacks or stored data.
**Architecture:** Local native View changes in AccountSidebar/QuotaCard; keep MobileUi styles and existing flows.
**Tech Stack:** Existing Java/Android Views, no new dependencies.
**Spec:** The short design below records the supplied layout-brief/done-means.

## Short design and constraints

- Keep Add account primary; one weighted Import/Export row below. A native Import dialog selects encrypted package or Cookie text; showing/canceling has no business side effect.
- Keep Studio; place proxy/theme/update in one equal-width48dp icon row, preserving callbacks, selected tint, theme/count accessibility descriptions and tooltips.
- Scope revision2: recent sessions show only a single ellipsized title (48dp minimum row); owner/type remain in accessibility/storage. Keep selection/open/long-press/delete guards.
- Place resetLabel immediately after the weekly label in its flexible heading, hide it when unknown, and retain a bounded right-hand weekly value. Use one extra/code/remaining row, weights34/46/20 with two6dp gaps, END ellipsis and full accessibility/tooltip values. Keep real0/unknown/stale display semantics; optional redeemed/earned move to a separate bounded auxiliary text only when present. Do not alter cache/collection.
- Revision3: floating account popup uses three equal slots per row (including blank trailing slots), a safe-root-bounded ScrollView and the original anchoring/dismiss/retuck. Only foreground or parked WebView instances count as started; cache/probe/saved account do not. Unstarted appearance is grey, with original usability/exhaustion click guards unchanged. Remove only the popup management shortcut; retain Add account. Proxy list removes generic tutorials and shortens normal status, preserving actual risk/unsupported/error and all operations.
- Scope only layout/choice regression/docs. Preserve0.1.27/code29, signing, migrationv1, observer, routing, updater and cache. No remote writes or release operations.
- Verify320/360dp using actual min(340dp,86%screen) and nested padding; font1.0/1.3, light/dark and long/unknown/zero values. If no existing device/emulator, report geometry constraints and unavailable native rendering honestly; do not install an image or create surrogate screenshots.

## Steps

- [x] RED→GREEN: execute the actual new dialog method body with a fake native-dialog boundary;6 assertions prove show/cancel are inert and each selection invokes only its original callback after dismissal.
- [x] Implement revisions1/2/3 and the dialog. Normalized source identity proves13 business/callback bodies,20 protected files and the session selection/long-press block unchanged; theme/tab restoration is among those bodies.
- [x] Existing known SDK/PATH/standardAVD inventory has no attached device/emulator/AVD; onlylayoutlib-api, no renderer. Record source-driven geometry for320/360dp, densities1/2/3 and explicit font1.0/1.3/day/night native gaps; no surrogate screenshot.
- [x] Revision3 RED→GREEN: actual popup/cell builders execute113 host View graph/state/bounds assertions for1/2/3/4/7 wrapping, blank slots, startup versus selection guards, no implicit launch, Add/switch/retuck and safe-root bounds. Not native rendering. The original floating-switch checks were not removed or rewritten to skip guards.
- [x] Revision1 build22s and revision2 build24s are history because new scope arrived during each. Final revision3 incremental assembleDebug21s passed (4executed/29up-to-date); only2 affected focused Node cases ran, not19Java/PC/updater suites. Package/debug signature/metadata/checksum, final DEX code markers and untouched observer asset match; this local theme commit is the deliverable. No push/main/tag/dispatch/Release.
