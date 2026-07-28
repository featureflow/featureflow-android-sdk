# JVM harness

Exercises the SDK's wire contract and evaluation against a **real Featureflow environment**
without needing the Android SDK, Gradle or an emulator.

```bash
FEATUREFLOW_CLIENT_KEY=sdk-js-env-xxxx ./harness/run.sh
```

Requires only a JDK and `kotlinc` (`sdk install kotlin`). It downloads `org.json` on first run.

## What it covers, and what it does not

It compiles the SDK's platform-independent core — `JsonValue`, `FeatureflowUser`, `Models`,
`Conditions`, `RuleEvaluator`, `Evaluation`, `FeatureflowConfig`, `RestClient`, `EventProcessor`
— and drives it against your environment. That is the whole client contract: request shape,
base64url path encoding, response parsing, rule walking, client-side resolution of
`featureflow.date` / `featureflow.hourofday`, and the outgoing event batch.

It does **not** cover `FeatureflowClient`, `FeatureStore` or `AndroidLogcatLogger`, which need the
Android framework — no `SharedPreferences` caching, no `ProcessLifecycleOwner` foreground refresh,
no polling loop. Use [`../example`](../example) on a device or emulator for those.

The split exists precisely so most of the SDK can be checked in seconds. See `../CLAUDE.md`.
