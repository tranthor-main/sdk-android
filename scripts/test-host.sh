#!/bin/bash
# JVM host conformance: the real SDK (queue, HttpURLConnection, persistence) against the Node mock.
# Expected: 38 pass and the lifecycle scenario skipped (a plain JVM has no ProcessLifecycleOwner), 0 failed.
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
trap mock_stop EXIT INT TERM
if [[ -n "${CONFORMANCE_HOST:-}" ]]; then
  [[ "$CONFORMANCE_HOST" == http://127.0.0.1:* ]] || { echo 'CONFORMANCE_HOST must be an http://127.0.0.1:<port> mock origin.' >&2; exit 1; }
else
  mock_start
  export CONFORMANCE_HOST=$MOCK_HOST
fi
export CONFORMANCE_RESULTS="$ROOT/tranthor-sdk/build/conformance/jvm-results.jsonl"
status=0
./gradlew :tranthor-sdk:testDebugUnitTest -Ptranthor.conformance "$@" || status=$?
if [[ -f "$CONFORMANCE_RESULTS" ]]; then cat "$CONFORMANCE_RESULTS"; fi
exit $status
