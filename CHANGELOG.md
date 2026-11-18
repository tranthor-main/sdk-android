# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

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
