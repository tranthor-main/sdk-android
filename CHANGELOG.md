# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.2.0] - Unreleased

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
