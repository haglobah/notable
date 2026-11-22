# Plan: Notable updates itself from cirdan

Goal (Beat, 2026-11-22): every merge to `main` reaches the tablet without a cable, with at most
Android's install prompt.

## How it works

- **Build (cirdan, noldor `modules/notable-apk.nix`):** a timer every 10 minutes (and
  `notable-release` by hand) fetches `main` into its own clone (`/var/lib/notable-apk/notable`) and,
  when it moved, runs `scripts/publish-apk.sh /var/lib/notable-apk/www`: `assembleRelease` in the
  flake's dev shell, signed with gondor's debug key (the key the tablet's app has; on cirdan at
  `~/.config/notable-signing/notable.keystore`, certificate SHA-256 `61:20:5F:6C:…:65:BE`).
- **Publish:** the script refuses an APK whose version code isn't this commit's or whose
  certificate isn't `EXPECTED_CERT_SHA256`, then moves `notable-<code>.apk` and `latest.json`
  into place whole and keeps the newest three APKs. Caddy serves the directory at
  `https://flow.humane.tools/notable/` without sign-in.
- **`latest.json`:** `{versionCode, versionName, apk, sha256, size, commit, subject, builtAt}`;
  `apk` is relative to `latest.json`.
- **Version code:** `git rev-list --count HEAD` (app/build.gradle; 42 without git). Builds of
  `main` only grow, and the tablet's first build had 42.
- **App (`update/AppUpdates.kt`):** reads `BuildConfig.UPDATE_URL` on resume (at most every 5
  minutes). A newer build puts a dot on the library's settings gear; Settings → General →
  Updates shows it with its commit subject. Install opens "install unknown apps" the first time,
  then downloads, checks the SHA-256 and commits a `PackageInstaller` session.
  `UpdateInstallReceiver` shows Android's confirm screen when it asks for one. From Android 12,
  once Notable has installed itself, `USER_ACTION_NOT_REQUIRED` makes later updates silent.

## Bootstrap

The build on the tablet before this (code 42) has no updater: open the newest APK's link once on
the tablet (`https://flow.humane.tools/notable/notable-<code>.apk`, see `latest.json`) and
install it over the old app (same key and id, so the data stays). From then on the gear's dot.

## Open

- Release builds run R8. Keep rules cover Onyx, MuPDF, lz4, Automerge, QuickJS and websockets;
  a crash in a release build that a debug build doesn't have is the first thing to suspect.
- Only `main` is built. Branches go to the tablet over adb as before.
