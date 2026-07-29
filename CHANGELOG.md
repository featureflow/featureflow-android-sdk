# Changelog

## [Unreleased]

### Changed
- **Variant keys are now compared exactly, and `value()` returns the key unchanged.** Previously
  the stored key was lower-cased, so a variant defined as `Wizard` reported as `wizard` and
  `is("WIZARD")` matched. Keys are lowercase by convention, so case-folding bought nothing while
  making `value()` misreport the configured key. This aligns the SDK with the Java, Node, Go and
  .NET SDKs, which always compared exactly. See CONTRACT.md in featureflow-client-sdk-testbed.

### Added
- Initial release. Client SDK for Android, minSdk 21.
- `FeatureflowClient` with `evaluate`, `peek`, `allFeatures`, `track`, `updateUser`, a `features`
  `StateFlow` and listener callbacks.
- Client-side resolution of `featureflow.date` / `featureflow.hourofday` rules, matching the
  partial-evaluation contract the JavaScript SDK implements.
- `SharedPreferences` evaluation cache keyed by API key and user id, and a persisted anonymous id.
- Summarised impression events and raw goal events, flushed on a timer and on backgrounding.
- Foreground refresh and background flush via `ProcessLifecycleOwner`.
