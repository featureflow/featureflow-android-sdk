# Releasing

Publishes to **Maven Central** via the Sonatype Central Portal, as
`io.featureflow:featureflow-android-sdk`.

> **Central is immutable.** A released version cannot be deleted or overwritten — only
> superseded by a higher version. That is why `release.yml` checks the version constants, runs
> the tests and asserts a signing key before it uploads anything.

## Before the first release

Much of this already exists because `featureflow-java-sdk` publishes to Central under the same
namespace. Reuse it rather than creating a second identity.

### 1. Namespace

`io.featureflow` is already verified on the Central Portal — that is how the Java SDK publishes.
Nothing to do, but confirm the account you generate the token under has access to it.

### 2. Portal token

Sign in to <https://central.sonatype.com> → **View Account** → **Generate User Token**. It gives
a username/password pair, not your login. Add as repository secrets:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | token username |
| `MAVEN_CENTRAL_PASSWORD` | token password |

### 3. GPG signing key

Central rejects unsigned artifacts. **Reuse the Java SDK's key** so both artifacts are signed by
the same identity — it lives encrypted at `featureflow-java-sdk/codesigning.asc.enc` and is
decrypted in `deploy-prod.sh` with `${ENCKEY}`.

```bash
# from a checkout of featureflow-java-sdk, with ENCKEY to hand
openssl aes-256-cbc -d -in codesigning.asc.enc -out codesigning.asc -k "$ENCKEY" -pbkdf2
```

Then add as repository secrets:

| Secret | Value |
|---|---|
| `SIGNING_KEY` | the full ASCII-armoured private key, `-----BEGIN…` to `-----END…` inclusive |
| `SIGNING_KEY_PASSWORD` | its passphrase |

Confirm the public key is on a keyserver Central checks, or it will reject the bundle:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

The Java SDK publishing successfully implies this is already done for that key.

### 4. Environment

The workflow uses an environment named `release`. Create it under **Settings → Environments** and
hold the four secrets there rather than at repository level, so a required reviewer can gate
publishing.

## Cutting a release

1. Bump the version in **two** places — CI fails the release if they disagree:
   - `version = "x.y.z"` in `featureflow/build.gradle.kts`
   - `FeatureflowVersion.CURRENT` in
     `featureflow/src/main/kotlin/io/featureflow/android/RestClient.kt` — sent as the
     `X-Featureflow-Client` header, and how server-side usage reporting attributes traffic
2. Update `CHANGELOG.md`.
3. Merge to `main`.
4. Create a GitHub Release with tag `x.y.z`.

The workflow verifies versions, tests, assembles, requires a signing key, and uploads. With
`automaticRelease = true` the bundle goes live without a manual Portal step; it usually appears
on Maven Central within ~15 minutes and on search a few hours later.

**Rehearse first.** Run the workflow manually with `dry_run` — it builds and signs the exact
bundle without uploading. Worth doing before the first real release, when the account setup is
still unproven.

## Checking the publication locally

```bash
./gradlew :featureflow:publishToMavenLocal
ls ~/.m2/repository/io/featureflow/featureflow-android-sdk/<version>/
```

Central requires four artifacts — `.aar`, `-sources.jar`, `-javadoc.jar`, `.pom` — and CI asserts
all four exist. Signing is skipped locally when no key is configured, which is deliberate: it
keeps this check available without handling the GPG key. The release workflow asserts the key
was present, so a convenience for local use cannot become an unsigned release.

## Versioning

Semantic versioning. Below `1.0.0`, treat minor bumps as potentially breaking while the API
settles.

Note the mobile constraint: **a published version is used forever.** Someone will still be
running today's release in two years, so an SDK version must keep working against a newer server
— which is why unknown condition operators and unknown targets fail safe rather than throwing.
See `CLAUDE.md`.

## What CI cannot catch

- **The framework half** — `SharedPreferences` caching, `ProcessLifecycleOwner` foreground
  refresh, the polling loop. CI compiles `example/`; it does not run it. Use an emulator.
- **A live environment.** `./harness/run.sh` against a real key is still the only end-to-end
  check, and it is what found the date-only condition bug. Worth doing once per release.
