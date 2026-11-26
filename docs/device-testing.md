# Example app device testing (maintainer runbook)

This runbook is for maintainers of the SDK; it is not a guide for using the SDK in your app (see the [README](../README.md)). The Compose example (`example/`, application ID `com.tranthor.example`) uses the local modules and Android 7.0 (API 24) or later. The emulator flow below is the one the automated checks use, on the `tranthor-api36` AVD (Android 16, API 36, Google Play arm64 image). The physical-device steps are optional.

## Emulator first

Requirements: JDK 17, Node.js 22+, and an Android SDK with `platforms;android-36`, `build-tools;36.1.0`, `platform-tools`, `emulator` and `system-images;android-36;google_apis_playstore;arm64-v8a`. Set `ANDROID_HOME` (the scripts also look in common locations).

```sh
scripts/run-on-emulator.sh
```

The script creates the dedicated AVD `tranthor-api36` if it is missing, boots it headless (or reuses it if it is already running), installs the debug build and launches it. Open the emulator window from Android Studio's Device Manager, or run `emulator -avd tranthor-api36` yourself first if you want a visible window; the script reuses a running instance.

For a non-secret local fixture, start the unchanged mock in one terminal:

```sh
node conformance/server/cli.cjs --port 43189
```

and launch the example from another:

```sh
TRANTHOR_WRITE_KEY=conformance-fixture scripts/run-on-emulator.sh --host http://127.0.0.1:43189 --key-from-env
curl --silent http://127.0.0.1:43189/control/requests
```

`--host http://127.0.0.1:P` also runs `adb reverse tcp:P tcp:P`, so the app's 127.0.0.1 reaches the host's loopback-only mock. (`http://10.0.2.2:P`, the emulator's alias for the host's loopback, also works on the emulator without adb reverse, but not on a physical device.) Launch host/key extras are honoured only in debug builds, only for loopback hosts (127.0.0.1, localhost, 10.0.2.2) and only as a pair; a launch host is never combined with a saved key, because any installed app can start the exported launcher activity with extras. The example's network security config allows cleartext only to 127.0.0.1 and 10.0.2.2; everything else must be HTTPS. Stop the mock with Ctrl-C.

`--key-from-env` is opt-in and meant for mock fixtures: the key travels as a launch-only intent extra (`TRANTHOR_WRITE_KEY`), is never persisted, and can appear in system logs, so never use it with a real key. `TRANTHOR_HOST` and `TRANTHOR_FLUSH_INTERVAL_MS` extras work the same way and take precedence over saved values. Editing a field manually makes its value eligible for saving.

## Configure and find your test events

Use a disposable test user, for example `android-example-yourname-date`. In your Tranthor workspace **Settings**, find the app's **write key**. Type it only into the app's **Write key** field (it is masked). Do not put it in a terminal, launch extras, screenshots, tickets or logs. Keep the default host `https://api.tranthor.com` for a normal workspace; tap **Configure**, enter the disposable user ID and tap **Identify**.

Manually entered keys are encrypted with a non-exportable AES-256-GCM key in the Android Keystore; only ciphertext and IV are stored in app-private preferences, and the app disables backup. The host is stored in app preferences. A saved key auto-configures the SDK on relaunch, restoring identity, consent and pending events. **Reconfigure** shuts down the old instance without flushing, then restores its durable namespace. Use the same workspace/host while events are pending: changing credentials can deliver pending events to the new workspace. Opt out to discard pending events deliberately before switching. **Forget key** deletes the saved ciphertext and the Keystore key and clears the field; uninstalling the app also removes both. Reset identity changes identity only.

Track, burst, Page and Screen attach `properties` with `liveTest: true`, the per-launch UUID `runId` and `source: "android-example"`. Identify has no properties argument, so the same tags appear in its **traits and context**. Identify first to associate later events with the disposable user.

Open the Tranthor dashboard **Events** page and filter by the exact **userId**. Look for **Android Example Tagged Event** with `liveTest`, `runId` and `source`. The live panel shows identity, consent, the last flush result and terminal error callbacks (event count, attempts, error). It shows no queue count because the SDK exposes none. "Accepted" log entries mean local acceptance, not delivery. **View log → Copy** copies the last 200 lines; keys and event bodies are never logged. An empty or opted-out Flush also succeeds, so check the Events page for ingestion.

To clean up, delete/erase the disposable user (including associated events where offered) from the workspace's users list; if your role lacks permission, ask an administrator. Resetting the SDK identity is not server-side erasure.

## Checklist

- **First event:** Identify, **Track (no auto flush)**, Flush. Expect "Last flush: Succeeded" and one tagged track for the disposable user.
- **Burst:** tap Track burst (25 by default), then Flush. Expect 25 tracks in batches of up to five under one run ID.
- **Short outage / retry:** turn **Slow flush** off (Flush interval 500 ms). Enable Airplane mode on the emulator (or phone), tap Track, then disable Airplane mode **within about 10 seconds**. Do not tap Flush: the SDK's retries deliver the event. If the outage outlasts the retries, the event stays queued and delivery pauses for 30 seconds, then 60, 120, 240 and 300; Flush or the app returning from the background tries again at once.
- **Relaunch recovery:** in Airplane mode, Track one or two events and force-stop the app promptly (`adb shell am force-stop com.tranthor.example` or Settings), relaunch, reconnect and Flush. The pending items keep their original run ID and stamps; restart resets the retry attempt count and the pause. Only batches the server refused for good are gone.
- **Consent:** offline, enqueue events, then Opt out. Pending events are discarded without error callbacks; Flush succeeds without a request; new calls are discarded until Opt in. Relaunch to check persisted consent.
- **Background flush:** enable **Slow flush (60 s)**, tap Track once and press Home immediately. The event arrives within seconds (ProcessLifecycleOwner `ON_STOP`, about 700 ms after the activity stops), not after 60 s. Android gives no background execution guarantee: the process can be frozen or killed after Home, in which case the event is delivered on the next launch.
- **Reset:** note both IDs, Reset identity: no user ID and a new anonymous ID.
- **Error observation:** during a long offline period no error is reported for the queued events and nothing is dropped; Flush reports the failure and the events stay queued. A batch the server refuses (for example with a 400) is reported with count and attempts and then dropped.
- **Push preview:** see [Push previews on the emulator](#push-previews-on-the-emulator). Local previews and simulated pushes bypass FCM.

## Automated local checks

```sh
scripts/test-emulator.sh             # instrumented conformance, 65 scenarios
scripts/test-emulator.sh --example   # plus the example UI tests (Track/Flush, push previews, Home flush)
```

`test-emulator.sh` boots or reuses `tranthor-api36`, starts the mock on a free loopback port, forwards it with adb reverse, runs the tests through Gradle (`ANDROID_SERIAL` selects the AVD even if other devices are attached), prints the conformance JSON lines from logcat, always stops the mock and removes the reverse rule, and stops the emulator only if it started it. The example UI tests launch the app with a slow 60 s interval and a launch-only mock fixture key, then verify ingestion exclusively through `/control/requests`. These checks are local-only; CI runs unit tests and JVM host conformance.

## Optional: physical phone (unverified)

1. Enable **Developer options** (tap Build number seven times) and **USB debugging**, connect the phone, and accept the RSA fingerprint prompt. `adb devices` must list it as `device`.
2. `export ANDROID_SERIAL=<serial from adb devices>`, then `./gradlew :example:installDebug` and `adb shell am start -n com.tranthor.example/.MainActivity`.
3. For the local mock, `adb reverse tcp:43189 tcp:43189` and use host `http://127.0.0.1:43189` (no LAN exposure needed). For a real workspace, enter the key in the app as above.

No Google account, signing setup or developer program is needed for a debug install. OEM battery optimizations (for example aggressive app standby) can change background-flush timing; Doze, the cached-app freezer and process death are not covered by the automated tests. Real FCM delivery additionally needs a Firebase project (options in `local.properties`) and a sender; see Real FCM on the emulator below.

## Push previews on the emulator

The example works without a `google-services.json`: FCM is disabled and the push section says so, but local notification previews and simulated pushes work. To try real FCM, put your Firebase options in the ignored `local.properties` (`tranthor.firebase.applicationId`, `apiKey`, `projectId`, `senderId`); the example then initializes Firebase manually, fetches a token and queues `Installation Registered` for it through the SDK.

```sh
node conformance/server/cli.cjs --port 43189
TRANTHOR_WRITE_KEY=conformance-fixture scripts/run-on-emulator.sh --host http://127.0.0.1:43189 --key-from-env
```

Scroll to **Push preview**, tap **Request notification permission** and allow it. **Preview local notification** posts a real local notification after two seconds; when it appears while the app is visible, the SDK queues `Push Received`. Press Home before it fires and tap it to test `Push Opened`; swipe it away for `Push Dismissed`. **Preview notification with image** attaches the bundled test image with no network request.

To simulate a remote push while the app runs:

```sh
scripts/send-emulator-push.sh                 # simulated FCM data message -> notification + Push Received
scripts/send-emulator-push.sh --open          # simulated tap -> Push Opened
scripts/send-emulator-push.sh --dismiss       # simulated swipe-away -> Push Dismissed
scripts/send-emulator-push.sh --delivery-id emulator-2 \
  --image-url https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png
curl --silent http://127.0.0.1:43189/control/requests
```

The default mode sends an `adb shell am broadcast` to a **debug-only** receiver (`SimulatedPushReceiver`, restricted to the shell by the `DUMP` permission) that feeds the same handler as the app's `FirebaseMessagingService`. `--image-url` adds `imageUrl` to the marker, so the app runs the real allowlisted HTTPS download and shows a BigPicture notification; the host must be listed in `local.properties` `tranthor.example.imageHosts` (for the URL above: `tranthor.example.imageHosts=www.google.com`), otherwise the text-only notification is shown. `--open`/`--dismiss` report the interaction directly through the same receiver: real taps are verified intents that adb cannot forge, and `am start` with a `tranthor` extra is deliberately ignored by the app. **Both bypass FCM, Google Play services, credentials, backend targeting and real-device delivery; they cannot prove those paths.** The fixed delivery ID dedupes repeated injections in one process; use `--delivery-id` or the local preview for a fresh ID. Notification observations are the SDK's own reserved `Push Received` / `Push Opened` / `Push Dismissed` events with only `deliveryId` (the example's `liveTest`/`runId` tags apply to its Track/Page/Screen buttons, not to these).

`scripts/test-emulator.sh --example` verifies the Track/Flush path, the local preview's `Push Received`, a real notification tapped from the shade after Home, the simulated receive/open path and the Home-press lifecycle flush, all through the mock's `/control/requests`. Real FCM delivery, process-death cold-start taps and OEM behaviour remain unverified.

## Real FCM on the emulator

The Google Play emulator image (`tranthor-api36`) can receive real FCM messages; no phone is needed. This proves FCM token issuance, delivery through Google Play services into the app's `FirebaseMessagingService`, the data-message handling, the real remote image download and the resulting Installation and Push events at the mock. It does **not** prove the Tranthor server's sender or Installation registry (run against a real Tranthor host for that), delivery to physical devices, or Doze and background-restriction behaviour.

1. In the [Firebase console](https://console.firebase.google.com/), create a project (or use a test one) and add an **Android app** with package name `com.tranthor.example`. You do not need to download `google-services.json`; open the app's settings instead.
2. Copy its options into the repository's git-ignored `local.properties`:
   ```properties
   tranthor.firebase.applicationId=1:1234567890:android:abc123   # "App ID"
   tranthor.firebase.apiKey=AIza...                              # "Web API key"
   tranthor.firebase.projectId=your-project-id
   tranthor.firebase.senderId=1234567890                         # "Project number"
   tranthor.example.imageHosts=www.google.com                    # exact HTTPS image hosts, comma separated
   ```
3. Create a service account key: **Project settings → Service accounts → Generate new private key**. Store the JSON **outside the repository** (for example `~/secrets/fcm-test.json`, `chmod 600`). Never commit, paste or log it; the script never prints it, its private key or the OAuth token.
4. Start the mock and the example (debug build):
   ```sh
   node conformance/server/cli.cjs --port 43189
   TRANTHOR_WRITE_KEY=conformance-fixture scripts/run-on-emulator.sh --host http://127.0.0.1:43189 --key-from-env
   ```
   Allow notifications in **Push preview**. The status line should say Firebase is configured and an FCM token was received. Tap **Copy FCM token (debug)** (debug builds only; the token is never logged or displayed), then `export FCM_TOKEN=$(pbpaste)` on macOS (the copied clip is flagged sensitive, `ClipDescription.EXTRA_IS_SENSITIVE`, so Android 13+ does not preview it): a windowed emulator shares its clipboard with the host (Extended controls → Settings → "Enable clipboard sharing", on by default). A headless emulator does not share the clipboard; run it with a window for this step.
5. Send a data message (the default), with and without an image:
   ```sh
   export FCM_SERVICE_ACCOUNT=~/secrets/fcm-test.json
   node scripts/send-fcm-push.mjs --title "Hello" --body "From FCM"
   node scripts/send-fcm-push.mjs --title "With image" \
     --image-url https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png
   node scripts/send-fcm-push.mjs --notification --title "Tray message"
   ```
   The script (Node 22+, no dependencies) signs an RS256 JWT with `node:crypto`, exchanges it for an OAuth token (scope `firebase.messaging`) and calls FCM HTTP v1 `projects/<project_id>/messages:send` with `android.priority=high` and the `tranthor` data key exactly as this module expects (`--delivery-id` defaults to a random ID). It reads the device token from `FCM_TOKEN` or `--token-file` (`--token` works but warns: it is visible in `ps` and shell history), warns if the key file is readable by other users or sits inside the repository (`.gitignore` also excludes `*service-account*.json` and `fcm-*.json`), and only talks to `https://fcm.googleapis.com` (an `FCM_API_BASE` override must be a loopback `http://` stub). It prints only the FCM message name or the error status/message. `android.priority` is `"high"` (FCM v1 `AndroidConfig.priority` accepts `"normal"`/`"high"`). Foreground or background, the SDK queues `Push Received`; tap the notification for `Push Opened`. `--notification` sends a notification message: Android's tray renders it without running app code while the app is backgrounded (no allowlisted image download), and its tap is an unverified launcher intent that the example does not count as an open (see Opened in the [push guide](push.md)).
6. Read what reached the mock: `curl --silent http://127.0.0.1:43189/control/requests`. You should see `Installation Registered` (with `platform`, `appId`, `token`, `permissionState`, versions) and, per push, `Push Received`/`Push Opened` with the `deliveryId` and the deterministic UUIDv8 `messageId`.

Without Firebase options the example stays usable: FCM is disabled with a status message, while local previews and `send-emulator-push.sh` still work.
