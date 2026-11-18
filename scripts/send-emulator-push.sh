#!/bin/bash
# Simulates a Tranthor push on the running example (debug build). It BYPASSES FCM, Google Play
# services, Firebase credentials and any server: it cannot prove real remote delivery. For real FCM
# use scripts/send-fcm-push.mjs (see docs/push.md "Real FCM on the emulator").
#   (default)          deliver a simulated FCM data message through the debug-only SimulatedPushReceiver,
#                      which feeds the same handler as the app's FirebaseMessagingService (posts a
#                      notification and reports Push Received, foreground or background).
#   --image-url URL    add imageUrl to the marker: the app downloads it through the real allowlisted
#                      rich-image path (HTTPS, host in local.properties tranthor.example.imageHosts).
#   --open             simulate the user tapping the notification (reports Push Opened).
#   --dismiss          simulate the user dismissing it (reports Push Dismissed).
#   --delivery-id ID   defaults to emulator-preview-1 (repeats dedupe within one app process).
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
interaction=
delivery=emulator-preview-1
image=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --open) interaction=opened; shift ;;
    --dismiss) interaction=dismissed; shift ;;
    --image-url) [[ $# -ge 2 ]] || exit 2; image=$2; shift 2 ;;
    --delivery-id) [[ $# -ge 2 ]] || exit 2; delivery=$2; shift 2 ;;
    --help|-h) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "Unknown argument $1" >&2; exit 2 ;;
  esac
done
[[ "$delivery" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] || { echo 'Invalid delivery ID.' >&2; exit 2; }
if [[ -n "$image" && ! "$image" =~ ^https://[A-Za-z0-9.-]+(:443)?/[A-Za-z0-9._~/%+=\&?-]*$ ]]; then
  echo 'Image URL must be a plain https:// URL (no quotes, spaces or fragments).' >&2; exit 2
fi
require_sdk
serial=${ANDROID_SERIAL:-$(find_running_avd || true)}
[[ -n "$serial" ]] || { echo "Start the example on $AVD_NAME first (scripts/run-on-emulator.sh)." >&2; exit 1; }
if [[ -n "$image" ]]; then
  marker="{\"version\":1,\"deliveryId\":\"$delivery\",\"imageUrl\":\"$image\"}"
else
  marker="{\"version\":1,\"deliveryId\":\"$delivery\"}"
fi
receiver=com.tranthor.example/.debug.SimulatedPushReceiver
if [[ -n "$interaction" ]]; then
  "$ADB" -s "$serial" shell am broadcast -n "$receiver" --es tranthor "'$marker'" --es interaction "$interaction" >/dev/null
  echo "Simulated notification $interaction sent (bypasses FCM)."
else
  "$ADB" -s "$serial" shell am broadcast -n "$receiver" \
    --es tranthor "'$marker'" --es title "'Tranthor emulator push'" --es body "'Simulated locally with adb; tap to test opened.'" >/dev/null
  echo 'Simulated push delivered to the debug receiver (bypasses FCM). This does not verify FCM or backend delivery.'
fi
