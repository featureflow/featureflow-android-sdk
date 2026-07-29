# Review instructions

This is a client-side feature-flag SDK shipped inside customer apps. Two facts drive everything
below: **a released version can never be hot-fixed** — someone will still be running today's
build in two years, and Maven Central is immutable so a bad release can only be superseded — and
**it must never be the reason a host app crashes or fails to start**.

## What Important means here

Reserve 🔴 Important for findings that would break a host app or serve a wrong flag value:

- **Anything that can crash or throw into the host app.** A feature-flag SDK failing must
  degrade to a default variant, never propagate. `initialize` is documented as never throwing.
- **Fail-open behaviour where fail-safe is required.** An unknown condition operator must return
  `false`; an unknown condition target must be *skipped*, not fail the rule; a failed fetch must
  fall back to cache, then `defaultVariants`, then `off`.
- **Wire-format divergence.** Paths, headers, the hand-rolled base64url path encoding, request
  and response shapes must match `sdk-server`, the JavaScript SDK and the iOS SDK. A mismatch here is
  invisible in tests and breaks in production.
- **Rule-evaluation semantics.** Rules are ordered and first-match-wins. A partially evaluated
  rule whose time conditions fail must be **skipped so evaluation continues** — ending evaluation
  there silently disables every fallback rule behind a scheduled one. This has been a real bug.
- **Cache correctness.** The evaluation cache is keyed by API key *and* user id. Serving one
  user's flags to another is a correctness bug, not untidiness — they may have had different
  entitlements.
- **Impression accounting.** `evaluate` records an impression; `peek` and bulk reads must not.
  Impressions drive experiment denominators and stale-flag detection.
- **Concurrency.** Data races, or state mutated off the single-threaded executor that owns it.
- **A leaked Activity or Context.** The SDK holds an application context only; a listener
  retaining an Activity, or a coroutine outliving its scope, leaks.
- **An API above minSdk 21.** `java.util.Base64` (26), `Map.putIfAbsent` (24) and `java.time`
  (26) all compile and then crash on an older device. The existing workarounds are commented.
- **A secret key in shippable code.** A `sdk-srv-env-` key must never appear outside tests or
  docs; it is extractable from an APK.

Naming, formatting, and refactoring suggestions are 🟡 Nit at most.

## Cap the nits

At most five Nits per review. If there are more, say "plus N similar items" in the summary. If
everything found is a Nit, open the summary with "No blocking issues".

## Do not report

- Anything CI already enforces: the unit tests, `assembleRelease`, the example app build, and
  the publication dry-run.
- Formatting and import ordering.
- Missing test coverage as an Important finding — raise it as a Nit, and only where the untested
  path is a failure path.
- `example/` and `harness/` held to production standards. They are developer tools; report only
  crashes, wrong SDK usage that would mislead a reader copying it, or a committed API key.

## Always check

- **Both SDKs stay in step.** This SDK and `featureflow-ios-sdk` implement one contract. A
  change to condition handling, rule ordering, event batching or the wire format that lands in
  only one of them is an Important finding.
- **Version constants move together.** `FeatureflowVersion.CURRENT` and `version` in
  `featureflow/build.gradle.kts` must agree; the former is sent as `X-Featureflow-Client` and is
  how server-side usage reporting attributes traffic. Central is immutable, so a mismatched
  release cannot be corrected, only superseded.
- **The evaluation core stays free of Android framework types.** `JsonValue`, `Models`,
  `Conditions`, `RuleEvaluator`, `Evaluation`, `FeatureflowConfig`, `RestClient` and
  `EventProcessor` must compile under plain `kotlinc`. An `android.*` import in any of them is an
  Important finding — it is what makes the JVM harness and fast local verification possible.
- **No new third-party dependency** without a stated reason. The SDK deliberately uses
  `HttpURLConnection` and `org.json` from the platform so it cannot conflict with the host app.
- **Public API additions are deliberate.** Kotlin defaults to public; anything not intended as
  API should be `internal`.
- **New behaviour that only works on a live connection** should say what happens when offline.

## Verification bar

Claims about behaviour need a `file:line` citation, not an inference from a name. This SDK has
several places where the obvious reading is wrong — the partial-evaluation skip, the deliberate
split where `evaluate` records an impression but the `features` flow does not, dropping failed
event batches rather than retrying, the hand-rolled base64url, and the API-21 workarounds — and
each is commented with its reason. Read the comment before flagging the code.

## Re-review

After the first review on a PR, post Important findings only. Do not raise new nits on later
pushes.
