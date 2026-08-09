#!/usr/bin/env bash
set -euo pipefail

apk=${1:?usage: verify-release-packaging.sh path/to/app-release.apk}
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
build_tools=$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
apksigner="$build_tools/apksigner"
zipalign="$build_tools/zipalign"
apkanalyzer=$(find "$sdk_root/cmdline-tools" -type f -name apkanalyzer | head -1)

# Read each tool's output into a variable before matching. Piping into `grep -q` under
# `set -o pipefail` makes grep close the pipe on its first match, killing the producer with
# SIGPIPE and failing the script with 141 on a perfectly good APK.
signer_output=$("$apksigner" verify --verbose "$apk")
grep -q '^Verifies$' <<<"$signer_output"
[[ $("$apkanalyzer" manifest application-id "$apk") == "ai.schism.split" ]]
[[ $("$apkanalyzer" manifest version-code "$apk") == "10301" ]]
[[ $("$apkanalyzer" manifest target-sdk "$apk") == "36" ]]
[[ $("$apkanalyzer" manifest debuggable "$apk") == "false" ]]
"$zipalign" -c -P 16 4 "$apk" >/dev/null

listing=$(unzip -l "$apk")
if grep -Eq 'assets/models/(det|rec)/inference' <<<"$listing"; then
    echo "Production OCR models leaked into the APK; they must be delivered by the backend." >&2
    exit 1
fi
if grep -Eq 'lib/(x86|x86_64|armeabi-v7a)/' <<<"$listing"; then
    echo "Release APK contains an unsupported ABI." >&2
    exit 1
fi
grep -q 'lib/arm64-v8a/' <<<"$listing"

size=$(stat -f %z "$apk" 2>/dev/null || stat -c %s "$apk")
(( size < 100 * 1024 * 1024 )) || {
    echo "Release APK exceeds the 100 MiB standalone size budget: $size bytes" >&2
    exit 1
}

echo "Verified release APK: $apk ($size bytes)"
