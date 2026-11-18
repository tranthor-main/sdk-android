# Optional push integration (FCM)

Add the **tranthor-push** module alongside `tranthor-sdk`. It uses Kotlin coroutines, AndroidX `lifecycle-process` and Android platform APIs; Firebase Cloud Messaging is a `compileOnly` dependency, so the **host app adds `com.google.firebase:firebase-messaging`** and owns its Firebase configuration (`google-services.json` or manual `FirebaseOptions`). The workspace's own FCM key lives on the Tranthor server (Settings, Push); the app never holds server credentials.

Bound to your `TranthorSDK`, the module registers this phone with Tranthor and reports what happens to each push. It sends **reserved track events through the SDK's existing durable queue and write key**: no second endpoint, no registration callback, and the events survive process death and offline periods like any other event.

## Set up

```kotlin
// Application.onCreate, after TranthorSDK.configure(...):
val push = TranthorPush.bind(context, PushConfiguration(applicationId = BuildConfig.APPLICATION_ID), sdk)
```

`TranthorPush.bind` reads the OS notification permission and re-checks it every time the app returns to the foreground. It is the only way an app obtains a `TranthorPush`; retain the result for the app's lifetime. Bind again if you replace the SDK instance; the foreground re-check then follows the new one. Forward every token, the one from `FirebaseMessaging.getInstance().token` at launch and every `onNewToken` callback, with `push.registerDeviceToken(token)`; the ready-made `TranthorMessagingService` does this for you (below). After a permission request, call `push.refreshPermission()`.

Identity and consent are the SDK's: `identify`, `reset`, `optOut` and `optIn` on `TranthorSDK` keep the Installation in step automatically. The push module has no identity or consent calls of its own.

## What the SDK sends

All are `track` events through the SDK queue.

**A phone is registered only to an identified customer.** Until `identify()` gives the SDK a `userId`, a forwarded token is held on the device and nothing is sent, so a phone nobody is signed in on receives no push. `identify()` registers it, and `reset()` removes it again. Installation events therefore always carry a `userId`.

| Event | When | `properties` |
|---|---|---|
| `Installation Registered` | The SDK has a token and a signed-in customer (`identify()`). Again when the token, customer, OS permission, app version or SDK version changes, and otherwise once per app start (the server treats it as "last seen"; it is never sent on every foreground). | `platform: "android"`, `appId` (the Android application ID), `token`, `permissionState` (`"granted"`, `"denied"` or `"unknown"`), `appVersion` (version name, omitted when unknown), `osVersion`, `sdkVersion` |
| `Installation Removed` | The token is replaced (old token), on `reset()` (under the outgoing customer, before the identity rotates), and on `optOut()` (once, even while opted out; see Consent below). Never when nobody is signed in: no phone was registered. | `platform`, `appId`, `token` |
| `Push Received` | A Tranthor push reached the app, foreground or background, wherever the messaging service runs. | `deliveryId` |
| `Push Opened` | The user tapped the notification. | `deliveryId` |
| `Push Dismissed` | The user swiped it away (never counted as opened). | `deliveryId` |

There is no `environment` on Android (FCM has no sandbox/production split).

Ordering: the server applies a token's events in event-timestamp order, so every Installation event the SDK creates has a strictly later timestamp than the one before it. A refreshed token sends `Installation Removed` for the old token, then `Installation Registered` for the new one. `reset()` queues `Installation Removed` under the outgoing customer and saves the rotation straight after it, and registers nothing: the token stays held until a later `identify` registers it to that customer. An `identify` that switches straight from one customer to another, without a `reset()`, registers the token to the new one (the server moves a token to its new owner). Within one app start, an `identify` that does not change the identity sends no Installation event (the first call after a restart may carry that start's one re-registration).

Persistence: the SDK keeps the registered token, application ID and permission in its private `state.json` (under `noBackupFilesDir`, excluded from backup), so a reset or token refresh after a restart still removes the right token. The token is also inside queued event files, in the `queue` folder next to it, until they are delivered. It is never logged.

## Consent and identity

- **`optOut()`** discards the queue and sends nothing afterwards, with one exception. If a customer is signed in and an Installation is stored, one `Installation Removed` (`platform`, `appId`, `token`, no automatic context) is delivered even while opted out, so Tranthor stops pushing to the phone. It is saved in the same write as the opt-out.
- **A removal that fails** with a network error, 408, 425, 429 or 5xx is retried with the configured retries and backoff, then waits with no timer. It gets one new attempt on each of: app start, the app returning to the foreground after a stop, `optIn()` and another `optOut()`. A 2xx or any other 4xx ends it; a 4xx is reported once to `onError` with the token redacted.
- **While opted out**, a refreshed token is stored but not registered, and `flush()` returns without a request.
- **`optIn()`** resumes collection and registers the stored token again for a signed-in customer. Queued events wait behind a removal that is still undelivered; until it is delivered, `flush()` throws that removal's `DeliveryFailure`.
- **`reset()`** sends `Installation Removed` under the outgoing customer, in the same durable write as the new anonymous ID. The token stays held and nothing is registered until the next `identify`.
- **`identify`** registers the held token to that customer. Identifying a different customer without `reset()` moves the token to the new customer. An `identify` that changes nothing sends no Installation event.

If the queue overflows while an Installation event waits, ordinary events are dropped first.

The interaction events carry a deterministic `messageId` (described under Interactions), so the server drops duplicates. `PushInteraction.Foreground` is reported as `Push Received` with the same `messageId` as `PushInteraction.Received`, so a message is counted once.

## The push the server sends

Tranthor sends **data-only** FCM messages (no `notification` block), so the app always builds the notification itself and can report receipt and opens:

```json
{
  "message": {
    "token": "<fcm-token>",
    "data": {
      "tranthor": "{\"version\":1,\"deliveryId\":\"8c0f...\",\"imageUrl\":\"https://cdn.example/a.png\"}",
      "title": "Hello",
      "body": "Example",
      "channelId": "promotions",
      "url": "shop://orders/42",
      "orderId": "42"
    },
    "android": { "priority": "HIGH", "ttl": "2419200s" }
  }
}
```

FCM data values must be strings, so the marker is one data key, `tranthor`, whose value is a **JSON string**. `version` must be the JSON number 1 (booleans and strings are rejected), and `deliveryId` a nonblank opaque identifier of at most 128 ASCII characters from `[A-Za-z0-9._:-]`. `imageUrl` is optional; settings test sends add `"test":true`, which the SDK ignores. `title`, `body` and `channelId` are omitted when empty. `PushContent.fromData(message.data)` reads all of this and returns null for anything without a valid marker, which belongs to the host (another SDK, for example).

`url` is the template's "When tapped, open" link, and every other key the server and FCM do not reserve (`tranthor`, `title`, `body`, `channelId`, `url`, `from`, `message_type`, `google.*`, `gcm.*`) is one of the template's data entries. `PushContent` exposes them as `url` and `data`.

**The SDK never opens a link.** A tap on a notification that `show` displayed opens your launch activity, and `TranthorNotifications.tapFrom(context, intent)` hands you what to report and what the push asked for:

```kotlin
// onCreate (only when savedInstanceState == null) and onNewIntent:
val tap = TranthorNotifications.tapFrom(this, intent) ?: return
lifecycleScope.launch { push.handle(tap.notification, tap.interaction) }
tap.url?.let { link -> if (link.startsWith("https://shop.example.com/") || link.startsWith("shop://")) router.open(link) } // your allow-list
val orderId = tap.data["orderId"]
```

`tapFrom` returns the link and data only for an intent the SDK created (the same action and per-install nonce check as `interactionFrom`, which it replaces when you want the link), and consumes them so activity recreation does not hand them over again. The link still comes from a push: treat it as untrusted input. If you build the notification yourself, wrap `TranthorNotifications.tapIntent(context, launchIntent, content)` in an immutable activity `PendingIntent` to get the same behaviour.

`TranthorNotifications.show(context, content, style)` displays it: `title`/`body` on `channelId` when your app created that channel, otherwise the style's default channel (created on first use), with the rich image when `imageUrl` is set and its host is allowlisted, and with the SDK's verified open/dismiss intents. One notification per delivery ID, so a redelivered message replaces rather than duplicates it.

The push API is Kotlin `suspend` functions; call them from your own coroutine scope.

## Forward without replacing another SDK

The host owns its `FirebaseMessagingService` and activities; TranthorPush never registers a service by itself, swizzles, opens URLs or routes deep links. The simplest path is the optional ready-made `TranthorMessagingService`:

```kotlin
class MyMessagingService : TranthorMessagingService() {
    // A push can start a cold process: await the app's configured push (or return null to skip).
    override suspend fun tranthorPush(): TranthorPush? = (application as MyApp).push.await()
    override fun notificationStyle() = TranthorNotificationStyle(
        smallIcon = R.drawable.ic_notification, defaultChannelId = "tranthor", defaultChannelName = "Updates",
        launchIntent = Intent(this, MainActivity::class.java), dismissReceiver = MyDismissReceiver::class.java,
        images = RichImageConfiguration(setOf("images.your-company.example")))
    override fun onOtherMessage(message: RemoteMessage) = otherSdk.handle(message)
}
```

With those overrides it forwards every token, displays each Tranthor push and reports `Push Received`, in the foreground or background, within FCM's execution window (the SDK work is bounded to 15 seconds). It awaits `tranthorPush()` before it reads `notificationStyle()`, so a style that is ready only once the SDK is configured still shows a push that started a cold process; if `tranthorPush()` throws, the push is still shown (without `Push Received`). The SDK work, including that wait, is bounded to 15 seconds: a `tranthorPush()` that never completes in a push-started process (for example one waiting for configuration that only an Activity performs) means the push is not shown, so complete it from `Application.onCreate`. One notification per `deliveryId`: the same message arriving twice replaces it and reports `Push Received` once. Messages without a Tranthor marker go to `onOtherMessage`. Leave `tranthorPush()`/`notificationStyle()` unimplemented to keep doing it yourself in `onTranthorToken`/`onTranthorMessage`, or call the helpers from your own service:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { appScope.launch { push.registerDeviceToken(token) } }
    override fun onMessageReceived(message: RemoteMessage) {
        val content = PushContent.fromData(message.data) ?: return otherSdk.handle(message)
        TranthorNotifications.show(this, content, style)
        runBlocking { push.handle(content.notification, PushInteraction.Received) }
    }
}
```

Interactions:

- **Received** (`Push Received`): a recognized message reached the app, foreground or background. `Foreground` (a message observed while some activity is visible) is reported as the same `Push Received`.
- **Opened**: `TranthorNotifications.openIntent(...)` copies your explicit launch intent and adds the SDK action `com.tranthor.push.action.OPEN`, the marker and a per-install secret nonce (stored in the app's private preferences and only ever placed inside the app's own immutable PendingIntents). In `onCreate`/`onNewIntent`, call `TranthorNotifications.interactionFrom(context, intent)` and forward the pair to `push.handle(...)`. It returns a result only for an intent carrying that action and nonce, ignores relaunches from Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, which re-deliver the original intent), and consumes the extras so activity recreation does not report the open again. The body-tap action identifier is `"default"` (`PushInteraction.DEFAULT_ACTION`).
  Call it only for a fresh delivery: skip restored activities (`savedInstanceState != null`) and relaunches from Recents. `interactionFrom` already ignores `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` and consumes the extras, but a restored activity's `getIntent()` after process death is the original intent again; the example's `MainActivity` calls the helper only when `savedInstanceState == null` and from `onNewIntent`. The per-install nonce is created and committed synchronously on first use; call `TranthorNotifications.prepare(context)` once from a background thread at startup (as `ExampleApplication` does) to keep that disk write off the main thread. Nonces are compared in constant time.
- **Dismissed**: `TranthorNotifications.dismissIntent(...)` targets your own non-exported `BroadcastReceiver` as the notification's delete intent; forward `interactionFrom(context, intent)` from it (same action/nonce check, action `com.tranthor.push.action.DISMISS`). A dismissal is never counted as opened.

`push.handle(notification, interaction)` reports one of these (Opened and Dismissed come from `interactionFrom` in your activity and dismiss receiver). `handle` returns false for unrecognized payloads, and true for recognized ones even if consent discards them. Default dedupe remembers 1,024 delivery ID and interaction pairs per instance (configurable 1 to 10,000, with `PushConfiguration(applicationId, recentEventLimit)`); concurrent duplicates share the first observation, and suspended observations are capped at the same limit. Evicted or relaunched IDs can be observed again: there is no durable dedupe on the device. Each event has a deterministic SHA-256-derived UUIDv8 `messageId` for its delivery ID and interaction (`SHA-256("TranthorPush:v1:<utf8 length>:<deliveryId>:<action>")`, first 16 bytes, version 8 and RFC variant bits, upper case), so the server can drop a repeat. The observation is attributed to the identity the SDK has when `handle` admits it.

The example app holds up to 32 observations that arrive before the SDK is configured, and reports them once it is. A process death loses that buffer.

## Notification permission

`PushPermission` is an explicit helper: on Android 13+ (API 33) posting requires the runtime `POST_NOTIFICATIONS` permission (declared by the module's manifest; request it with the Activity Result API or `PushPermission.request(activity, code)`); earlier versions only honour the app's notification setting. `canPostNotifications(context)` reports the current state. Nothing prompts automatically, and no permission is needed to receive FCM data messages.

## Rich image notifications

Android has no notification service extension: the app builds the notification itself. For a recognized data message with an `imageUrl`, call:

```kotlin
val image = TranthorNotifications.loadImage(notification, RichImageConfiguration(setOf("images.your-company.example")))
val built = TranthorNotifications.build(context, channelId, R.drawable.ic_notification, title, body,
    TranthorNotifications.openIntent(context, launchIntent, notification, requestCode),
    TranthorNotifications.dismissIntent(context, MyDismissReceiver::class.java, notification, requestCode), image)
```

`loadImage` blocks (call it from `onMessageReceived`, which runs on a background thread) and returns null on any failure, so the text notification is always preserved. The downloader permits only exact allowlisted HTTPS hosts on port 443, with no URL credentials, fragments or redirects, and sends no SDK write key or token. Use a final CDN URL. Signed query strings are supported and never logged. Supported MIME/content pairs are PNG, JPEG and GIF with at most 16 million pixels in the first frame; magic bytes, dimensions and format trailers are validated in pure Kotlin before decoding, and identity-encoded responses must match their Content-Length (truncation). Defaults: 5 MiB and 10 seconds (validated up to 10 MiB and 20 seconds, with a watchdog bounding the whole transfer). At most four downloads run per process. The image is decoded with `BitmapFactory` sampled so its longest side is at most 1024 px, then shown with `BigPictureStyle`. The bounded bytes are held in memory and no file is written. The empty allowlist rejects all remote images.

FCM gives `onMessageReceived` a short execution window (about 10–20 seconds depending on the OS and message priority), which is why the time bound defaults to 10 seconds.

## What the server does with these events

The Tranthor server keeps one Installation per `(workspace, platform, appId, token)`, owned by one customer at a time. It applies a token's events in event-time order, moves a re-registered token to its new owner, and removes an Installation on `Installation Removed`, on FCM `UNREGISTERED`, or after 90 days without re-registration. OS permission is advisory: only an explicit `"denied"` excludes an Installation. The write key ships in the app, so registrations are trusted at the same level as `identify`, and interaction events are claims that should count only when their `deliveryId` matches a push the server sent to that customer. Applications must review privacy disclosures for push tokens and notification-interaction analytics; this module adds no tracking prompt or third-party analytics dependency.

## Limits

- The nonce on notification intents guards against other apps, not against code in your own process or a rooted device.
- A tap on a notification FCM displayed itself (a notification message, not a data message) cannot be authenticated, so it is not counted as an open unless you call `unverifiedTrayOpenFrom`.
- Duplicate suppression lives in memory. After a process restart, an intent that is delivered again can report the same observation twice; the repeat carries the same `messageId`.
