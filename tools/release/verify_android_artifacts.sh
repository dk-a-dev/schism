#!/usr/bin/env bash
# Gate for the Schism v1.3.0 Android release artifacts.
#
#   verify_android_artifacts.sh [--structure-only] <artifact.aab|artifact.apk> ...
#
# There is exactly one release variant (no product flavors), so a release is one AAB plus one
# APK built from the same commit. OCR models are downloaded from the backend at runtime, so no
# artifact may contain model bytes.
#
# EXPECTED_SIGNER_SHA256 pins the release signing certificate. It defaults to the Schism upload
# certificate below, which is deliberately committed: a certificate fingerprint is public (it ships
# inside every signed APK), so pinning it here enforces the check on local runs as well as CI with
# no secret to configure. Export the variable to override it.
#
# This is the UPLOAD certificate, which is what our own build outputs are signed with. Play App
# Signing re-signs the AAB with a separate app signing key, so apps installed from Play present a
# different certificate — that is expected and is not what this script verifies.
set -euo pipefail

: "${EXPECTED_SIGNER_SHA256:=666bdaa2a45dfbb29f861fa262ce7c34673705b9ee76e079fcd160308f81ad0a}"

APPLICATION_ID=ai.schism.split
VERSION_NAME=1.3.0
VERSION_CODE=10300
TARGET_SDK=36
MIN_SDK=26

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
structure_only=0
if [[ ${1:-} == --structure-only ]]; then
    # ponytail: exists so test_release_tools.sh can exercise the structural rules on synthetic
    # zips without an Android SDK. The release path never passes it.
    structure_only=1
    shift
fi
(($# > 0)) || { echo "usage: verify_android_artifacts.sh [--structure-only] <artifact> ..." >&2; exit 1; }

fail() { echo "verify: $*" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
build_tools() {
    local dir
    dir=$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)
    [[ -n $dir ]] || fail "no Android build-tools under $sdk_root"
    printf '%s' "$dir"
}

check_structure() {
    local artifact=$1 listing
    listing=$(unzip -Z1 "$artifact") || fail "cannot read zip entries from $artifact"

    if grep -Eq '(^|/)assets/models/' <<<"$listing"; then
        fail "$artifact bundles OCR model assets; models are delivered by the backend at runtime"
    fi
    if grep -Eq '(^|/)lib/(x86|x86_64|armeabi-v7a)/' <<<"$listing"; then
        fail "$artifact contains an unsupported ABI; release ships arm64-v8a only"
    fi
    grep -Eq '(^|/)lib/arm64-v8a/.*\.so$' <<<"$listing" ||
        fail "$artifact contains no arm64-v8a native library"
    # The OCR runtime (not the models) legitimately ships in every build.
    grep -Eq '(^|/)lib/arm64-v8a/libonnxruntime.*\.so$' <<<"$listing" ||
        fail "$artifact is missing the ONNX Runtime native library"
}

check_apk() {
    local apk=$1 bt packaging certs digest
    bt=$(build_tools)

    packaging="$repo_root/schism-android/tools/verify-release-packaging.sh"
    [[ -x $packaging ]] || fail "missing $packaging (application id, version, target SDK, debuggable, signature)"
    "$packaging" "$apk"

    # verify-release-packaging.sh covers applicationId/versionCode/targetSdk/debuggable; these two
    # facts are not in its list.
    local badging
    badging=$("$bt/aapt2" dump badging "$apk")
    grep -Fq "versionName='$VERSION_NAME'" <<<"$badging" || fail "$apk versionName is not $VERSION_NAME"
    grep -Fq "sdkVersion:'$MIN_SDK'" <<<"$badging" || fail "$apk minSdk is not $MIN_SDK"

    certs=$("$bt/apksigner" verify --print-certs "$apk")
    if grep -qi 'CN=Android Debug' <<<"$certs"; then
        fail "$apk is signed with the Android debug certificate"
    fi
    # Lower-cased with tr, not ${x,,}: macOS still ships bash 3.2.
    digest=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs" | head -1 | tr 'A-F' 'a-f')
    [[ -n $digest ]] || fail "$apk has no signer certificate digest"
    if [[ -n ${EXPECTED_SIGNER_SHA256:-} ]]; then
        [[ $digest == "$(printf '%s' "$EXPECTED_SIGNER_SHA256" | tr 'A-F' 'a-f')" ]] ||
            fail "$apk signer digest $digest does not match EXPECTED_SIGNER_SHA256"
        echo "verify: signer digest matches the pinned production certificate"
    else
        echo "verify: signer digest $digest (EXPECTED_SIGNER_SHA256 unset, not enforced)"
    fi
}

check_aab() {
    local aab=$1
    unzip -Z1 "$aab" | grep -Fxq 'base/manifest/AndroidManifest.xml' ||
        fail "$aab has no base module manifest"
    unzip -qq -o "$aab" 'base/manifest/AndroidManifest.xml' -d "$work"
    # ponytail: the AAB manifest is protobuf and bundletool is not on the runner; grepping the
    # package string is enough because the APK from the same variant is fully checked above.
    grep -Faq "$APPLICATION_ID" "$work/base/manifest/AndroidManifest.xml" ||
        fail "$aab base manifest does not declare $APPLICATION_ID"
}

for artifact in "$@"; do
    [[ -f $artifact ]] || fail "artifact not found: $artifact"
    check_structure "$artifact"
    "$repo_root/tools/release/check_16kb.sh" "$artifact"
    if ((structure_only == 0)); then
        case $artifact in
            *.apk) check_apk "$artifact" ;;
            *.aab) check_aab "$artifact" ;;
            *) fail "unsupported artifact type: $artifact" ;;
        esac
    fi
    echo "verify: OK $artifact"
done

echo "verify: all $# artifact(s) passed (app $APPLICATION_ID $VERSION_NAME/$VERSION_CODE, targetSdk $TARGET_SDK)"
