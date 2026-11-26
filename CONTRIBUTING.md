# Contributing

Thanks for helping. This file covers how to build, test and change the SDK.

## Setup

You need JDK 17, the Android SDK with `platforms;android-36`, `build-tools;36.1.0` and `platform-tools`, and Node.js 22 or later (for the conformance mock server and the FCM sender script). Point `ANDROID_HOME` at the Android SDK, or create a git-ignored `local.properties` with `sdk.dir`.

The emulator tests also need `emulator` and `system-images;android-36;google_apis_playstore;arm64-v8a`.

## Build and test

```sh
./gradlew build                  # assemble, lint, unit tests for both modules, and the API check
scripts/test-unit.sh             # unit tests, API check and the FCM script tests
scripts/test-host.sh             # JVM conformance suite against the mock server
scripts/test-emulator.sh         # instrumented conformance suite on the tranthor-api36 emulator
scripts/test-emulator.sh --example   # plus the example app's UI tests
```

CI runs `./gradlew build`, the FCM script tests and the JVM conformance suite. The emulator tests run locally only. To run one conformance scenario, set `CONFORMANCE_SCENARIO=retry-500-doubling` before `scripts/test-host.sh`. Maintainers: [docs/device-testing.md](docs/device-testing.md) is the runbook for trying the example app on an emulator or phone.

## Conformance suite

`conformance/` is a language-neutral test suite for the event protocol: a protocol document, scenario files, a JSON schema and a mock server. Scenarios, schema and server are copied unmodified from the suite's source, so do not change them here. One runner (`tranthor-sdk/src/conformance`) is compiled into both the JVM and the instrumented tests, and both read `conformance/` directly.

The JVM adapter declares every capability except lifecycle, because a plain JVM has no `ProcessLifecycleOwner`; the instrumented adapter declares all nine and drives a real Home press. Both declare `durable-queue`: an exhausted batch is kept, and delivery pauses (see `ExhaustionPause`). The identity timings (provider timeout and provider backoff) are internal `TranthorSDK.create` parameters, which both adapters use to run the identity scenarios in real time with scaled values.

## Public API

Both library modules use Kotlin explicit API mode and track their public surface in `tranthor-sdk/api` and `tranthor-push/api`. `./gradlew apiCheck` fails when the surface changes. After an intentional change, run `./gradlew apiDump` and commit the updated `.api` files. Plumbing between the two modules carries `@InternalTranthorApi`; apps should not use it.

## Releasing

The version lives in `gradle.properties` (`VERSION_NAME`) and is also reported in each event's library context. `./gradlew publishToMavenLocal` publishes both modules to your local Maven repository for testing. Publishing to Maven Central additionally needs artifact signing, a javadoc jar and repository credentials, none of which are set up yet.

## Pull requests

Keep a change focused, add a test for new behaviour, and make sure the checks above pass. Do not commit write keys, Firebase service-account files or `google-services.json`; the example reads optional Firebase options from `local.properties`. Add a line to [CHANGELOG.md](CHANGELOG.md) for user-visible changes.
