#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$REPO_ROOT/build/crossplay-plugins"

GEYSER_PROJECT_ID="wKkoqHrH"
GEYSER_VERSION="2.12.1-b1277"
GEYSER_EXPECTED_SHA512="02559b2aeaacbb7a0916c1e9b830fffd4638f22d79852c854fc258fb37b8ce2b491369fa293944c3678e3021dcaa3d3fc2870532c0fbe7a7854ce483dbf31b2d"
GEYSER_METADATA_URL="https://api.modrinth.com/v2/project/${GEYSER_PROJECT_ID}/version?loaders=%5B%22velocity%22%5D&game_versions=%5B%2226.3%22%5D"

FLOODGATE_VERSION="2.2.5"
FLOODGATE_SOURCE_VERSION="2.2.5-SNAPSHOT"
FLOODGATE_BUILD="141"
FLOODGATE_COMMIT="81b65ccb766bf8024858ebf3eefa6974056aecc5"
FLOODGATE_REPO_URL="https://github.com/GeyserMC/Floodgate.git"
FLOODGATE_GRADLE_IMAGE="gradle:7.6-jdk17"
FLOODGATE_RECIPE="git-recursive-full-build-v1"
FLOODGATE_EXPECTED_DESCRIPTOR_VERSION="2.2.5-SNAPSHOT (b141-81b65cc)"

mkdir -p "$OUTPUT_DIR"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_command curl
require_command python3
require_command git
require_command docker

if command -v sha256sum >/dev/null 2>&1; then
  hash256_file() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  hash256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  echo "Required SHA-256 tool not found: install sha256sum or shasum" >&2
  exit 1
fi

if command -v sha512sum >/dev/null 2>&1; then
  hash512_file() { sha512sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  hash512_file() { shasum -a 512 "$1" | awk '{print $1}'; }
else
  echo "Required SHA-512 tool not found: install sha512sum or shasum" >&2
  exit 1
fi

curl_retry() {
  curl --fail --silent --show-error --location \
    --retry 4 --retry-all-errors --connect-timeout 20 \
    -H 'User-Agent: thedusk4203/Minecraft_Server crossplay bootstrap' \
    "$@"
}

verify_velocity_descriptor() {
  local jar_file="$1"
  local expected_id="$2"
  local expected_main="$3"
  local expected_version="${4:-}"

  python3 - "$jar_file" "$expected_id" "$expected_main" "$expected_version" <<'PY'
import json
import sys
import zipfile

jar_path, expected_id, expected_main, expected_version = sys.argv[1:]
with zipfile.ZipFile(jar_path) as archive:
    try:
        raw = archive.read("velocity-plugin.json")
    except KeyError as exc:
        raise SystemExit("velocity-plugin.json is missing from plugin jar") from exc
    names = set(archive.namelist())

data = json.loads(raw.decode("utf-8"))
if data.get("id") != expected_id:
    raise SystemExit(f"Velocity plugin id mismatch: expected {expected_id}, got {data.get('id')}")
if data.get("main") != expected_main:
    raise SystemExit(f"Velocity plugin main mismatch: expected {expected_main}, got {data.get('main')}")
if expected_version and data.get("version") != expected_version:
    raise SystemExit(f"Velocity plugin version mismatch: expected {expected_version}, got {data.get('version')}")
main_class = expected_main.replace(".", "/") + ".class"
if main_class not in names:
    raise SystemExit(f"Velocity plugin main class is missing: {main_class}")
print(f"Validated Velocity descriptor id={data.get('id')} version={data.get('version')} main={data.get('main')}")
PY
}

fetch_geyser() (
  local output_file="$OUTPUT_DIR/Geyser-Velocity.jar"
  local metadata_file temp_file
  metadata_file="$(mktemp)"
  temp_file="$(mktemp "$OUTPUT_DIR/.Geyser-Velocity.jar.XXXXXX")"
  trap 'rm -f "$metadata_file" "$temp_file"' EXIT

  echo "Resolving pinned Geyser $GEYSER_VERSION for Velocity/26.3 from Modrinth..."
  curl_retry "$GEYSER_METADATA_URL" -o "$metadata_file"

  local resolved
  resolved="$(python3 - "$metadata_file" "$GEYSER_VERSION" "$GEYSER_EXPECTED_SHA512" <<'PY'
import json
import sys

path, expected_version, expected_sha512 = sys.argv[1:]
with open(path, "r", encoding="utf-8") as handle:
    versions = json.load(handle)

matches = [item for item in versions if item.get("version_number") == expected_version]
if len(matches) != 1:
    raise SystemExit(f"expected exactly one Modrinth version {expected_version}, found {len(matches)}")
version = matches[0]
files = version.get("files") or []
primary = [item for item in files if item.get("primary") is True]
if len(primary) != 1:
    raise SystemExit(f"expected exactly one primary file, found {len(primary)}")
file = primary[0]
if file.get("filename") != "Geyser-Velocity.jar":
    raise SystemExit(f"unexpected Geyser filename: {file.get('filename')}")
sha512 = (file.get("hashes") or {}).get("sha512")
if sha512 != expected_sha512:
    raise SystemExit(f"Geyser SHA-512 metadata mismatch: expected {expected_sha512}, got {sha512}")
url = file.get("url")
if not isinstance(url, str) or not url.startswith("https://cdn.modrinth.com/"):
    raise SystemExit(f"unexpected Geyser download URL: {url}")
print(url)
PY
)"

  if [[ -f "$output_file" ]] && [[ "$(hash512_file "$output_file")" == "$GEYSER_EXPECTED_SHA512" ]]; then
    verify_velocity_descriptor "$output_file" "geyser" "org.geysermc.geyser.platform.velocity.GeyserVelocityPlugin"
    echo "Geyser-Velocity.jar already matches pinned SHA-512."
    exit 0
  fi

  echo "Downloading verified Geyser artifact from Modrinth CDN..."
  curl_retry "$resolved" -o "$temp_file"
  local actual_sha512
  actual_sha512="$(hash512_file "$temp_file")"
  if [[ "$actual_sha512" != "$GEYSER_EXPECTED_SHA512" ]]; then
    echo "Geyser SHA-512 mismatch; expected $GEYSER_EXPECTED_SHA512, got $actual_sha512" >&2
    exit 1
  fi
  verify_velocity_descriptor "$temp_file" "geyser" "org.geysermc.geyser.platform.velocity.GeyserVelocityPlugin"
  mv -f "$temp_file" "$output_file"
  echo "Verified Geyser $GEYSER_VERSION sha512=$GEYSER_EXPECTED_SHA512"
)

build_floodgate() (
  local output_file="$OUTPUT_DIR/floodgate-velocity.jar"
  local provenance_file="$OUTPUT_DIR/floodgate-velocity.provenance"

  if [[ -f "$output_file" && -f "$provenance_file" ]]; then
    local cached_sha
    cached_sha="$(hash256_file "$output_file")"
    if grep -Fxq "commit=$FLOODGATE_COMMIT" "$provenance_file" \
      && grep -Fxq "build=$FLOODGATE_BUILD" "$provenance_file" \
      && grep -Fxq "recipe=$FLOODGATE_RECIPE" "$provenance_file" \
      && grep -Fxq "sha256=$cached_sha" "$provenance_file"; then
      verify_velocity_descriptor "$output_file" "floodgate" "org.geysermc.floodgate.VelocityPlugin" "$FLOODGATE_EXPECTED_DESCRIPTOR_VERSION"
      echo "floodgate-velocity.jar already matches pinned source provenance."
      exit 0
    fi
  fi

  local work_root source_dir temp_output
  work_root="$(mktemp -d "$OUTPUT_DIR/.floodgate-build.XXXXXX")"
  source_dir="$work_root/Floodgate"
  temp_output="$(mktemp "$OUTPUT_DIR/.floodgate-velocity.jar.XXXXXX")"
  trap 'rm -rf "$work_root" "$temp_output"' EXIT

  echo "Checking out Floodgate $FLOODGATE_VERSION build $FLOODGATE_BUILD at $FLOODGATE_COMMIT with recursive submodules..."
  git clone --quiet --filter=blob:none --no-checkout "$FLOODGATE_REPO_URL" "$source_dir"
  git -C "$source_dir" checkout --quiet --detach "$FLOODGATE_COMMIT"
  git -C "$source_dir" submodule update --init --recursive --depth 1

  local checked_commit source_version submodule_status
  checked_commit="$(git -C "$source_dir" rev-parse HEAD)"
  if [[ "$checked_commit" != "$FLOODGATE_COMMIT" ]]; then
    echo "Floodgate checkout mismatch; expected $FLOODGATE_COMMIT, got $checked_commit" >&2
    exit 1
  fi
  if ! git -C "$source_dir" diff --quiet || ! git -C "$source_dir" diff --cached --quiet; then
    echo "Floodgate checkout is not clean before build" >&2
    exit 1
  fi
  submodule_status="$(git -C "$source_dir" submodule status --recursive)"
  if grep -Eq '^[+-]' <<<"$submodule_status"; then
    echo "Floodgate submodule checkout is not pinned cleanly:" >&2
    echo "$submodule_status" >&2
    exit 1
  fi

  source_version="$(sed -n 's/^version=//p' "$source_dir/gradle.properties" | head -n 1)"
  if [[ "$source_version" != "$FLOODGATE_SOURCE_VERSION" ]]; then
    echo "Floodgate source version mismatch; expected $FLOODGATE_SOURCE_VERSION, got $source_version" >&2
    exit 1
  fi

  echo "Reproducing upstream Floodgate build workflow with BUILD_NUMBER=$FLOODGATE_BUILD..."
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e BUILD_NUMBER="$FLOODGATE_BUILD" \
    -e GRADLE_USER_HOME=/tmp/gradle-home \
    -v "$source_dir:/workspace" \
    -w /workspace \
    "$FLOODGATE_GRADLE_IMAGE" \
    ./gradlew build --no-daemon

  local built_jar="$source_dir/velocity/build/libs/floodgate-velocity.jar"
  if [[ ! -s "$built_jar" ]]; then
    echo "Floodgate build did not produce velocity/build/libs/floodgate-velocity.jar" >&2
    exit 1
  fi

  cp "$built_jar" "$temp_output"
  verify_velocity_descriptor "$temp_output" "floodgate" "org.geysermc.floodgate.VelocityPlugin" "$FLOODGATE_EXPECTED_DESCRIPTOR_VERSION"

  local built_sha
  built_sha="$(hash256_file "$temp_output")"
  mv -f "$temp_output" "$output_file"
  {
    echo "source=$FLOODGATE_REPO_URL"
    echo "commit=$FLOODGATE_COMMIT"
    echo "source_version=$FLOODGATE_SOURCE_VERSION"
    echo "runtime_version=$FLOODGATE_VERSION"
    echo "build=$FLOODGATE_BUILD"
    echo "recipe=$FLOODGATE_RECIPE"
    while IFS= read -r line; do
      [[ -n "$line" ]] && echo "submodule=$line"
    done <<<"$submodule_status"
    echo "sha256=$built_sha"
  } > "$provenance_file"
  echo "Built Floodgate $FLOODGATE_VERSION build $FLOODGATE_BUILD from exact Git checkout sha256=$built_sha"
)

fetch_geyser
build_floodgate

echo "Pinned crossplay plugins are ready in $OUTPUT_DIR"
