# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Identity verification. `identify(userId, identityToken = …)` and `Configuration.identityTokenProvider` (Java: `Builder.identityTokenSupplier`) send a customer-signed identity token with each batch, for its own user, renewed through the provider before it expires. Tokens are saved in the state file. Details are in the README.
- `onNotice` reports events the server dropped or could not verify (read from the `x-tranthor-dropped-events` and `x-tranthor-unverified-events` response headers, once per response), an identity token the server refused, and unavailable identity settings.
- `DeliveryFailure.code`, the server's error code for a 403 or 503.

### Changed

- `x-tranthor-app-id` is now sent on every request: the application's package name, or `Configuration.appId` when you set it. An `appId` that cannot be an HTTP header value (empty, spaces at either end, a newline, non-ASCII) makes `configure` throw `InvalidAppId`; the SDK never sends an id you did not choose.
- When identity is in use (a provider is set, or an identity token was ever passed to `identify`), a `403 identity_not_verified` holds the current user's batch instead of dropping it, and a request carries at most 10 users. An app that never uses identity tokens keeps the old behaviour: the 403 is reported to `onError` and the batch dropped.
- A `503 identity_settings_unavailable` or `identity_secrets_unavailable` is now held for `Retry-After` for every app, in use or not, because it is a server outage and never a reason to drop. It is not counted against `retries`.
- An `Installation Removed` (opt-out or sign-out) is never dropped for lack of a valid token: if the server refuses it and one renewal does not help, it stays, is retried every 60 seconds and at the next launch, and each failed attempt is reported to `onError`.
- `TranthorSDK.identify` has a new last parameter, `identityToken`. Binary compatibility is kept for it: the old compiled signature and its default-argument bridge stay as a hidden deprecated overload, so code compiled against 0.2.x still links. `Configuration` gained three trailing parameters; the 11-argument constructor Java uses is unchanged, but Kotlin code compiled against 0.2.x that relies on default arguments must be recompiled (it needs no source change), because a hidden overload for that constructor would clash with the one Java uses. This is accepted before 1.0.

### Fixed

- Example app: the Push section said real FCM delivery was not available. It now says what real FCM pushes need: your Firebase options in `local.properties` and the Firebase key added in Tranthor. The SDK itself is unchanged.

## [0.2.1] - 2026-11-18

### Changed

- The SDK is now covered by the Tranthor SDK License instead of the MIT License. You may include it in your apps to connect them to Tranthor; you may not redistribute it on its own or use it with other services. Versions 0.1.0 and 0.2.0 stay under the MIT License.

## [0.2.0] - 2026-11-18

### Changed

- Events are kept while the network is down and sent when it returns, however long that takes. Before, a batch was dropped after its retries ran out (about 15 seconds with the defaults).
- When all attempts for a batch fail, delivery pauses for 30 seconds, then 60, 120, 240 and at most 300 seconds. A delivered batch resets the pause. During the pause the interval timer, new events and a full batch start no attempt; `flush()` and the app returning from the background try again at once. A restarted app starts without a pause and sends after the normal flush interval.
- `onError` is no longer called when retries run out, because nothing is dropped then. It is still called for a batch the server refuses with a final status (any status other than 408, 425, 429 and 5xx), and for events dropped because the queue is full (`attempts` is 0). `flush()` still throws the failure.
- Queued events are stored one small file each, in a `queue` folder inside the storage directory, instead of inside one state file. Saving an event now costs the same however many events are queued. The files stay in the same app-private folder, excluded from backup like the state file. A queued event file that was read but cannot be decoded is renamed to `.corrupt` and skipped; the others are still sent. A file that cannot be read at all is a temporary storage failure: `configure` throws `StoreTemporarilyUnavailable` and the file stays where it is.
- If queued events cannot be deleted during `optOut()`, they are deleted again by `optIn()` before collection resumes and are never sent; `optIn()` throws `StoreTemporarilyUnavailable` and the user stays opted out if they still cannot be deleted.
- Events saved by 0.1.0 are moved to the new folder automatically the first time 0.2.0 starts, in their original order and with their original IDs and timestamps. Do not downgrade to 0.1.0 afterwards: it cannot read the new state file.

## [0.1.0] - Unreleased

First release.

### Added

- `tranthor-sdk`: identify customers and record track, page and screen events from Android (API 23 and later), with Kotlin coroutine and Java APIs.
- Events are saved to disk before a call returns and delivered in batches with retries and backoff, so they survive restarts and short network outages.
- Identity linking: a login identify ties earlier anonymous activity to the user, and a different user on the same device starts with a new anonymous ID.
- Persistent opt-out and opt-in.
- Delivery follows the app lifecycle: events are sent when the app goes to the background and resume when it returns.
- `onError` reports batches that fail for good or are dropped, with push tokens redacted.
- `tranthor-push`: register the phone's FCM token while a customer is signed in, show Tranthor's push notifications (including images from hosts you allow), and report received, opened and dismissed notifications.
- A Compose example app and an in-repo mock server for trying both modules on an emulator.
