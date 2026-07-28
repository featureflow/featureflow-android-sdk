# CLAUDE.md

Guidance for Claude Code working in this repository.

Workspace-level guidance is in `../CLAUDE.md`.

## What this is

The Featureflow **client-side** SDK for Android (Kotlin, Gradle/AGP). It is a sibling of
`../featureflow-ios-sdk`, and the two must behave identically — see *The contract* below.

## Commands

```bash
./gradlew :featureflow:assembleRelease
./gradlew :featureflow:testDebugUnitTest
```

**The Gradle wrapper JAR is not committed** — it was generated on a machine without Gradle
installed. Run `gradle wrapper --gradle-version 8.9` once to produce `gradlew` and
`gradle/wrapper/gradle-wrapper.jar`; `gradle-wrapper.properties` is already correct.

Building also needs the Android SDK (`ANDROID_HOME` or `local.properties`).

### Verifying without the Android toolchain

The platform-independent core — `JsonValue`, `FeatureflowUser`, `Models`, `Conditions`,
`RuleEvaluator`, `Evaluation`, `FeatureflowConfig`, `RestClient`, `EventProcessor` — compiles with
plain `kotlinc` against `org.json` and `kotlinx-coroutines-core` alone. Only `FeatureflowClient`,
`FeatureStore` and `AndroidLogcatLogger` touch the Android framework, which is why the logger was
split out of `FeatureflowConfig.kt`. **Keep it that way**: it is the difference between the
evaluation logic being testable in seconds and needing an emulator.

## The contract

This SDK is a **client**, not a server SDK. It does no rule matching of its own beyond time
conditions. Two endpoints, both keyed by the API key in the URL path (client keys are public):

| Call | Endpoint |
|---|---|
| Fetch | `GET {baseUrl}/api/js/v1/evaluate/{apiKey}/user/{base64url(userJSON)}` |
| Events | `POST {eventsUrl}/api/js/v1/event/{apiKey}` |

The authoritative implementations are `../sdk-server/src/routes/js/`,
`../sdk-server/src/services/evaluate.service.ts` and `../sdk-server/src/services/conditions.ts`.
**Verify against those, never from memory.** `../featureflow-javascript-sdk/src/` is the reference
client — this SDK deliberately mirrors its behaviour.

`featureflow-edge-proxy` serves the same surface, so paths, headers and response bytes must stay
wire-compatible.

### Partial evaluation

The server pre-matches every rule against the user's own attributes, then returns a short list of
candidate rules. Rules that depended on `featureflow.date` or `featureflow.hourofday` come back
*partially* evaluated, with those conditions attached for the device to resolve — that is what
keeps the response identical for everyone in a bucket, and therefore CDN-cacheable.

`RuleEvaluator` is the client half: walk rules in order, first match wins, and a partial rule
whose time conditions fail is **skipped so evaluation continues** — it does not end evaluation.
`RuleEvaluatorTest.skippedPartialRuleFallsThroughToNextRule` pins that.

### Deliberate decisions

- **base64url is hand-rolled.** `java.util.Base64` needs API 26 and `android.util.Base64` is not
  available off-device in unit tests. Standard base64 emits `/`, which is a path separator, so
  the URL-safe alphabet is required. `WireFormatTest` checks it against `java.util.Base64` at
  every padding length.
- **`HttpURLConnection` and `org.json`, not OkHttp/Moshi/kotlinx-serialization.** Both are part of
  the platform, so the SDK adds no transitive dependency that can conflict with the host app's.
- **Unknown condition operators and unknown targets fail safe.** An operator this version predates
  returns `false`; a target the SDK does not supply locally is *skipped*, so an old app keeps
  matching a rule as it did before a new target existed. Both are tested.
- **Impressions are summarised** per (feature, variant, user). A flag read in a composable
  recomposes freely; unsummarised, that would post events at the frame rate.
- **Reading `features` (the StateFlow) records no impression**; `evaluate()` does. That split is
  deliberate — impressions must mean exposure.
- **Failed event batches are dropped, not retried.** Retrying risks double-counting impressions
  after a partial server-side write, and events are diagnostic rather than transactional.
- **`initialize` never throws.** Featureflow being unreachable must not stop an app launching. It
  falls back to cache, then `defaultVariants`, then `off`.
- **The cache is keyed by API key *and* user id.** Serving a previous user's flags to a new one is
  a correctness bug, not untidiness — they may have had different entitlements.
- **A throwing listener must not break the poll loop.** `notify` catches per listener.
- **`ProcessLifecycleOwner` access is wrapped in a try/catch.** Its initialiser is absent in unit
  tests and some stripped builds; the SDK degrades to polling rather than crashing.

## API-level constraints

minSdk is **21**, which rules out several conveniences. Where one is worked around, the reason is
in a comment — keep those comments if you touch the code:

- `java.util.Base64` → API 26, hand-rolled in `RestClient`
- `Map.putIfAbsent` → API 24, `putIfAbsentCompat` in `FeatureflowClient.kt`
- `java.time` → API 26 without desugaring, `SimpleDateFormat` in `Iso8601`

## Testing

Unit tests run under Robolectric because the model layer uses `org.json`, whose unit-test stubs
throw by default.

Two harnesses exist for testing against a live environment:

- `harness/run.sh` — JVM, no Android toolchain. Compiles the platform-independent core together
  with `harness/Harness.kt` in one `kotlinc` invocation, which puts the harness in the same module
  so it can see `internal` declarations — the same trick the unit tests use. Covers the wire
  contract, rule walking and client-side time resolution.
- `example/` — an Android Compose app for the framework-dependent half: `SharedPreferences`
  caching, `ProcessLifecycleOwner` foreground refresh, polling, `StateFlow` recomposition.

The split mirrors the `core/` vs framework split in the source, and is the reason the JVM harness
is possible at all.

There is no automated live-server integration test yet. `../featureflow-client-sdk-testbed` holds the shared
cross-SDK scenarios this SDK and the iOS SDK must both satisfy; wiring this repo up to it is
outstanding — see `../ops/SDK-BACKLOG.md`.

## Version

`FeatureflowVersion.CURRENT` in `RestClient.kt` is sent as the `X-Featureflow-Client` header and
must be bumped alongside `CHANGELOG.md` on release.
