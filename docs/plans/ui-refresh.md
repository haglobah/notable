# reMarkable-inspired UI refresh

Status: source changes compile, preview APK builds, and on-device visual smoke checks are complete. Notable Preview is installed separately and left open at the library.

## Objective and reference

Bring Notable closer to reMarkable's paper tablet interface while keeping its existing notebook, import, sync, and editor workflows.

Reference: https://support.remarkable.com/articles/Knowledge/Navigating-on-your-reMarkable
The reference illustrations show flat monochrome surfaces, thin rules, serif section headings, and document captions beneath previews. No reMarkable logos, fonts, or artwork were copied.

## Implementation

- Theme: neutral light/dark palettes, serif headings, sans-serif controls, small corner radii.
- Library: distinct app header, spacious folder controls, larger notebook previews, labeled creation/import controls. Sections share the notebook grid's scroll container to accommodate smaller screen heights.
- Cards: notebook titles and localized page counts below previews; restrained borders, white preview surfaces, retained sync badges. Page overview uses larger cards to accommodate 40 dp action controls.
- Shared chrome: horizontal breadcrumbs, lighter editor dividers, stronger settings tab indicator.
- Added a standalone notebook card Compose preview.
- Added flake-parts development shell and lock file: JDK 21, Android platform 37, build tools 36.0.0, SDK environment variables, and a Nix-compatible aapt2 override. README documents usage.
- Added Polish translations for the new labels and page-count plural forms.

Changed files are under `ui/theme`, `ui/components` (BreadCrumb, NotebookCard, PageCard, PagePreview, ShowPagesRow), `ui/views` (HomeView, PagesView, Settings), `editor/ui/Topbar.kt`, `editor/ui/toolbar/Toolbar.kt`, and `res/values/strings.xml`.

## Validation and remaining work

- `git diff --check`: passed.
- Strings: XML parsing, unique resource names, and plural fallback checks passed.
- Initial compile attempt could not start without Java. Beat requested a flake-parts shell to provide it; that blocker is resolved.
- `nix flake check path:. --no-build --all-systems`: passed for x86_64-linux, aarch64-linux, and aarch64-darwin. Removed x86_64-darwin because the pinned Nixpkgs no longer supports it.
- `nix develop path:. --command ...`: Java and javac 21.0.12.1 verified; compileDebugKotlin and assembleDebug passed.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- First lint run: 29 errors and 67 warnings. Three errors concerned the new labels; those now have Polish translations. Verified that all 26 other errors are missing Polish translations already present in HEAD. The follow-up lint run confirmed 26 errors and 67 warnings, with no remaining errors for the new resources.
- Connected device: BOOX Note Air 5 C, Android API 35. Beat authorized a separate temporary app rather than replacing the installed app. Installed `com.ethran.notable.preview`, label `Notable Preview`; the original package was not replaced.
- Temporary build copy: `/tmp/notable-preview-l50q9rn_`; APK under `app/build/outputs/apk/debug/app-debug.apk`. Identity-only adjustments include package ID, provider authority, permission-settings package, deep-link scheme, app label, and Google services client package field.
- Beat explicitly declined separate database/document folders and accepted stray test documents. Reverted the temporary storage changes; verified dbUtils.kt, FileUtils.kt, and ExportEngine.kt are byte-identical to the repo. Both apps will see the same notes. Test edits must be limited to clearly named preview documents.
- BOOX automatically disabled the new package (`enabled=3`, lastDisabledCaller `com.onyx`). Enabled only the preview package and woke the tablet; preview activity launched successfully. Inspected its setup screen.
- Automatic approval review initially blocked granting All files access. Beat explicitly approved it, then granted it himself. The shared library now opens successfully.
- On-device checks passed: populated library, notebook details via long press, creating/renaming a test notebook, opening its editor, expanding toolbar, opening page overview, edit-mode action layout, adding a second page, opening that page (2/2), and settings layout. Existing document contents were not edited.
- Created one test notebook, `Preview - UI Review`, with two blank pages. Beat accepted stray test documents; retained it for his review. The preview uses the shared database as requested.
- Visual review found a bright-green edit-mode indicator; replaced it with white in PagesView.kt. Rebuilt and updated only the preview package. Verified the original app's APK installation path is unchanged.
- Evidence screenshots are in ignored `.artifacts/ui-preview/`: library.png, editor.png, settings.png. Preview is open at the library.
- These are visual/navigation smoke checks, not full pen-hardware, sync, import/export, dark-mode, or screen-size regression tests. Physical e-ink contrast still needs Beat's assessment; screenshots do not capture all panel behavior.
- Future broader regression coverage: empty libraries, long names, large text/UI scale, short/landscape screens, quick navigation, imports, and conflict badges.

## Existing issues observed outside this styling pass

- Notebook title edits can be lost when dismissing with Back while the field remains focused. Reproduced on the tablet; moving focus away with Tab saved the title. Existing NotebookConfig.kt persists in onFocusChanged and does not explicitly save in onDismissRequest. Needs a separate behavior fix with a regression test.

- Lint has 26 pre-existing missing Polish translation errors, plus warnings in dependencies and existing code. No lint baseline or suppression was added. The report is at `app/build/reports/lint-results-debug.html`.

- HomeView's two existing library previews have commented-out render calls and show no UI. They need dependency-free preview fixtures before they can validate the full library.
- Dark mode remains inconsistent across existing hard-coded white/black surfaces. The palette is neutral in both modes, but this is not a complete dark-mode conversion.
- PageCard enables its direct click handler only during reorder dragging; normal navigation also depends on the supplied touchModifier. This existing interaction was left intact and needs device-level verification.

Unrelated Automerge planning files in docs/adr and docs/plans were not changed.
