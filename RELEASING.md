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

Central rejects unsigned artifacts.

`featureflow-java-sdk` has an encrypted key at `codesigning.asc.enc`, decrypted in CI with an
`ENCKEY` CircleCI environment variable. **Treat that as unavailable unless the passphrase is in a
password manager** — CircleCI masks environment variables, so it cannot be read back. The
encrypted file also predates the public key committed alongside it (Dec 2024 vs Aug 2025), so it
likely holds a superseded key anyway.

Generating a fresh key is the cleaner path. Central does not require key continuity between
releases.

```bash
gpg --full-generate-key
```

| Prompt | Answer |
|---|---|
| Key type | `1` (RSA and RSA) |
| Key size | `4096` |
| Valid for | `2y` — renewable, and expiry does not invalidate past releases |
| Real name | `Featureflow` |
| Email | `admin@featureflow.io` |
| Passphrase | becomes `SIGNING_KEY_PASSWORD` |

**Put the passphrase and fingerprint in the password manager immediately.** Losing them means
repeating this, which is how the previous key was lost.

```bash
gpg --list-secret-keys --keyid-format=long     # fingerprint under `sec`; call it $KEY
```

**Publish the public key.** Central verifies signatures against a public keyserver and rejects the
upload without it:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys $KEY
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$KEY"    # expect 200
```

Export the private key and load it as a secret, then delete the file:

```bash
gpg --armor --export-secret-keys $KEY > codesigning.asc
gh secret set SIGNING_KEY -R featureflow/featureflow-android-sdk --env release < codesigning.asc
gh secret set SIGNING_KEY_PASSWORD -R featureflow/featureflow-android-sdk --env release
rm codesigning.asc
```

Back the key up to the password manager as well — `gpg --armor --export-secret-keys $KEY`.

| Secret | Value |
|---|---|
| `SIGNING_KEY` | the full ASCII-armoured private key, `-----BEGIN…` to `-----END…` inclusive |
| `SIGNING_KEY_PASSWORD` | its passphrase |

### 4. Environment

The `release` environment already exists, with deployment policies allowing the `main` branch
(for `dry_run`) and semver tags (for releases). A branch-only policy would **block** releases,
because a GitHub Release runs the workflow on the tag ref, not on `main`.

Hold the four secrets in that environment rather than at repository level. Add a required
reviewer there if you want a human gate — worth considering, since Central is immutable.

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

```bash
./gradlew :featureflow:publishToMavenLocal -PskipSigning=true    # no GPG key needed
```

Central requires four artifacts — `.aar`, `-sources.jar`, `-javadoc.jar`, `.pom` — plus a `.asc`
signature for each, and CI asserts all of them exist.

**Signing is on by default and `-PskipSigning` is the only way out.** It used to be the other way
round — sign only if a key looked present — which silently produced an unsigned bundle in CI,
caught only because the dry run checks for the `.asc` files. Verifying the outcome beats
verifying the input.

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
