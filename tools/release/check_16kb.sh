#!/usr/bin/env bash
# Fail unless every arm64 native library in an APK/AAB has 16-KB aligned PT_LOAD segments.
# Android 15+ devices with a 16-KB page size refuse to load a 4-KB aligned library.
set -euo pipefail

artifact=${1:?usage: check_16kb.sh path/to/app-release.apk or .aab}
[[ -f "$artifact" ]] || { echo "check_16kb: artifact not found: $artifact" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# One pattern covers both layouts: 'lib/...' in an APK, 'base/lib/...' in an AAB. unzip exits 11
# when nothing matched, which is not an error here — the caller asserts arm64 libraries exist.
unzip -qq -o "$artifact" '*lib/arm64-v8a/*.so' -d "$tmp" || [[ $? -eq 11 ]]

# ponytail: 20 lines of stdlib struct parsing instead of depending on readelf/llvm-readelf,
# which ship on neither stock macOS nor a plain ubuntu-latest runner.
python3 - "$tmp" <<'PY'
import pathlib, struct, sys

MIN_ALIGN = 16 * 1024
PT_LOAD = 1
bad = []
libs = sorted(pathlib.Path(sys.argv[1]).rglob("*.so"))

for so in libs:
    data = so.read_bytes()
    if data[:4] != b"\x7fELF" or len(data) < 64 or data[4] != 2:
        bad.append(f"{so.name}: not a 64-bit ELF")
        continue
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    loads = 0
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + 56 > len(data):
            bad.append(f"{so.name}: truncated program header table")
            break
        p_type, = struct.unpack_from("<I", data, off)
        if p_type != PT_LOAD:
            continue
        loads += 1
        p_align, = struct.unpack_from("<Q", data, off + 0x30)
        if p_align < MIN_ALIGN:
            bad.append(f"{so.name}: PT_LOAD alignment {p_align} < {MIN_ALIGN}")
    else:
        if loads == 0:
            bad.append(f"{so.name}: no PT_LOAD segment")

for line in bad:
    print(f"check_16kb: {line}", file=sys.stderr)
if bad:
    sys.exit(1)
print(f"check_16kb: {len(libs)} arm64 library(ies) are 16-KB aligned")
PY
