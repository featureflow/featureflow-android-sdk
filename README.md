# featureflow-android-sdk

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![minSdk 21](https://img.shields.io/badge/minSdk-21-brightgreen.svg)](https://developer.android.com)

> Featureflow client SDK for Android

Get your Featureflow account at [featureflow.io](https://featureflow.io).

**Contents:** [Install](#installation) · [Quick start](#quick-start) · [Compose](#jetpack-compose) ·
[Users](#users) · [Goals](#goals) · [Configuration](#configuration) ·
[Mobile-specific behaviour](#things-that-are-different-on-mobile) · [Testing](#testing)

---

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.featureflow:featureflow-android-sdk:0.1.0")
}
```

**Requirements:** minSdk 21, Java 17 toolchain, Kotlin 1.9+.

The SDK depends only on `kotlinx-coroutines` and `androidx.lifecycle`. It uses
`HttpURLConnection` and `org.json` from the platform rather than OkHttp or a JSON library, so it
cannot conflict with your app's HTTP stack or force a version on you.

It needs the internet permission, which it declares itself:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Get your key

Go to **Environments → (your environment) → API Keys** in the
[Featureflow dashboard](https://app.featureflow.com) and copy the **Client SDK key**. It starts
with `sdk-js-env-`.

> **Use the client key, never the server key.** A `sdk-js-env-` key is public by design — it only
> ever returns already-evaluated values for one user. A `sdk-srv-env-` key downloads your entire
> ruleset, including every targeting rule and attribute name, and anyone can extract it from a
> shipped APK. If a server key is ever built into an app, rotate it.

## Quick start

```kotlin
val user = FeatureflowUser.Builder("user-123")
    .withAttribute("tier", "gold")
    .withAttributes("roles", listOf("beta"))
    .build()

val featureflow = FeatureflowClient.initialize(
    context = applicationContext,
    apiKey = "sdk-js-env-YOUR_KEY",
    user = user
)

if (featureflow.evaluate("new-checkout").isOn()) {
    // the new checkout
} else {
    // the old one
}
```

`initialize` is a `suspend` function that waits for the first evaluation, so calling it before
rendering flag-driven UI avoids a visible variant swap. It never throws — if Featureflow is
unreachable the client falls back to the on-disk cache, then to your configured defaults. A flag
service being down must not stop your app from starting.

Create **one** client, in `Application.onCreate` or your DI graph, and share it.
`FeatureflowClient.get()` returns the one `initialize` created.

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            FeatureflowClient.initialize(this@MyApplication, BuildConfig.FEATUREFLOW_KEY)
        }
    }
}
```

### Evaluating

```kotlin
val evaluation = featureflow.evaluate("checkout-layout")

evaluation.isOn()        // variant == "on"
evaluation.isOff()       // variant == "off"
evaluation.`is`("wizard")// any variant, case-insensitive
evaluation.value()       // "wizard"
evaluation.jsonValue()   // the variant's JSON config payload, if any
```

`evaluate` is synchronous and reads already-fetched data, so it is safe in a composable or
`onBindViewHolder` — no need to cache the result. Each call records an impression, which is
summarised into a count rather than sent individually.

Use `peek()` instead where a read does not mean the user was actually exposed to the feature —
debug screens, diagnostics. Impressions drive experiment results and stale-flag detection, so
keeping non-exposures out of them matters.

### Variant config payloads

A variant can carry JSON, so a flag can change a value rather than a code path:

```kotlin
val limit = featureflow.evaluate("upload-limits")
    .jsonValue()?.get("maxUploads")?.intValue ?: 10   // always have a fallback
```

## Jetpack Compose

`features` is a `StateFlow`, so the UI follows a rollout without a relaunch:

```kotlin
@Composable
fun Checkout(featureflow: FeatureflowClient) {
    val features by featureflow.features.collectAsState()

    if (features["new-checkout"] == "on") {
        NewCheckout()
    } else {
        LegacyCheckout()
    }
}
```

Reading through the flow records **no** impression. Call `featureflow.evaluate(...)` at the point
the user is actually exposed to the feature — typically in the branch you took, or in a
`LaunchedEffect`.

## Views

```kotlin
class CheckoutActivity : AppCompatActivity() {

    private val listener: (Map<String, String>) -> Unit = { applyFlags() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FeatureflowClient.get()?.addListener(listener)
    }

    override fun onDestroy() {
        FeatureflowClient.get()?.removeListener(listener)
        super.onDestroy()
    }
}
```

Always remove the listener — one holding a reference to an Activity leaks it.

## Users

```kotlin
val user = FeatureflowUser.Builder("user-123")
    .withAttribute("tier", "gold")
    .withAttribute("age", 32)
    .withAttribute("beta", true)
    .withAttributes("roles", listOf("admin", "tester"))
    .build()
```

Attributes may be strings, numbers, booleans, dates or lists; a rule matches when **any** element
of a list matches. Session attributes are used for the evaluation but not persisted against the
user for later rule-building.

**The id must be stable for the same person across launches.** It is what percentage rollouts
bucket on — an id that changes per launch re-buckets the user every time, so a 10% rollout looks
like it is flickering on and off. Use your own account id. Omit the user entirely and the SDK
generates an anonymous id and persists it.

> Do **not** use the Android ID or an advertising ID. They are unstable across resets and reinstalls
> and carry privacy obligations you do not need for flag bucketing.

### On login and logout

```kotlin
featureflow.updateUser(loggedInUser)   // suspend; re-evaluates everything
```

Call it on login, on logout, and whenever a targeting attribute changes. Queued impressions are
flushed first so they stay attributed to the user who generated them.

On logout, `resetAnonymousId()` issues a fresh anonymous id so the signed-out user does not stay
in the buckets the account was in. Follow it with `updateUser`.

### Sharing a user with your backend

For an experiment spanning app and server, both sides must use the same id or they will disagree
about which arm the user is in. For a signed-in user that is your account id. For an anonymous
one, send the SDK's id:

```kotlin
request.header("X-Featureflow-Anonymous-Id", featureflow.anonymousId)
```

## Goals

```kotlin
featureflow.track("checkout-completed")
featureflow.track("purchase", value = 49.95)
featureflow.track("purchase", value = 49.95, data = mapOf("plan" to JsonValue.of("pro")))
```

Fire the goal where the conversion actually happens — after payment succeeds, not when the button
is tapped — and for **every** arm of an experiment including the control, or the denominator is
wrong.

Events are batched and flushed on a timer and when the app backgrounds.

## Configuration

```kotlin
val config = FeatureflowConfig(
    pollingIntervalMillis = 60_000,
    defaultVariants = mapOf("new-checkout" to "off", "kill-switch-payments" to "on"),
    logger = AndroidLogcatLogger(FeatureflowLogLevel.DEBUG)
)

val featureflow = FeatureflowClient.initialize(context, "sdk-js-env-YOUR_KEY", user, config)
```

| Option | Default | Notes |
|---|---|---|
| `pollingIntervalMillis` | `60_000` | Foreground refresh interval. **This is your flag propagation latency** — and the main driver of request volume, which Featureflow bills on. |
| `backgroundPollingIntervalMillis` | `0` | Disabled by default; see below. |
| `refreshOnForeground` | `true` | Re-fetch when the app comes to the foreground. |
| `defaultVariants` | `emptyMap()` | Served before the first fetch and when offline. Anything unlisted is `off`. |
| `useCache` | `true` | Persist the last evaluation, so returning users skip the default-value frame. |
| `offline` | `false` | No network at all; serves `defaultVariants`. For tests and previews. |
| `disableEvents` | `false` | Stops impressions and goals. |
| `eventFlushIntervalMillis` | `30_000` | Milliseconds between event flushes. |
| `maxEventQueueSize` | `1000` | Bound on queued events during a long offline session. |
| `timeoutMillis` | `10_000` | Connect and read timeout. |
| `logger` | `null` | An SDK should not write to your logcat uninvited. |

Set `defaultVariants` for any flag whose wrong-way default would be harmful. It is the mobile
equivalent of the failover variants the server SDKs register — and note the polarity: for a kill
switch protecting a fragile dependency, the safe default is usually the *safe path*, which may
mean the flag reads `on` by default.

## Things that are different on mobile

**Shipped binaries never update.** Someone will still be running the build you shipped today in
two years, and it will keep evaluating whatever flags it reads. **Never delete a flag a live build
still reads** — archive it instead and leave the off variant serving something safe. Check your
minimum supported version before cleaning up a mobile flag.

**Background polling is off by default, honestly.** A backgrounded Android process is subject to
Doze and App Standby; a timer is not a schedule the platform will honour, and it stops when the
process is frozen. Flags refresh when the app returns to the foreground, which is what
`refreshOnForeground` is for. If you genuinely need background updates, schedule a
`WorkManager` job and call `featureflow.refresh()` — that is a supported use of the API.

**A long foreground session can hold a stale value.** Values refresh on the poll interval, so
don't rely on a flag flipping mid-session for anything safety-critical.

**Time-based rules use the device clock.** Rules targeting `featureflow.date` or
`featureflow.hourofday` are deliberately resolved on-device — that is what keeps responses
CDN-cacheable — so a scheduled rollout fires at each user's local time, and a device with a wrong
clock gets the wrong answer. For a hard cutover at a specific instant, flip the flag server-side.

## Testing

Run offline with fixed variants — no network, deterministic results:

```kotlin
val featureflow = FeatureflowClient.initialize(
    context = context,
    apiKey = "test",
    config = FeatureflowConfig(offline = true, defaultVariants = mapOf("new-checkout" to "on"))
)
assertTrue(featureflow.evaluate("new-checkout").isOn())
```

Write a test for **both** branches of every flag. An untested `off` branch is the usual reason a
rollback fails.

## Development

```bash
./gradlew :featureflow:assembleRelease
./gradlew :featureflow:testDebugUnitTest
```

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Links

- [Featureflow](https://featureflow.io)
- [Documentation](https://docs.featureflow.io)
- [iOS SDK](https://github.com/featureflow/featureflow-ios-sdk)

## License

MIT — see [LICENSE](LICENSE).
