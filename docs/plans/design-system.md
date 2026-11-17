# Design system for the Boox app

Status: in progress. Goal: one calm, consistent, reMarkable-like look across the Android app,
built from a small pattern library (atomic design: tokens → atoms → molecules → organisms) that
every screen uses. Earlier passes: `ui-refresh.md`, `ui-refresh-round-2.md`.

## Audit (2026-11-16, BOOX Note Air 5 C, debug build `com.ethran.notable.dev`)

What breaks the "one designed product" feel, by screen. Code counts are for
`app/src/main/java/com/ethran/notable`.

### Everywhere

- No shared vocabulary. 70 raw Material `Button(`s, 8 `IconButton(`s, 2 `OutlinedButton(`s, beside
  the newer `PaperAction`/`PaperIconAction` (16 uses) and a dozen private one-offs
  (`TextTarget`, `LinkTarget`, `ActionButton`, `MenuPanel`, `SortRow`, `ChoiceRow`, …).
- Colors are typed in, not taken from tokens: `Color.Black` 91×, `Color.White` 64×,
  `Color.Gray`/`LightGray` 18×, `Color(0x…)` 16×. So dark mode can't work, and greys drift
  (PaperLine #B8B8B8, LightGray, Gray, 0.12 alpha, 0.6 alpha all mean "secondary line").
- Type is typed in: 50 `fontSize =` overrides (10, 14, 15, 16, 20 sp …) instead of roles.
- Corners: 35 `RoundedCornerShape(…)` with 0, 2, 3, 8, 50 % and pill radii next to each other.
- Three icon families mixed: Feather line icons, filled Material icons (`MoreHoriz`, gear,
  arrows), and Unicode glyphs used as icons (`▾ ↓ ↑ ✕ ? ▸`).
- Mixed case: "Default Page Background Template", "Double Tap Action", "Debug Mode (show changed
  area)" (Title Case) beside "Enable smooth scrolling", "Open properties on new notebook".
- Shadows/elevation on an e-ink screen (Check-for-update button, Log out button, Copy/Share/Clear).

### Library

- Four control styles in one view: filled black (New notebook), outlined (Quick page…), grey-filled
  chips (folders), bare text with a glyph (`Type ▾`, `Progress ▾`, `↓`, `?`).
- Filter segments (34 dp, square) don't share a height or corner with the action buttons (48 dp,
  2 dp radius) or the search underline; rows don't line up.
- The search underline stops at the sort control; the `?` sits inside the field like a stray glyph.
- The settings gear floats alone at the end of the action row, not where navigation lives.
- Notebook titles break mid-word ("Manifold_for_Charity_Inc_S / AFE"); the `•••` details button is
  heavier than the title it belongs to.
- Facet "pill" when selected is a full pill, segments are square: two shapes for "selected".

### Notebook details dialog

- No frame: the dialog has no border or radius, so it melts into the library behind it.
- Serif dialog title, but "Location: Library" is also serif (should be body text).
- Rating 1–10 as ten square buttons in two rows; "Clear rating" disabled looks like a grey ghost.
- Grey-filled name field vs. white outlined elsewhere; dividers of different weights.
- "Delete notebook" looks like any other button; no destructive treatment or separation.

### Settings

- ON/OFF switches are tiny boxes with 10 sp text, unlike any other control.
- Selects are light-weight text plus a filled ▼ triangle, not a control.
- Footer: a pill-shaped slate "Sponsor" button with a pink heart, above a square black button with
  a drop shadow and a different width. Neither matches anything else.
- Sync tab: bold headings at three sizes, full-width grey "disabled" buttons that look like
  banners, a bordered warning box with a different border weight, a shadowed "Log out".
- Toolbar tab: full-bleed black section bars ("Scrollable section"), filled trash icons and
  struck-eye icons per row; the hint text is 14 sp flush-left, without the row's padding.
- Debug tab: "Memory usage ▸" (glyph as disclosure), crash logs in an unframed monospace block,
  Copy/Share/Clear as shadowed black buttons.
- Version label top-right uses a heavier weight than the title.

### Editor chrome

- Page menu: a 0.5–1 dp black box, uneven separators (some rows separated, some not), no padding
  rhythm, no hierarchy between "Library" and "Export book to xopp".
- Toolbar: pen colors are user data (fine), but the selected marker is a grey pill while selected
  pens are inverted; dividers are 1 dp black bars of differing heights.
- Pages overview: fully rounded pill buttons (only place with pills), "Edit Mode ◯" uses a radio
  glyph as a switch, "Library" as a serif title that is really a back link; the current page is
  marked with a black band carrying the number.

### Dialogs

- `ShowConfirmationDialog`: centred bold 20 sp title, actions spread with `SpaceAround` at the
  bottom, square border; other dialogs use left-aligned serif titles and right-aligned actions.
- Export dialog: three equal buttons for Cancel / PDF / Xopp; long explanatory paragraph.

## Decisions (tokens)

E-ink first: no shadows, no animation, no ripples, opaque surfaces, contrast from ink and
whitespace, one hairline weight. The look is "printed paper": serif for names of places
(screen and dialog titles), a quiet sans for everything you operate.

- **Color** (light / dark): ink #191919 / #F5F5F5, paper #FFFFFF / #191919, inset #F2F2F2 /
  #262626 (fields at rest, quiet fills), secondary #5C5C5C / #B0B0B0, rule #C4C4C4 / #4A4A4A
  (hairlines), disabled #A3A3A3 / #6E6E6E. No other greys in UI code.
- **Type roles**: `display` serif 30, `title` serif 24 (screen, dialog), `heading` serif 19
  (sections), `body` sans 16, `label` sans 15 medium (controls), `meta` sans 13 (secondary
  lines), `mono` 12 (logs, tokens). Sentence case for every label.
- **Space**: 4, 8, 12, 16, 24, 32, 48 dp. Screen gutter 32, dialog padding 28, row padding 16.
- **Shape**: controls 8 dp, surfaces (dialogs, menus, notices) 14 dp, paper (page and cover
  previews) 0. One hairline: 1 dp; focus/selected outline: 2 dp.
- **Size**: controls 44 dp tall (compact 36 dp in dense bars, hit area still ≥ 44); icons 20 dp in
  controls, 22 dp in icon buttons; switch 46×26.
- **Icons**: Feather everywhere in app chrome; the pen/tool drawables stay in the editor toolbar.

## Pattern library (`ui/design/`)

- Tokens: `NotableTheme` (colors, type, space, shape, size) via composition locals, provided by
  `InkaTheme`.
- Atoms: `NButton` (primary, secondary, quiet, danger), `NIconButton`, `NSwitch`, `NCheckbox`,
  `NRadio`, `NTextField`, `NSlider`, `NDivider`, `NBadge`, `NIcon`.
- Molecules: `NSegmented`, `NSelect` (dropdown control), `NSearchField`, `NMenuItem`,
  `NSettingRow` (+ toggle/select/slider/action variants), `NField` (label + control + help),
  `NNotice`, `NTabs`.
- Organisms: `NTopBar`, `NDialog`, `NConfirmDialog`, `NMenu`, `NSection`, `NEmptyState`.
- Gallery: Settings → Debug → "Pattern library" shows every component in each state on the device.

## Work log

- Audit: done (above). Screenshots: scratchpad only.
- Library: `ui/design/` Tokens.kt, Atoms.kt, Molecules.kt, Organisms.kt, PatternLibrary.kt.
  `InkaTheme` provides the tokens and maps Material colors/type onto them; `ui/theme/Color.kt`
  names are aliases of the light tokens. Legacy `PaperAction`, `PaperIconAction`, `OnOffSwitch`,
  `SettingToggleRow`, `SelectorRow`, `SettingSliderRow`, `SelectMenu` now delegate to the new
  components (so every old call site already has the new look), to be removed once unused.
  Compiles; reviewed on the BOOX in the gallery (fixes: top bar action position, menu width).
- Migration, in parallel by disjoint file sets (no commits, no adb from agents):
  library (HomeView, LibraryBar, cards, QuickNav, scrubber), settings (all tabs, sync, system
  info, logs), dialogs (details, folders, confirm/export, background, snack), editor chrome
  (page menu, toolbar and its menus, pages overview, welcome).

- Integrated: promoted `NButton(onLongClick)`, `NIconButton(badge)`, `NPopover`,
  `NSearchField(onSearch)`, `Modifier.nColorMark` (pen color dot, shared by the toolbar and the
  toolbar settings). Deleted the legacy wrappers (`PaperActions.kt`, `Switch.kt`,
  `SettingsUIElements.kt`, `EInkSettingsElements.kt`, `editor/ui/Select.kt`). Pages overview shows
  the notebook title. No raw Material controls remain in app UI; remaining hard-coded colors are
  page/canvas drawing and the selection overlay (out of scope).
- Validation: `compileDebugKotlin`, `compileDebugAndroidTestKotlin` pass; 488 JVM tests, 0 failed
  (`RepoLocalDocsTest` failed once in one full run, then passed 3/3 alone: flaky, sync code,
  untouched). Lint: no new errors (the remaining ones predate this work: missing Polish for the
  sync and live-code string files, NewApi in RepoLocalDocs, BOM in LibrarySearch, LocalContext in
  NotableSyncSection); removed the one string this work made unused. On the BOOX (debug app):
  library, notebook details, all settings tabs, editor toolbar and menu, pages overview and the
  gallery reviewed by screenshot.

- Folders: quiet (borderless) buttons with the folder icon, so places read apart from the
  outlined creation actions.

## Open

- Folder delete has no confirmation (as before).
- Export dialog launches the export in a scope that the dialog's closing may cancel (existing).
- Not checked on device: welcome screen, system information, bug report, snack, template picker,
  move-to-folder, quick nav, dark mode. Instrumented tests not run (they'd uninstall packages).
- New hard-coded English strings in QuickNav/scrubber descriptions; Polish for new live-code
  descriptions missing (that file has no Polish yet).
