#!/bin/bash
# Unit tests for core and push plus the public API check. JVM conformance is skipped here;
# run scripts/test-host.sh for it.
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
./gradlew testDebugUnitTest apiCheck "$@"
# FCM sender script: JWT/request construction against a local stub (no network).
node --test scripts/send-fcm-push.test.mjs
