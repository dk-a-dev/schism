#!/usr/bin/env bash
# Write a deterministic SHA256SUMS and artifact-manifest.json for a release directory.
#
#   generate_checksums.sh dist/v1.3.0
#
# Verify later with:  cd dist/v1.3.0 && shasum -a 256 -c SHA256SUMS
# The manifest conforms to docs/release/v1.3/artifact-manifest.schema.json.
set -euo pipefail

dir=${1:?usage: generate_checksums.sh <release-directory>}
[[ -d $dir ]] || { echo "checksums: not a directory: $dir" >&2; exit 1; }

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
dir=$(cd -- "$dir" && pwd)

# Everything in the directory except the two files we are about to write.
# read loop, not mapfile: macOS still ships bash 3.2.
names=()
while IFS= read -r name; do
    names+=("$name")
done < <(cd "$dir" && find . -maxdepth 1 -type f \
    ! -name SHA256SUMS ! -name artifact-manifest.json | sed 's|^\./||' | LC_ALL=C sort)
((${#names[@]} > 0)) || { echo "checksums: no artifacts in $dir" >&2; exit 1; }

(cd "$dir" && shasum -a 256 "${names[@]}" > SHA256SUMS)

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
build_tools=$(basename "$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)")
gradle_version=$(sed -n 's/.*gradle-\([0-9][^-]*\)-.*\.zip/\1/p' \
    "$repo_root/schism-android/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null | head -1)

SCHISM_COMMIT=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || true) \
SCHISM_TAG=${RELEASE_TAG:-$(git -C "$repo_root" describe --tags --exact-match 2>/dev/null || true)} \
SCHISM_COMMITTED_AT=$(git -C "$repo_root" show -s --format=%cI HEAD 2>/dev/null || true) \
SCHISM_BUILD_TOOLS=$build_tools \
SCHISM_GRADLE=$gradle_version \
SCHISM_JAVA=$(java -version 2>&1 | head -1 | tr -d '"') \
SCHISM_SIGNER=${EXPECTED_SIGNER_SHA256:-} \
python3 - "$dir" "$repo_root/schism-android/app/build.gradle.kts" <<'PY'
import hashlib, json, os, pathlib, re, sys

release_dir = pathlib.Path(sys.argv[1])

# applicationId/versionName/versionCode are read from the build file, never pinned here. This
# manifest previously claimed 1.3.0/10300 two releases after that stopped being true, because a
# second copy of a fact is a copy that goes stale silently.
gradle = pathlib.Path(sys.argv[2]).read_text()
def gradle_value(key, quoted=True):
    pattern = rf'^\s*{key}\s*=\s*"([^"]+)"' if quoted else rf'^\s*{key}\s*=\s*(\d+)'
    match = re.search(pattern, gradle, re.MULTILINE)
    if not match:
        raise SystemExit(f"checksums: cannot read {key} from {sys.argv[2]}")
    return match.group(1)
artifacts = []
for line in (release_dir / "SHA256SUMS").read_text().splitlines():
    digest, name = line.split("  ", 1)
    artifacts.append({
        "name": name,
        "sha256": digest,
        "size": (release_dir / name).stat().st_size,
    })

def env(key):
    value = os.environ.get(key, "").strip()
    return value or None

manifest = {
    "schemaVersion": 1,
    "applicationId": gradle_value("applicationId"),
    "versionName": gradle_value("versionName"),
    "versionCode": int(gradle_value("versionCode", quoted=False)),
    "targetSdk": 36,
    "minSdk": 26,
    "abis": ["arm64-v8a"],
    "bundledOcrModels": False,
    "source": {
        "commit": env("SCHISM_COMMIT"),
        "tag": env("SCHISM_TAG"),
        # Commit time, not wall-clock time, so re-running produces byte-identical output.
        "committedAt": env("SCHISM_COMMITTED_AT"),
    },
    "signerSha256": env("SCHISM_SIGNER"),
    "tools": {
        "androidBuildTools": env("SCHISM_BUILD_TOOLS"),
        "gradle": env("SCHISM_GRADLE"),
        "java": env("SCHISM_JAVA"),
    },
    "artifacts": artifacts,
}

out = release_dir / "artifact-manifest.json"
out.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
print(f"checksums: {len(artifacts)} artifact(s) -> {release_dir/'SHA256SUMS'} and {out}")

# Guard against the manifest and SHA256SUMS drifting apart.
for entry in artifacts:
    actual = hashlib.sha256((release_dir / entry["name"]).read_bytes()).hexdigest()
    if actual != entry["sha256"]:
        sys.exit(f"checksums: digest mismatch for {entry['name']}")
PY
