#!/bin/bash
# Shared helpers, sourced by the scripts in this directory. Never prints write keys.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
AVD_NAME=${TRANTHOR_AVD_NAME:-tranthor-api36}
AVD_IMAGE=${TRANTHOR_AVD_IMAGE:-"system-images;android-36;google_apis_playstore;arm64-v8a"}

require_sdk() {
  if [[ -z "${ANDROID_HOME:-}" ]]; then
    for candidate in "${ANDROID_SDK_ROOT:-}" /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
      if [[ -n "$candidate" && -d "$candidate/platforms" ]]; then export ANDROID_HOME=$candidate; break; fi
    done
  fi
  [[ -n "${ANDROID_HOME:-}" ]] || { echo 'Set ANDROID_HOME to an Android SDK with platforms;android-36.' >&2; exit 1; }
  export ANDROID_SDK_ROOT=$ANDROID_HOME
  ADB="$ANDROID_HOME/platform-tools/adb"
  EMULATOR="$ANDROID_HOME/emulator/emulator"
  AVDMANAGER=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1 || true)
  [[ -x "$ADB" ]] || { echo "Missing $ADB (install platform-tools)." >&2; exit 1; }
}

# Starts the vendored Node mock on a free loopback port (port 0 avoids a selection/start race).
# Sets MOCK_HOST and MOCK_PORT; mock_stop must run from the caller's EXIT trap.
MOCK_PID=
MOCK_LOG=
mock_start() {
  MOCK_LOG=$(mktemp -t tranthor-mock.XXXXXX)
  node "$ROOT/conformance/server/cli.cjs" --port 0 >"$MOCK_LOG" &
  MOCK_PID=$!
  for ((n=0; n<100; n++)); do
    [[ -s "$MOCK_LOG" ]] && break
    kill -0 "$MOCK_PID" 2>/dev/null || { cat "$MOCK_LOG" >&2; exit 1; }
    sleep 0.1
  done
  MOCK_HOST=$(node -e 'const fs=require("node:fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],"utf8").trim().split("\n")[0]).host)' "$MOCK_LOG")
  [[ "$MOCK_HOST" == http://127.0.0.1:* ]] || { echo 'Mock did not report a loopback host.' >&2; exit 1; }
  MOCK_PORT=${MOCK_HOST##*:}
  for ((n=0; n<100; n++)); do
    if curl --silent --fail "$MOCK_HOST/control/time" >/dev/null; then return 0; fi
    sleep 0.1
  done
  curl --silent --fail "$MOCK_HOST/control/time" >/dev/null
}
mock_stop() {
  if [[ -n "$MOCK_PID" ]]; then kill "$MOCK_PID" 2>/dev/null || true; wait "$MOCK_PID" 2>/dev/null || true; MOCK_PID=; fi
  if [[ -n "$MOCK_LOG" ]]; then rm -f "$MOCK_LOG"; MOCK_LOG=; fi
}

# Finds a running emulator for $AVD_NAME, or creates/boots it. Exports ANDROID_SERIAL.
# STARTED_EMULATOR=1 means this script launched it and should stop it afterwards.
STARTED_EMULATOR=0
EMULATOR_PID=
find_running_avd() {
  local serial
  for serial in $("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1}'); do
    if [[ "$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')" == "$AVD_NAME" ]]; then echo "$serial"; return 0; fi
  done
  return 1
}
ensure_avd() {
  if "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then return 0; fi
  [[ -d "$ANDROID_HOME/system-images/android-36/google_apis_playstore/arm64-v8a" ]] || {
    echo "Missing system image $AVD_IMAGE; install it with sdkmanager first." >&2; exit 1; }
  [[ -n "$AVDMANAGER" ]] || { echo 'Missing avdmanager (cmdline-tools).' >&2; exit 1; }
  echo "Creating AVD $AVD_NAME from $AVD_IMAGE"
  echo no | "$AVDMANAGER" create avd --name "$AVD_NAME" --package "$AVD_IMAGE" --device pixel_8 --force >/dev/null
}
boot_emulator() {
  local serial
  if serial=$(find_running_avd); then
    export ANDROID_SERIAL=$serial
    echo "Using already running $AVD_NAME ($serial); it will be left running."
  else
    ensure_avd
    local port
    for port in $(seq 5580 2 5598); do
      if ! "$ADB" devices | grep -q "^emulator-$port"; then break; fi
    done
    echo "Booting $AVD_NAME on emulator-$port (headless)"
    "$EMULATOR" -avd "$AVD_NAME" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
      >"${TMPDIR:-/tmp}/tranthor-emulator-$port.log" 2>&1 &
    EMULATOR_PID=$!
    STARTED_EMULATOR=1
    export ANDROID_SERIAL=emulator-$port
  fi
  "$ADB" -s "$ANDROID_SERIAL" wait-for-device
  local deadline=$((SECONDS + ${TRANTHOR_BOOT_TIMEOUT:-600}))
  until [[ "$("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    if (( SECONDS > deadline )); then echo 'Emulator boot timed out.' >&2; exit 1; fi
    if [[ -n "$EMULATOR_PID" ]] && ! kill -0 "$EMULATOR_PID" 2>/dev/null; then echo 'Emulator exited during boot.' >&2; exit 1; fi
    sleep 2
  done
  # Animations make UI tests flaky on a loaded host.
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$ANDROID_SERIAL" shell settings put global "$setting" 0 >/dev/null 2>&1 || true
  done
  "$ADB" -s "$ANDROID_SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true
  echo "Emulator $ANDROID_SERIAL booted."
}
stop_emulator_if_started() {
  if [[ "$STARTED_EMULATOR" == 1 && -n "${ANDROID_SERIAL:-}" ]]; then
    "$ADB" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
    if [[ -n "$EMULATOR_PID" ]]; then
      for ((n=0; n<60; n++)); do kill -0 "$EMULATOR_PID" 2>/dev/null || break; sleep 1; done
      kill "$EMULATOR_PID" 2>/dev/null || true
    fi
    STARTED_EMULATOR=0
  fi
}
