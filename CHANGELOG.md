# Changelog

## [Unreleased]

### Added
- Initial release. Client SDK for Android, minSdk 21.
- `FeatureflowClient` with `evaluate`, `peek`, `allFeatures`, `track`, `updateUser`, a `features`
  `StateFlow` and listener callbacks.
- Client-side resolution of `featureflow.date` / `featureflow.hourofday` rules, matching the
  partial-evaluation contract the JavaScript SDK implements.
- `SharedPreferences` evaluation cache keyed by API key and user id, and a persisted anonymous id.
- Summarised impression events and raw goal events, flushed on a timer and on backgrounding.
- Foreground refresh and background flush via `ProcessLifecycleOwner`.
