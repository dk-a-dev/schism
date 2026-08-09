#!/usr/bin/env bash
set -euo pipefail

apk=${1:?usage: verify-release-packaging.sh path/to/app-release.apk}
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
build_tools=$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
apksigner="$build_tools/apksigner"
zipalign="$build_tools/zipalign"
apkanalyzer=$(find "$sdk_root/cmdline-tools" -type f -name apkanalyzer | head -1)

# The expected version code comes from the build file. Pinning it here only agrees with the build
# until the next bump, and a bare `[[ a == b ]]` under `set -e` fails with no output at all — this
# script exited 1 silently for exactly that reason after 10301 became 10302.
gradle_file=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)/app/build.gradle.kts
expected_version_code=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$gradle_file")
expected_application_id=$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' "$gradle_file")
[[ -n $expected_version_code && -n $expected_application_id ]] ||
    { echo "cannot read applicationId/versionCode from $gradle_file" >&2; exit 1; }

# Every check below reports what it saw. A gate that fails without saying why costs more time than
# it saves.
expect() {
    local what=$1 actual=$2 want=$3
    [[ $actual == "$want" ]] || { echo "$apk: $what is '$actual', expected '$want'" >&2; exit 1; }
}

# Read each tool's output into a variable before matching. Piping into `grep -q` under
# `set -o pipefail` makes grep close the pipe on its first match, killing the producer with
# SIGPIPE and failing the script with 141 on a perfectly good APK.
signer_output=$("$apksigner" verify --verbose "$apk")
grep -q '^Verifies$' <<<"$signer_output" || { echo "$apk: signature does not verify" >&2; exit 1; }
expect "application id" "$("$apkanalyzer" manifest application-id "$apk")" "$expected_application_id"
expect "versionCode" "$("$apkanalyzer" manifest version-code "$apk")" "$expected_version_code"
expect "targetSdk" "$("$apkanalyzer" manifest target-sdk "$apk")" "36"
expect "debuggable" "$("$apkanalyzer" manifest debuggable "$apk")" "false"
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
