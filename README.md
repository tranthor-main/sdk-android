# Tranthor SDK for Android

Send events from an Android app to [Tranthor](https://tranthor.com): identify customers, track what they do, and let Tranthor message them. Events are written to disk first and delivered in batches with retries, so a flaky network does not lose them. An optional module adds push notifications over Firebase Cloud Messaging.

- `tranthor-sdk` (package `com.tranthor.sdk`): events, identity, consent and delivery.
- `tranthor-push` (package `com.tranthor.push`): FCM token registration, notification display, and receive, open and dismiss reporting. See [docs/push.md](docs/push.md).

## Requirements

Android 6.0 (API 23) or later. The libraries use Kotlin coroutines and AndroidX `lifecycle-process`. Building from source needs JDK 17 and the Android SDK with platform 36.

## Install

The artifacts are not on Maven Central yet. Until they are, use the modules from source.

Depend on these coordinates:

```kotlin
dependencies {
    implementation("com.tranthor:tranthor-sdk:0.2.0")
    implementation("com.tranthor:tranthor-push:0.2.0") // optional
}
```

To build from a checkout, add it to your `settings.gradle.kts` as a composite build. Gradle then substitutes the dependencies above with the checkout's modules. Use the path to wherever you cloned this repository; the directory name is up to you:

```kotlin
includeBuild("path/to/your/checkout")
```

Or publish to your local Maven repository with `./gradlew publishToMavenLocal` and add `mavenLocal()` to your repositories.

## Quick start

The Kotlin API is made of `suspend` functions, so call it from a coroutine. Java code uses `TranthorClient`.

```kotlin
import androidx.lifecycle.lifecycleScope
import com.tranthor.sdk.Configuration
import com.tranthor.sdk.TranthorSDK
import com.tranthor.sdk.jsonObjectOf
import kotlinx.coroutines.launch

lifecycleScope.launch {
    val sdk = TranthorSDK.configure(applicationContext, Configuration(writeKey = "<your write key>"))
    sdk.identify(userId = "customer-123", traits = jsonObjectOf("plan" to "trial"))
    sdk.track("Opened", properties = jsonObjectOf("source" to "app"))
    sdk.screen("Settings")
    sdk.flush()
}
```

`writeKey` is the value from your workspace settings and is sent as the `Authorization` header exactly as given. Create one instance and keep it for the life of the app, for example in your `Application`; the snippet above shows the calls, not where to hold the instance. Event methods return once the event is on disk, not when it is delivered.

`TranthorClient` admits calls in order and reports results to optional callbacks:

```java
Configuration configuration = new Configuration.Builder(writeKey).build();
TranthorClient.configure(context, configuration, (client, error) -> {
    if (client != null) client.track("Opened", Collections.singletonMap("source", "app"));
});
```

## Methods

| Method | What it does |
| --- | --- |
| `configure(context, configuration)` | Loads stored state, starts delivery and returns the instance. Throws `InvalidHost`, `NamespaceInUse` or `StoreTemporarilyUnavailable`. |
| `identify(userId, traits, ...)` | Records traits and sets the signed-in user. |
| `track(event, properties, ...)` | Records an event. |
| `page(name, ...)`, `screen(name, ...)` | Record a named page or screen view. Nothing is tracked automatically. |
| `flush()` | Waits until queued events are delivered. Throws `DeliveryFailure`, `FlushCancelled` or `Stopped`. |
| `reset()` | Signs the user out and starts a new anonymous ID. Events already queued keep their identity. |
| `optOut()`, `optIn()` | Withdraw or restore consent. |
| `shutdown()` | Stops without flushing. Pending events stay on disk for the next instance. |
| `anonymousId`, `userId`, `isOptedOut` | The current state. |

`identify`, `track`, `page` and `screen` also accept `messageId`, `timestamp` and `context` to override the generated values. Properties and traits are `JsonValue`s; `jsonObjectOf` converts ordinary Kotlin values. Numbers are doubles, so send identifiers above 2^53 as strings. An event may nest at most 64 JSON levels deep; deeper payloads throw `InvalidPayload`.

## Identity

Before `identify`, events carry a stored anonymous ID. `identify` with a user ID sends both IDs, so Tranthor can attach earlier anonymous activity to the user. After that, events carry only the user ID. If a different user identifies on the same device, the anonymous ID is replaced first, so the new user does not inherit the previous user's history. An empty user ID is ignored.

## Consent

`optOut()` is saved to disk, cancels delivery and discards pending events. Calls made while opted out are ignored, and `flush()` returns without a request. If queued events could not be deleted, `optIn()` deletes them first, so they are never sent, and throws `StoreTemporarilyUnavailable` if it still cannot. `optIn()` resumes collection. The one exception is push: if a phone is registered, a single removal is still delivered so Tranthor stops sending to it.

## Configuration

| Option | Default | Meaning |
| --- | --- | --- |
| `writeKey` | required | Authorization header value. Never logged. |
| `host` | `https://api.tranthor.com` | Origin to send to. `configure` throws `InvalidHost` for anything but an HTTP(S) origin. |
| `batchSize` | 5 | Events per request. |
| `flushIntervalMs` | 500 | Wait after the first queued event before sending. |
| `retries` | 5 | Extra attempts after a retryable failure. |
| `baseDelay` | 500 | First retry delay in milliseconds; it doubles up to 30 seconds. |
| `maxQueueSize` | 10,000 | Pending events kept. The oldest ordinary event is dropped first and reported to `onError`. |
| `automaticContext` | true | Adds library, OS, device, app, screen, locale and time zone to each event. Your own `context` keys replace them. |
| `storageNamespace` | `default` | Names the on-disk queue. Two live instances cannot share a namespace in one process. |
| `storageDirectory` | `noBackupFilesDir/TranthorSDK` | Base directory for stored state and queued events. |
| `onError` | none | Called once for each batch that fails for good or is dropped, with its events, HTTP status (null for network errors) and attempts. |

## Delivery

Events are sent as JSON to `/api/public/apps/batch`, one request at a time. Network errors and HTTP 408, 425, 429 and 5xx are retried with backoff, honouring `Retry-After`; other statuses are final, and a batch that fails for good is dropped and reported to `onError`.

Running out of retries never drops events. The batch stays first in the queue, `onError` is not called, and automatic delivery pauses for 30 seconds, then 60, 120, 240 and at most 300 seconds after each further batch that fails all its attempts. During the pause the interval timer, new events and a full batch start no attempt. `flush()` and the app returning from the background try again at once, and `flush()` throws the failure it waited for. A delivered batch resets the pause to 30 seconds, and an app that restarts begins without a pause and sends after the normal flush interval. Events leave the queue only when the server accepts them, when the server refuses them with a final status, on `optOut()`, or when the queue is full.

Each queued event is saved to its own small file in a `queue` folder inside `storageDirectory` before the event call returns, so queued events survive a restart and an event costs the same however long the queue is. Events saved by 0.1.0 are moved into that folder the first time 0.2.0 starts. When the app goes to the background the SDK sends once and pauses if that fails; it resumes when the app returns. Android gives no guarantee that a background process keeps running, so anything undelivered is sent on the next launch.

The push token never appears in `onError`: it is replaced by `"<redacted>"`.

## Push

`tranthor-push` registers the phone's FCM token while a customer is signed in, shows Tranthor's push notifications and reports when they are received, opened or dismissed. Setup, the payload format and notification permission are in [docs/push.md](docs/push.md).

## Current limits

- No compression, alias or group calls, and no automatic screen tracking.
- No cross-process lock. An app with several processes needs a `storageNamespace` per process.

## Contributing and licence

See [CONTRIBUTING.md](CONTRIBUTING.md) to build and test, and [CHANGELOG.md](CHANGELOG.md) for releases. Report security issues as described in [SECURITY.md](SECURITY.md). Licensed under the [MIT License](LICENSE).
