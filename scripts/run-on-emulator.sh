#!/bin/bash
# Installs and launches the example on the tranthor-api36 emulator (booting it if needed).
#   --host URL       launch-only host override (not persisted). For the local mock use
#                    http://127.0.0.1:<port>; the port is forwarded with adb reverse.
#   --key-from-env   forward TRANTHOR_WRITE_KEY as a launch-only key (use a non-secret mock fixture;
#                    launch extras can appear in system logs). Real keys belong in the app's field.
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
usage() { sed -n '2,7p' "$0"; }
launch_host=
forward_key=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; launch_host=$2; shift 2 ;;
    --key-from-env) forward_key=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
if $forward_key && [[ -z "${TRANTHOR_WRITE_KEY:-}" ]]; then echo 'TRANTHOR_WRITE_KEY must be set for --key-from-env.' >&2; exit 2; fi
require_sdk
boot_emulator
./gradlew :example:installDebug
if [[ "$launch_host" == http://127.0.0.1:* ]]; then
  port=${launch_host##*:}; port=${port%%/*}
  "$ADB" -s "$ANDROID_SERIAL" reverse "tcp:$port" "tcp:$port" >/dev/null
  echo "Forwarded device 127.0.0.1:$port to this Mac's mock (adb reverse)."
fi
extras=()
if [[ -n "$launch_host" ]]; then extras+=(--es TRANTHOR_HOST "'$launch_host'"); fi
if $forward_key; then extras+=(--es TRANTHOR_WRITE_KEY "'$TRANTHOR_WRITE_KEY'"); fi
"$ADB" -s "$ANDROID_SERIAL" shell am start -S -n com.tranthor.example/.MainActivity "${extras[@]}" >/dev/null
echo "Example installed and launched on $ANDROID_SERIAL. Enter a workspace key only in the app's Write key field."
