#!/bin/bash
# JVM host conformance: the real SDK (queue, HttpURLConnection, persistence) against the Node mock.
# Expected on suite 1.6.0: 65 validated, 61 passed, 0 failed, 4 skipped. The skips are lifecycle-background-flush (a plain JVM has no
# ProcessLifecycleOwner) and the three scenarios that durable-queue supersedes: retries-exhausted,
# interval-after-retries-exhausted and retry-dropped-connection-exhausted.
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
