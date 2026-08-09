#!/usr/bin/env bash
# Self-check for the release verification tools. No frameworks, no network, no signing key:
# fixtures are tiny synthetic zips holding synthetic ELF stubs, built inline with python3.
#
#   tools/release/test_release_tools.sh
set -euo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$here/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

pass=0
fail=0

ok() { pass=$((pass + 1)); echo "ok   - $1"; }
bad() { fail=$((fail + 1)); echo "FAIL - $1"; }

# expect_pass <name> <cmd...>
expect_pass() {
    local name=$1; shift
    if "$@" >"$tmp/out" 2>&1; then ok "$name"; else bad "$name"; sed 's/^/       /' "$tmp/out"; fi
}

# expect_fail <name> <expected-substring> <cmd...>
expect_fail() {
    local name=$1 needle=$2; shift 2
    if "$@" >"$tmp/out" 2>&1; then
        bad "$name (command unexpectedly succeeded)"
    elif grep -Fq "$needle" "$tmp/out"; then
        ok "$name"
    else
        bad "$name (failed, but not with \"$needle\")"; sed 's/^/       /' "$tmp/out"
    fi
}

gradle_file=$repo_root/schism-android/app/build.gradle.kts
app_id=$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' "$gradle_file")
version_code=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$gradle_file")
version_name=$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' "$gradle_file")

python3 - "$tmp" "$app_id" <<'PY'
import struct, sys, zipfile

APP_ID = sys.argv[2]
from pathlib import Path

out = Path(sys.argv[1])

def elf(align):
    """Minimal little-endian AArch64 ELF64 with a single PT_LOAD segment."""
    ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\x00" * 8
    ehdr = ident + struct.pack(
        "<HHIQQQIHHHHHH",
        3, 183, 1,      # ET_DYN, EM_AARCH64, EV_CURRENT
        0, 64, 0, 0,    # e_entry, e_phoff, e_shoff, e_flags
        64, 56, 1,      # e_ehsize, e_phentsize, e_phnum
        64, 0, 0,       # e_shentsize, e_shnum, e_shstrndx
    )
    phdr = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 0x1000, 0x1000, align)
    return ehdr + phdr

ALIGNED = elf(0x4000)
UNALIGNED = elf(0x1000)

def zip_at(name, entries):
    path = out / name
    with zipfile.ZipFile(path, "w") as z:
        for entry, data in entries.items():
            z.writestr(entry, data)
    return path

APK_OK = {
    "AndroidManifest.xml": APP_ID.encode(),
    "lib/arm64-v8a/libonnxruntime.so": ALIGNED,
    "lib/arm64-v8a/libopencv_java4.so": ALIGNED,
}
zip_at("good.apk", APK_OK)
zip_at("models.apk", dict(APK_OK, **{"assets/models/det/inference.onnx": b"leaked"}))
zip_at("badabi.apk", dict(APK_OK, **{"lib/armeabi-v7a/libonnxruntime.so": ALIGNED}))
zip_at("noarm64.apk", {"AndroidManifest.xml": APP_ID.encode()})
zip_at("noruntime.apk", {"lib/arm64-v8a/libsomething.so": ALIGNED})
zip_at("unaligned.apk", dict(APK_OK, **{"lib/arm64-v8a/libonnxruntime.so": UNALIGNED}))
zip_at("good.aab", {
    "base/manifest/AndroidManifest.xml": b"\x0a\x0f" + APP_ID.encode(),
    "base/lib/arm64-v8a/libonnxruntime.so": ALIGNED,
})
zip_at("models.aab", {
    "base/manifest/AndroidManifest.xml": APP_ID.encode(),
    "base/lib/arm64-v8a/libonnxruntime.so": ALIGNED,
    "base/assets/models/rec/inference.onnx": b"leaked",
})
PY

verify="$here/verify_android_artifacts.sh"
check16="$here/check_16kb.sh"
checksums="$here/generate_checksums.sh"

echo "# verify_android_artifacts.sh"
expect_pass "accepts a well-formed APK layout" "$verify" --structure-only "$tmp/good.apk"
expect_pass "accepts a well-formed AAB layout" "$verify" --structure-only "$tmp/good.aab"
expect_pass "accepts both artifacts in one run" "$verify" --structure-only "$tmp/good.apk" "$tmp/good.aab"
expect_fail "rejects OCR models bundled in an APK" "bundles OCR model assets" \
    "$verify" --structure-only "$tmp/models.apk"
expect_fail "rejects OCR models bundled in an AAB" "bundles OCR model assets" \
    "$verify" --structure-only "$tmp/models.aab"
expect_fail "rejects a non-arm64 ABI" "unsupported ABI" \
    "$verify" --structure-only "$tmp/badabi.apk"
expect_fail "rejects an artifact with no arm64 library" "no arm64-v8a native library" \
    "$verify" --structure-only "$tmp/noarm64.apk"
expect_fail "rejects a missing ONNX Runtime library" "missing the ONNX Runtime" \
    "$verify" --structure-only "$tmp/noruntime.apk"
expect_fail "rejects a 4-KB aligned native library" "PT_LOAD alignment" \
    "$verify" --structure-only "$tmp/unaligned.apk"
expect_fail "rejects a missing artifact" "artifact not found" \
    "$verify" --structure-only "$tmp/absent.apk"

echo "# check_16kb.sh"
expect_pass "accepts 16-KB aligned libraries" "$check16" "$tmp/good.apk"
expect_fail "rejects 4-KB aligned libraries" "PT_LOAD alignment 4096" "$check16" "$tmp/unaligned.apk"
expect_pass "accepts the AAB library layout" "$check16" "$tmp/good.aab"

echo "# generate_checksums.sh"
dist="$tmp/dist"
mkdir -p "$dist"
cp "$tmp/good.apk" "$dist/schism-v1.3.0.apk"
cp "$tmp/good.aab" "$dist/schism-v1.3.0.aab"
expect_fail "rejects a missing release directory" "not a directory" "$checksums" "$tmp/empty-$$"
mkdir -p "$tmp/empty-$$"
expect_fail "rejects a release directory with no files" "no artifacts in" "$checksums" "$tmp/empty-$$"
expect_pass "writes checksums and a manifest" "$checksums" "$dist"
expect_pass "SHA256SUMS verifies" bash -c "cd '$dist' && shasum -a 256 -c SHA256SUMS"

cp "$dist/SHA256SUMS" "$tmp/first.sums"
cp "$dist/artifact-manifest.json" "$tmp/first.json"
expect_pass "regenerating is byte-identical" bash -c \
    "'$checksums' '$dist' >/dev/null && cmp -s '$tmp/first.sums' '$dist/SHA256SUMS' && cmp -s '$tmp/first.json' '$dist/artifact-manifest.json'"

expect_pass "manifest satisfies the published schema's required keys" python3 -c '
import json, sys
APP_ID, VERSION_CODE, VERSION_NAME = sys.argv[3], int(sys.argv[4]), sys.argv[5]
schema = json.load(open(sys.argv[1]))
manifest = json.load(open(sys.argv[2]))
missing = [k for k in schema["required"] if k not in manifest]
missing += ["source." + k for k in schema["properties"]["source"]["required"] if k not in manifest["source"]]
missing += ["tools." + k for k in schema["properties"]["tools"]["required"] if k not in manifest["tools"]]
assert not missing, missing
assert manifest["applicationId"] == APP_ID
assert manifest["versionCode"] == VERSION_CODE and manifest["versionName"] == VERSION_NAME
assert manifest["targetSdk"] == 36 and manifest["minSdk"] == 26
assert manifest["abis"] == ["arm64-v8a"] and manifest["bundledOcrModels"] is False
assert len(manifest["artifacts"]) == 2
for a in manifest["artifacts"]:
    assert len(a["sha256"]) == 64 and a["size"] > 0
' "$repo_root/docs/release/v1.3/artifact-manifest.schema.json" "$dist/artifact-manifest.json" "$app_id" "$version_code" "$version_name"

printf 'tampered' >> "$dist/schism-v1.3.0.apk"
expect_fail "SHA256SUMS catches a tampered artifact" "FAILED" bash -c \
    "cd '$dist' && shasum -a 256 -c SHA256SUMS"

echo
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
