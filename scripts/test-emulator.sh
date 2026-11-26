#!/bin/bash
# Instrumented conformance (every scenario of suite 1.5.0, lifecycle via the real ProcessLifecycleOwner) on the
# dedicated AVD tranthor-api36. Creates/boots the AVD if needed, routes the 127.0.0.1-only mock with
# adb reverse, always stops the mock and removes the reverse, and stops the emulator only if this
# script started it. Local-only: CI does not run emulator tests.
#   --example   also run the example app's UI tests against the same mock.
#   CONFORMANCE_SCENARIO=<id> to diagnose one scenario (all files are still validated).
#   TRANTHOR_EMULATOR_ROUTE=alias uses the emulator's host alias http://10.0.2.2:P instead of
#   adb reverse + 127.0.0.1 (emulator only; physical devices need adb reverse).
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
run_example=false
for arg in "$@"; do
  case "$arg" in
    --example) run_example=true ;;
    --help|-h) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "Unknown argument $arg" >&2; exit 2 ;;
  esac
done
require_sdk
reversed=
cleanup() {
  if [[ -n "$reversed" ]]; then "$ADB" -s "$ANDROID_SERIAL" reverse --remove "tcp:$reversed" >/dev/null 2>&1 || true; fi
  mock_stop
  stop_emulator_if_started
}
trap cleanup EXIT INT TERM
boot_emulator
mock_start
device_host=$MOCK_HOST
if [[ "${TRANTHOR_EMULATOR_ROUTE:-reverse}" == alias ]]; then
  device_host="http://10.0.2.2:$MOCK_PORT"
else
  "$ADB" -s "$ANDROID_SERIAL" reverse "tcp:$MOCK_PORT" "tcp:$MOCK_PORT" >/dev/null
  reversed=$MOCK_PORT
fi
"$ADB" -s "$ANDROID_SERIAL" logcat -c || true
args=(-Pandroid.testInstrumentationRunnerArguments.conformanceHost="$device_host")
if [[ -n "${CONFORMANCE_SCENARIO:-}" ]]; then args+=(-Pandroid.testInstrumentationRunnerArguments.conformanceScenario="$CONFORMANCE_SCENARIO"); fi
if [[ -n "${RACE_ITERATIONS:-}" ]]; then args+=(-Pandroid.testInstrumentationRunnerArguments.raceIterations="$RACE_ITERATIONS"); fi
status=0
./gradlew :tranthor-sdk:connectedDebugAndroidTest "${args[@]}" || status=$?
echo '--- conformance JSON lines (logcat tag TranthorConformance) ---'
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v raw -s TranthorConformance:I | grep -E '^\{|^validated' || true
if $run_example && [[ $status == 0 ]]; then
  ./gradlew :example:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.tranthorHost="$device_host" || status=$?
fi
exit $status
