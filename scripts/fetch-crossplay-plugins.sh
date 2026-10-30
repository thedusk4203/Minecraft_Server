#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$REPO_ROOT/build/crossplay-plugins"

GEYSER_VERSION="2.12.1"
GEYSER_BUILD="1277"
FLOODGATE_VERSION="2.2.5"
FLOODGATE_BUILD="141"
BASE_URL="https://download.geysermc.org/v2/projects"

mkdir -p "$OUTPUT_DIR"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_command curl
require_command python3
require_command sha256sum

fetch_plugin() {
  local project="$1"
  local version="$2"
  local build="$3"
  local download_key="$4"
  local output_name="$5"

  local metadata_url="$BASE_URL/$project/versions/$version/builds/$build"
  local download_url="$metadata_url/downloads/$download_key"
  local metadata_file
  local temp_file
  metadata_file="$(mktemp)"
  temp_file="$(mktemp "$OUTPUT_DIR/.${output_name}.XXXXXX")"

  cleanup() {
    rm -f "$metadata_file" "$temp_file"
  }
  trap cleanup RETURN

  echo "Resolving pinned $project $version build $build..."
  curl --fail --silent --show-error --location \
    --retry 4 --retry-all-errors --connect-timeout 20 \
    "$metadata_url" -o "$metadata_file"

  local metadata
  metadata="$(python3 - "$metadata_file" "$project" "$version" "$build" "$download_key" <<'PY'
import json
import re
import sys

path, project, version, build_raw, download_key = sys.argv[1:]
build = int(build_raw)
with open(path, "r", encoding="utf-8") as handle:
    data = json.load(handle)

if data.get("project_id") != project:
    raise SystemExit(f"project mismatch: expected {project}, got {data.get('project_id')}")
if data.get("version") != version:
    raise SystemExit(f"version mismatch: expected {version}, got {data.get('version')}")
if data.get("build") != build:
    raise SystemExit(f"build mismatch: expected {build}, got {data.get('build')}")

download = data.get("downloads", {}).get(download_key)
if not isinstance(download, dict):
    raise SystemExit(f"download key missing: {download_key}")
sha256 = download.get("sha256")
name = download.get("name")
if not isinstance(sha256, str) or re.fullmatch(r"[a-f0-9]{64}", sha256) is None:
    raise SystemExit("upstream metadata returned an invalid SHA-256")
if not isinstance(name, str) or not name.endswith(".jar"):
    raise SystemExit("upstream metadata returned an invalid artifact name")

print(f"{sha256}\t{name}")
PY
)"

  local expected_sha upstream_name
  IFS=$'\t' read -r expected_sha upstream_name <<<"$metadata"

  local output_file="$OUTPUT_DIR/$output_name"
  if [[ -f "$output_file" ]]; then
    local existing_sha
    existing_sha="$(sha256sum "$output_file" | awk '{print $1}')"
    if [[ "$existing_sha" == "$expected_sha" ]]; then
      echo "$output_name already matches pinned $project $version build $build ($expected_sha)."
      return
    fi
  fi

  echo "Downloading $upstream_name -> $output_name..."
  curl --fail --silent --show-error --location \
    --retry 4 --retry-all-errors --connect-timeout 20 \
    "$download_url" -o "$temp_file"

  echo "$expected_sha  $temp_file" | sha256sum --check --status || {
    echo "SHA-256 mismatch for $project $version build $build" >&2
    exit 1
  }

  mv -f "$temp_file" "$output_file"
  echo "Verified $output_name sha256=$expected_sha"
}

fetch_plugin geyser "$GEYSER_VERSION" "$GEYSER_BUILD" velocity "Geyser-Velocity.jar"
fetch_plugin floodgate "$FLOODGATE_VERSION" "$FLOODGATE_BUILD" velocity "floodgate-velocity.jar"

echo "Pinned crossplay plugins are ready in $OUTPUT_DIR"
