# Contributing

## Setup

```bash
git clone https://github.com/featureflow/featureflow-android-sdk.git
cd featureflow-android-sdk
gradle wrapper --gradle-version 8.9    # once — the wrapper JAR is not committed
./gradlew :featureflow:testDebugUnitTest
```

You need the Android SDK (`ANDROID_HOME`, or a `local.properties` with `sdk.dir`) and a Java 17
toolchain.

## Verifying the core without the Android toolchain

Most of the SDK is deliberately free of Android framework types and compiles with plain
`kotlinc` against `org.json` and `kotlinx-coroutines-core`:

```
JsonValue.kt  FeatureflowUser.kt  Models.kt  Conditions.kt  RuleEvaluator.kt
Evaluation.kt  FeatureflowConfig.kt  RestClient.kt  EventProcessor.kt
```

Only `FeatureflowClient`, `FeatureStore` and `AndroidLogcatLogger` touch the framework. **Please
keep that split** — it is why the evaluation logic can be checked in seconds rather than needing
an emulator. If you add a framework dependency to one of the files above, move the code instead.

## Before opening a PR

```bash
./gradlew :featureflow:testDebugUnitTest
./gradlew :featureflow:assembleRelease
```

## Things to know

**This SDK must stay wire-compatible** with `featureflow-javascript-sdk`, the iOS SDK,
`sdk-server` and `featureflow-edge-proxy`: same paths, same headers, same request and response
shapes. If you change anything that crosses the network, check it against the server routes rather
than inferring it from existing calls, and add a case to `WireFormatTest`.

**Behaviour must match the iOS SDK.** The two are developed as a pair. A fix to condition handling,
rule ordering or event batching in one belongs in the other in the same change.

**minSdk is 21.** `java.util.Base64`, `Map.putIfAbsent` and `java.time` are all unavailable; the
workarounds are commented where they appear. Android Studio will happily suggest an API 26 call
that compiles and then crashes on an older device.

**Fail safe, always.** An unknown operator evaluates to `false`; an unknown condition target is
skipped; a failed fetch falls back to cache and then to defaults; a throwing listener does not
break the others. A feature-flag SDK must never be the reason an app fails to start or crashes.

## Style

- Kotlin official code style, 100-column lines.
- Comments explain *why*, particularly where behaviour looks surprising — the partial-evaluation
  skip, the hand-rolled base64, dropping failed event batches, the API-21 workarounds.
- Public API gets KDoc including the trap, if there is one.
- British English in prose.

## Releasing

1. Bump `FeatureflowVersion.CURRENT` in `featureflow/src/main/kotlin/io/featureflow/android/RestClient.kt`
   — it is sent as the `X-Featureflow-Client` header and is how server-side usage reporting
   identifies the SDK.
2. Update `CHANGELOG.md`.
3. Tag `x.y.z` and publish to Maven Central.
