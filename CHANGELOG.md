# Changelog

## [0.2.0] - 2026-08-13

### Added
- **`application` config option (application tag).** A label naming this app, sent as the
  `X-Featureflow-Application` header on every flag fetch and event post so the dashboard can
  attribute SDK usage and flag evaluations per application. Slug-validated (case forgiven,
  invalid values dropped with a warning and no header sent). Write-only telemetry — it never
  changes what is evaluated or served. See CONTRACT.md in featureflow-client-sdk-testbed.

## [0.1.1] - 2026-08-11

### Changed
- **Variant keys are now compared exactly, and `value()` returns the key unchanged.** Previously
  the stored key was lower-cased, so a variant defined as `Wizard` reported as `wizard` and
  `is("WIZARD")` matched. Keys are lowercase by convention, so case-folding bought nothing while
  making `value()` misreport the configured key. This aligns the SDK with the Java, Node, Go and
  .NET SDKs, which always compared exactly. See CONTRACT.md in featureflow-client-sdk-testbed.

## [0.1.0] - 2026-07-29

### Added
- Initial release. Client SDK for Android, minSdk 21.
- `FeatureflowClient` with `evaluate`, `peek`, `allFeatures`, `track`, `updateUser`, a `features`
  `StateFlow` and listener callbacks.
- Client-side resolution of `featureflow.date` / `featureflow.hourofday` rules, matching the
  partial-evaluation contract the JavaScript SDK implements.
- `SharedPreferences` evaluation cache keyed by API key and user id, and a persisted anonymous id.
- Summarised impression events and raw goal events, flushed on a timer and on backgrounding.
- Foreground refresh and background flush via `ProcessLifecycleOwner`.
