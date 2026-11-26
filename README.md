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
    implementation("com.tranthor:tranthor-sdk:0.2.1")
    implementation("com.tranthor:tranthor-push:0.2.1") // optional
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
| `identify(userId, traits, ..., identityToken)` | Records traits and sets the signed-in user. With [identity verification](#identity-verification), pass the user's identity token. |
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

## Identity verification

A write key ships inside your app, so anyone can copy it and send events for any user. **Identity verification** closes that: your own backend signs a short-lived identity token (an HS256 JWT whose `sub` is the user ID) for the signed-in user, the app hands it to the SDK, and Tranthor checks it. The SDK holds no secret and never verifies a token; it reads only the `exp` claim, to renew a token before it expires. Set the mode, the signing secrets and the rollout in the Tranthor dashboard (Off, Monitor, then Required).

```kotlin
val config = Configuration(
    writeKey = "Basic …",
    // Called for the current user when a token is missing, about to expire or refused. Return null for "no token".
    identityTokenProvider = { userId -> myBackend.identityTokenFor(userId) },
)
lifecycleScope.launch {
    val sdk = TranthorSDK.configure(applicationContext, config)
    sdk.identify(userId = "user-1", identityToken = tokenFromSignIn)
}
```

From Java, use `Builder.identityTokenSupplier(userId -> …)` (blocking, run on a background thread) and `client.identifyWithIdentityToken(userId, traits, identityToken)` (a different name so that `identify(userId, traits, null)` keeps meaning the overload with a callback). The SDK abandons a provider call after 20 seconds but cannot interrupt a blocked thread, and it starts no new call while an abandoned one is still running, so make the supplier cancellable: give its network call a timeout well under 20 seconds.

How it works:

- `identify(userId, identityToken = …)` caches the token for that user and saves it with the queue. It is sent with the events, one per user in the batch, never inside an event, and only for its own user. It is never logged or passed to `onError`/`onNotice`.
- Tokens are saved in the state file under `noBackupFilesDir` (excluded from backup) with the same protection as the queue; if you set a custom `storageDirectory`, protecting it is up to you. A token is kept while its user is signed in or still has events waiting, then removed; an expired token that nothing needs is removed too, and opting out removes the signed-in user's token once nothing queued needs it. `reset()` keeps the outgoing user's token until their queued events (including a push `Installation Removed`) are sent.
- Before a batch is sent, the current user's token is renewed through `identityTokenProvider` when it has less than 60 seconds left. The provider runs off the SDK's own thread and the SDK waits at most 20 seconds for it. A send waits for the provider only until it answers or 20 seconds have passed: a provider that throws or returns null at once does not delay it, a slow one delays it by the time it takes (at most 20 seconds), and one that hangs delays it by 20 seconds, once. After any failure the provider is not called again for 60 seconds. Whatever happens the batch is then sent with the tokens the SDK has, because the server decides: a broken provider never keeps data back from a workspace that does not require tokens.
- **When the server refuses a token.** Identity counts as **in use** when you set `identityTokenProvider` or have ever passed a token to `identify` on this install (remembered in the state file, even after a refusal deletes the cached token). In use: the refused token is dropped from the cache. Events of a user who is no longer signed in are removed from the batch and reported once to `onError` (message `identity not verified`); the rest is sent. For the signed-in user the SDK renews the token through the provider and sends the batch again, once. If no token can be had, or the renewed token is refused too, the batch is **held**: it stays at the head of the queue with its events, order and timestamps, automatic delivery pauses for 60 seconds, and then it tries again. Each such attempt calls `onNotice` with `IDENTITY_REFUSED` and the server's `reason`. Other users' events in that batch are sent first; events queued behind the held batch wait for it. While held, the queue keeps at most 1,000 events (the oldest are reported to `onError`). `flush()` ends the pause, tries again at once and throws the `DeliveryFailure`. A hold is not saved: a restart starts without one. An `identify` with a fresh token, or for a different user, ends it at once. Never in use (no provider, no token ever passed): the old behaviour, the refusal is reported to `onError` and the batch dropped, not held.
- **When the server refuses the write key itself** (for example because it is limited to other apps), that is a configuration error: the batch is dropped and reported to `onError`, as before. `DeliveryFailure.code` carries the server's error code when it sends one.
- **When the server cannot read its identity settings,** the batch is held and tried again after the delay the server asks for (30 seconds if it names none, at most 5 minutes), and is never dropped or counted against `retries`. An opt-out or sign-out removal is the exception to the hold: it keeps its usual retries and is never dropped either (see the removals bullet). This holds for every app, whether or not it uses identity tokens, because it is a server outage and never a reason to drop events. `onNotice` gets `IDENTITY_UNAVAILABLE`.
- **Opt-out and sign-out removals.** The push `Installation Removed` that an opt-out still delivers, and the one `reset()` queues under the outgoing user, use that user's cached token (the provider is asked for one when it is missing or nearly expired). A removal is **never dropped for lack of a valid token**: in Required the server refuses an unverified removal, and dropping it would leave the phone registered and receiving push after the person opted out. If it is refused and one renewal does not fix it, it stays: an opt-out removal waits, is retried every 60 seconds and at the next launch, and each failed attempt is reported to `onError` (its push token redacted). It stays until it is delivered, or until you sign the same user in again, whose registration then supersedes a removal that a refusal parked. Without a provider and without a token it is sent as before, which a workspace in Off or Monitor accepts.
- **`appId`** (default: your application's package name) is sent with every request. A write key limited to certain apps needs it to match. The `aud` claim of a token is optional; if your backend sets it, it must equal the app ID (the package name, or the `appId` you configured), otherwise the server refuses the token with the reason `audience` and the SDK cannot fix that.

`onNotice` reports things that are not delivery failures and never contain a token or user ID. `Notice.kind` is `EVENTS_DROPPED` or `EVENTS_UNVERIFIED` (the server dropped, or could not verify, `count` events; once per response; the dropped count also covers oversized events, and in Required mode it includes anonymous events that could change a customer), `IDENTITY_REFUSED` (with the server's `reason`; it says when renewing cannot fix it, which means your token signing is wrong) or `IDENTITY_UNAVAILABLE`.

**In Required, register push and send traits after sign-in.** When your workspace requires verified identity, the server can drop events from a customer who is not signed in without any error: an anonymous `identify` (traits), `Installation Registered` and `Installation Removed`, subscription changes and push received, opened and dismissed events. The SDK never sends an Installation without a signed-in user (a forwarded FCM token is held until `identify`, which then registers it), but call `identify` with a token before you rely on push, and watch `onNotice` for `EVENTS_DROPPED`.

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
| `identityTokenProvider` | none | `suspend (userId) -> token?`; see [Identity verification](#identity-verification). |
| `appId` | the package name | Sent with every request. |
| `onNotice` | none | Diagnostics that are not failures: the server dropped or could not verify events, an identity token was refused, identity settings were unavailable. |

## Delivery

Events are sent as JSON to `/api/public/apps/batch`, one request at a time. Network errors and HTTP 408, 425, 429 and 5xx are retried with backoff, honouring `Retry-After`; other statuses are final, and a batch that fails for good is dropped and reported to `onError`.

Running out of retries never drops events. The batch stays first in the queue, `onError` is not called, and automatic delivery pauses for 30 seconds, then 60, 120, 240 and at most 300 seconds after each further batch that fails all its attempts. During the pause the interval timer, new events and a full batch start no attempt. `flush()` and the app returning from the background try again at once, and `flush()` throws the failure it waited for. A delivered batch resets the pause to 30 seconds, and an app that restarts begins without a pause and sends after the normal flush interval. Events leave the queue only when the server accepts them, when the server refuses them with a final status, on `optOut()`, when the queue is full, or, with identity tokens, when a refused token removes the events of a user who is no longer signed in (reported to `onError`) or the queue holds more than 1,000 events while a batch is held (the oldest are reported to `onError`).

A refused identity token holds the batch instead of dropping it, see [Identity verification](#identity-verification) for what is held, what is removed and what is reported.

Each queued event is saved to its own small file in a `queue` folder inside `storageDirectory` before the event call returns, so queued events survive a restart and an event costs the same however long the queue is. Events saved by 0.1.0 are moved into that folder the first time 0.2.0 starts. When the app goes to the background the SDK sends once and pauses if that fails; it resumes when the app returns. Android gives no guarantee that a background process keeps running, so anything undelivered is sent on the next launch.

The push token never appears in `onError`: it is replaced by `"<redacted>"`.

## Push

`tranthor-push` registers the phone's FCM token while a customer is signed in, shows Tranthor's push notifications and reports when they are received, opened or dismissed. Setup, the payload format and notification permission are in [docs/push.md](docs/push.md).

## Current limits

- No compression, alias or group calls, and no automatic screen tracking.
- No cross-process lock. An app with several processes needs a `storageNamespace` per process.

## Contributing and licence

See [CONTRIBUTING.md](CONTRIBUTING.md) to build and test, and [CHANGELOG.md](CHANGELOG.md) for releases. Report security issues as described in [SECURITY.md](SECURITY.md). Use of the SDK is covered by the [Tranthor SDK License](LICENSE): you may include it in your apps to connect them to Tranthor, and may not redistribute it on its own or use it with other services.
