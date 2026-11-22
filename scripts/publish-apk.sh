#!/usr/bin/env bash
# Builds this checkout's release APK and publishes it for the tablet's updater
# (app/src/main/java/com/ethran/notable/update/AppUpdates.kt, docs/plans/self-update.md):
#
#   scripts/publish-apk.sh OUT_DIR
#
# OUT_DIR gets notable-<versionCode>.apk and latest.json
#   {versionCode, versionName, apk, sha256, size, commit, subject, builtAt}
# (apk relative to latest.json), each moved into place whole; the newest
# $KEEP APKs stay. Nothing is published unless the APK has the version code
# of this commit and, with EXPECTED_CERT_SHA256 set, is signed with that
# certificate (an update with another key would not install).
#
# Signing: STORE_FILE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (app/build.gradle).
# Runs the build in the flake's dev shell (JDK, Android SDK) unless already in it.
set -euo pipefail

out=${1:?usage: publish-apk.sh OUT_DIR}
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

: "${STORE_FILE:?STORE_FILE: the signing keystore}"
keep=${KEEP:-3}
code=$(git rev-list --count HEAD)
commit=$(git rev-parse --short=12 HEAD)
subject=$(git log -1 --format=%s)

# Before the dev shell: a timer's tick with nothing new costs nothing.
if [[ -f $out/latest.json ]] && [[ $(jq -r .commit "$out/latest.json") == "$commit" ]]; then
  echo "build $code ($commit) is published already"
  exit 0
fi

if [[ -z ${ANDROID_HOME:-} ]]; then
  exec nix develop "path:$root" -c "$0" "$@"
fi

./gradlew --no-daemon --console=plain :app:assembleRelease
apk=app/build/outputs/apk/release/app-release.apk

tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
built=$("$tools/aapt2" dump badging "$apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
name=$("$tools/aapt2" dump badging "$apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
if [[ $built != "$code" ]]; then
  echo "the APK says versionCode $built, this commit is $code: not published" >&2
  exit 1
fi
cert=$("$tools/apksigner" verify --print-certs "$apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
if [[ -n ${EXPECTED_CERT_SHA256:-} ]] && [[ ${cert,,} != "$(tr -d ':' <<<"${EXPECTED_CERT_SHA256,,}")" ]]; then
  echo "the APK is signed with $cert, not $EXPECTED_CERT_SHA256: not published" >&2
  exit 1
fi

mkdir -p "$out"
file=notable-$code.apk
cp "$apk" "$out/.$file.tmp"
mv "$out/.$file.tmp" "$out/$file"
sha=$(sha256sum "$out/$file" | cut -d' ' -f1)
size=$(stat -c %s "$out/$file")
jq -n --argjson versionCode "$code" --arg versionName "$name" --arg apk "$file" \
  --arg sha256 "$sha" --argjson size "$size" --arg commit "$commit" --arg subject "$subject" \
  --arg builtAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{versionCode: $versionCode, versionName: $versionName, apk: $apk, sha256: $sha256, size: $size, commit: $commit, subject: $subject, builtAt: $builtAt}' \
  >"$out/.latest.json.tmp"
mv "$out/.latest.json.tmp" "$out/latest.json"

# The newest $keep APKs stay: one being downloaded while the next lands is still there.
ls -1 "$out"/notable-*.apk | sort -t- -k2 -n | head -n -"$keep" | xargs -r rm -f
echo "published build $code ($commit, $(( size / 1048576 )) MB): $subject"
