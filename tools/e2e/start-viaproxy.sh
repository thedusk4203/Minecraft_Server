#!/usr/bin/env bash
set -euo pipefail

VERSION="3.4.14"
JAR_NAME="ViaProxy-${VERSION}.jar"
DOWNLOAD_URL="https://github.com/ViaVersion/ViaProxy/releases/download/v${VERSION}/${JAR_NAME}"
EXPECTED_SHA256="2894bbfb2f4342f8fde887af6c689313efd5b0e46a8006775eea0e21e2948ab5"
WORK_DIR="${RUNNER_TEMP:-/tmp}/minecraft-server-viaproxy"
JAR_PATH="${WORK_DIR}/${JAR_NAME}"
LOG_PATH="${WORK_DIR}/viaproxy.log"
PID_PATH="${WORK_DIR}/viaproxy.pid"

mkdir -p "${WORK_DIR}"

if [[ ! -f "${JAR_PATH}" ]]; then
  curl --fail --location --silent --show-error "${DOWNLOAD_URL}" --output "${JAR_PATH}"
fi

echo "${EXPECTED_SHA256}  ${JAR_PATH}" | sha256sum --check --strict

java -jar "${JAR_PATH}" cli \
  --target-address 127.0.0.1:25567 \
  --target-version 26.3 \
  >"${LOG_PATH}" 2>&1 &
echo $! >"${PID_PATH}"

for _ in $(seq 1 60); do
  if (echo > /dev/tcp/127.0.0.1/25568) >/dev/null 2>&1; then
    echo "ViaProxy ${VERSION} is listening on 127.0.0.1:25568"
    exit 0
  fi
  if ! kill -0 "$(cat "${PID_PATH}")" 2>/dev/null; then
    cat "${LOG_PATH}"
    echo "ViaProxy exited before becoming ready" >&2
    exit 1
  fi
  sleep 1
done

cat "${LOG_PATH}"
echo "Timed out waiting for ViaProxy" >&2
exit 1
