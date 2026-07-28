# On-device harness

An Android app that exercises the parts of the SDK the [JVM harness](../harness) cannot: the
`SharedPreferences` cache, foreground refresh via `ProcessLifecycleOwner`, the polling loop, and
the `features` `StateFlow` driving recomposition.

## Setup

Put your **client** SDK key in `local.properties` at the repo root — it is git-ignored, so the
key never lands in a commit:

```properties
featureflow.clientKey=sdk-js-env-xxxx
# optional, for staging or self-hosted
featureflow.baseUrl=https://app.featureflow.io
```

Get the key from **Environments → your environment → API Keys**. It starts with `sdk-js-env-`.
Never use a `sdk-srv-env-` key in an app: it downloads your whole ruleset and anyone can extract
it from the APK.

## Run

```bash
./gradlew :example:installDebug
```

Or open the project in Android Studio and run the `example` configuration.

## What to check

1. **Flags load.** The list fills with every feature in the environment.
2. **A change arrives without a restart.** Flip a flag in the dashboard and watch the list update
   within 15 seconds. This is the behaviour worth verifying above all others.
3. **Foreground refresh.** Background the app, flip a flag, bring it back — the change should be
   there immediately rather than after the next poll.
4. **The cache works.** Force-stop the app, turn off networking, relaunch. Flags should still be
   the last known values rather than all `off`.
5. **User switching re-evaluates.** Change the user id and press Switch; anything with targeting
   rules should follow.
6. **Events arrive.** Press Track goal, then background the app — the batch flushes on
   background. Check the impressions in the dashboard.

Point 4 is the one most likely to be wrong, and the one that matters most on a mobile network.
