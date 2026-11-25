# Android v0.1.40 flat account cards

> Execute inline in the assigned isolated worker; the supplied five-point design is already authorized. No helpers, builds, tests, lint, devices, screenshots, real accounts, downloads, CI changes, dependencies or remote writes.

**Goal:** A continuous browser/status-bar surface, readable smaller account typography, complete flat-color cards and an explicit refresh action in the existing account editor.
**Architecture:** Reuse BG behind the transparent system bar and browser toolbar; retain existing safe insets, theme rebuilds and video restoration. Share account-name creation and weekly-value typography, measure name/badge text centers, remove the quota footer entirely, and reuse the existing manual-refresh guards through editor feedback. Use one opaque fill and at most one border for every appearance surface.
**Tech stack:** Existing Android Views, Java 17, AppCompat and Material; no new dependency.
**Spec:** The dispatched Android task's five requirements and explicit verification/release restrictions, replacing previous texture/footer decisions.

## Constraints and review focus

- Base: `2d3af946adc7766a63b72195b678dd09dd4829f5`; branch `Kongbai333/android-flat-cards-v0140`. One complete local commit; coordinator handles signing/push/release.
- Keep all 13 preference IDs and their original base colors; do not reset account preferences or change data/network/auth/proxy/transfer behavior.
- Preserve original-height opaque stacking, full last card, separate edit/switch controls, gesture cancellation and accessibility visibility guards.
- Refresh is offered only for a saved, usable account, uses its saved configuration, and leaves name/proxy/appearance drafts intact. Existing task/current-Web-account guards and QuotaSync cooldowns remain authoritative.
- Source review must cover light/dark/system, Web reentry and video return; long Chinese names, narrow widths and system font scaling; whole badges and right-side action space; cached/unknown/unlimited/exhausted quota semantics; rejected/waiting/failed refreshes without invented success.

## Steps

- [x] Use BG for the Web toolbar without a border or shadow; trace existing theme/inset/video paths and loading feedback.
- [x] Share 16sp account-name creation and 17sp weekly typography, retain bold/tabular figures and a full-size percent sign, and replace baseline badge alignment with measured text-center alignment and wrapping.
- [x] Delete footer views, wordmark, refresh binding and spacing; retain actual quota/invite values, cache provenance, essential status feedback and exact-code copying. Add editor refresh using the same saved-account guards with honest in-panel feedback and no draft commit/dismissal.
- [x] Replace decorated surfaces with one opaque fill and one optional border, remove thumbnail lettering, rename colors descriptively, retain IDs, and use the same rendering in cards/previews/swatches.
- [x] Set `0.1.40`/code `42`, update README behavior/version examples, minimally adapt existing extracted-method fixtures only where compilation requires it, and self-review the complete diff before the single local commit.

## Source review record

- `MainActivity.renderWeb()` now uses BG, with address-field stroke and loading indicator unchanged. `configureInsets()` retains transparent status color, cutout/system-bar padding and disabled system contrast scrim; `applyTheme()`, `renderScreen()` and `hideVideo()` keep effective resource colors and icon contrast coherent.
- `AccountCardTypography` owns only the name/weekly roles. Both sidebar and editor preview use it; `AccountCardValue` keeps percentages full-size and tabular/bold figures. The new warning color is accepted only at contrast >= 4.5 against the opaque fill, otherwise the card ink is used.
- `QuotaCard` no longer constructs footer, note or wordmark children; detail columns end naturally. Unknown quota cannot display the state string as a successful measurement, and weekly success time uses only the quota observation. The quota region retains short-press expansion and long-press provenance; independent extra/invite details and copying are unchanged.
- `AccountEditor` refresh calls the existing activity entry without save/dismiss/leave. Only a saved, usable account gets the action; current-Web-account/generation guards, per-account cooldown and saved credentials/routes remain authoritative. Synchronous state receipts and later real updates feed the in-panel live status, and a no-op cannot reuse a previous success. Quota/portrait updates repaint the preview without changing the name/proxy/color draft; dismissal detaches the editor reference.
- `AccountNameLine` measures constrained name/badge children and their layout ascent/descent centers, with multi-line names or insufficient total width moving the whole badge below. It uses the identity width already reserved by `AccountCardTop`, leaving proxy layout below both children. Pathological badge text wraps inside that single badge rather than ellipsizing or exceeding the reserved width.
- All 13 IDs, original base RGB values, preference file, fallback and persistence path were compared with the base source. Only one opaque `GradientDrawable.setColor()` fill and optional single stroke remain; thumbnails are plain Views and external labels carry selection.
- Existing extracted `QuotaCard.update()` clipboard fixture gained only the new typography/alert interface and removed its unused note field; all business assertions remain verbatim. CI, dependencies, engines, observer, cache model, stack geometry and gesture guards were not changed.
- Final review is an author self-review, as the dispatch prohibits helper workers. No unresolved source-review finding remains; rendering and execution have not been validated.

## Verification boundary

Only source inspection, diff/whitespace checks and Git metadata checks are authorized. No compilation, automated checks, lint or runtime validation will be run or claimed; the user will try the release themselves. Existing fixture business assertions remain intact.
