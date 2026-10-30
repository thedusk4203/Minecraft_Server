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
FLOODGATE_SOURCE_URL="https://github.com/GeyserMC/Floodgate/archive/${FLOODGATE_COMMIT}.tar.gz"
FLOODGATE_GRADLE_IMAGE="gradle:7.6-jdk17"

mkdir -p "$OUTPUT_DIR"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_command curl
require_command python3
require_command tar
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
      && grep -Fxq "sha256=$cached_sha" "$provenance_file"; then
      echo "floodgate-velocity.jar already matches pinned source provenance."
      exit 0
    fi
  fi

  local source_archive source_dir temp_output
  source_archive="$(mktemp "$OUTPUT_DIR/.floodgate-source.XXXXXX.tar.gz")"
  source_dir="$(mktemp -d "$OUTPUT_DIR/.floodgate-build.XXXXXX")"
  temp_output="$(mktemp "$OUTPUT_DIR/.floodgate-velocity.jar.XXXXXX")"
  trap 'rm -rf "$source_archive" "$source_dir" "$temp_output"' EXIT

  echo "Downloading Floodgate $FLOODGATE_VERSION build $FLOODGATE_BUILD source at $FLOODGATE_COMMIT from GitHub..."
  curl_retry "$FLOODGATE_SOURCE_URL" -o "$source_archive"
  tar -xzf "$source_archive" --strip-components=1 -C "$source_dir"

  local source_version
  source_version="$(sed -n 's/^version=//p' "$source_dir/gradle.properties" | head -n 1)"
  if [[ "$source_version" != "$FLOODGATE_SOURCE_VERSION" ]]; then
    echo "Floodgate source version mismatch; expected $FLOODGATE_SOURCE_VERSION, got $source_version" >&2
    exit 1
  fi

  echo "Building official Floodgate commit with BUILD_NUMBER=$FLOODGATE_BUILD..."
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e BUILD_NUMBER="$FLOODGATE_BUILD" \
    -e GRADLE_USER_HOME=/tmp/gradle-home \
    -v "$source_dir:/workspace" \
    -w /workspace \
    "$FLOODGATE_GRADLE_IMAGE" \
    gradle :velocity:build --no-daemon

  local built_jar="$source_dir/velocity/build/libs/floodgate-velocity.jar"
  if [[ ! -s "$built_jar" ]]; then
    echo "Floodgate build did not produce velocity/build/libs/floodgate-velocity.jar" >&2
    exit 1
  fi

  cp "$built_jar" "$temp_output"
  local built_sha
  built_sha="$(hash256_file "$temp_output")"
  mv -f "$temp_output" "$output_file"
  {
    echo "source=https://github.com/GeyserMC/Floodgate"
    echo "commit=$FLOODGATE_COMMIT"
    echo "source_version=$FLOODGATE_SOURCE_VERSION"
    echo "runtime_version=$FLOODGATE_VERSION"
    echo "build=$FLOODGATE_BUILD"
    echo "sha256=$built_sha"
  } > "$provenance_file"
  echo "Built Floodgate $FLOODGATE_VERSION build $FLOODGATE_BUILD from commit $FLOODGATE_COMMIT sha256=$built_sha"
)

fetch_geyser
build_floodgate

echo "Pinned crossplay plugins are ready in $OUTPUT_DIR"
