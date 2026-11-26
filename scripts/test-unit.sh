#!/bin/bash
# Unit tests for core and push plus the public API check. JVM conformance is skipped here;
# run scripts/test-host.sh for it.
source "$(dirname "$0")/lib.sh"
cd "$ROOT"
./gradlew testDebugUnitTest apiCheck "$@"
# FCM sender script: JWT/request construction against a local stub (no network).
node --test scripts/send-fcm-push.test.mjs
# Mock server for the conformance suite, including its identity-token support.
node --test conformance/server/server.test.cjs conformance/server/identity.test.cjs
