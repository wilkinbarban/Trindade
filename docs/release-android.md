# Android Release Procedure — Trindade Massas

This is the procedure for cutting a signed Android release of the Trindade app: where its version comes
from, how it is signed and published, how to build one by hand, what has to be backed up, and where the
crew downloads the APK from.

It exists because a release APK has one property no other artifact in this repository has: **it can never
be replaced once it is installed on a phone.** Android refuses an APK signed with a different key over an
existing install, so a release built with the wrong key — or with no key at all — is not a bad release,
it is no release. Everything below follows from that one fact.

Everything here is about the Android client in `packages/android/`. The backend and web deployment
procedure is `docs/deployment.md`.

---

## 1. What has been verified, and what has not

Stated first, because the difference matters when a release fails at 22:00.

| Part | Status |
| --- | --- |
| The keystore at `~/.android-keystores/trindade-release.jks` | Used to build and sign a real release APK, which `apksigner` reports as signed by an RSA 4096 certificate with the fingerprint in Section 6 and DN `CN=Trindade Massas Operacoes, ...`. The store type, the alias, the self-signed status and the 2054 validity date come from the operator's own record of how the key was created and were **not** re-checked while writing this document. |
| A signed release built locally from the keystore | Verified. The build, the `apksigner verify --print-certs` result and the certificate digest that matched the fingerprint are recorded in Section 5. |
| The absent-keystore path | Verified. `-PrequireSigned=true` with no keystore fails the build with the guard message in Section 5 and produces no APK at all. |
| `scripts/ci-android.sh` | Not re-run end to end after this change. The release build it performs and the version properties it passes are the ones exercised by the signed build below, and the guard sentence its check greps was checked directly against the guard (Section 9), but the script itself has not been run since the version properties and the tightened grep were added. |
| `.github/workflows/android-release.yml` | **UNVERIFIED.** A GitHub-hosted runner cannot be run from the development host, so this workflow has never executed. Every command in it was run by hand inside the same container image before being written there, but the workflow's own wiring — the secret injection, the artifact hand-off between jobs, and the `gh release` call — is untested. **The first tag push is its test.** |
| The secrets named in Section 4 | Not verified as configured; they are configured on GitHub, outside this repository. |

---

## 2. The version comes from the git tag

No human edits the version. The release workflow derives it from the tag, and the tag is the single source
of truth for both numbers Android records in the APK.

**For a tag `v<major>.<minor>.<patch>`:**

```
versionName = <major>.<minor>.<patch>          e.g. v1.2.3  ->  1.2.3
versionCode = major * 10000 + minor * 100 + patch   e.g. v1.2.3 -> 10203
```

| Tag | versionName | versionCode |
| --- | --- | --- |
| `v0.0.1` | `0.0.1` | `1` |
| `v1.0.0` | `1.0.0` | `10000` |
| `v1.2.3` | `1.2.3` | `10203` |
| `v2.0.0` | `2.0.0` | `20000` |

Three consequences worth knowing before choosing a tag:

- **`versionCode` is what Android compares.** It decides whether an install is an update. The encoding
  above grows with every release as long as tags grow in order, which is the whole reason for the formula
  rather than a hand-picked number.
- **`minor` and `patch` each have two digits of room.** `v1.100.0` would encode to `20000`, the same as
  `v2.0.0`, so the workflow **refuses** such a tag instead of publishing an APK Android would compare
  against the wrong version. Roll over to a new major instead.
- **The `v` belongs to the tag, not the version.** `versionName` is digits and dots; a build whose
  `-PversionName` carries a leading `v` fails the build's own validation.

### How the version reaches the APK

- `-PversionName` and `-PversionCode` define both numbers, and a **release build requires both**: the build
  has no default for a release, so an APK can never be published carrying a version the build invented
  instead of the one its tag declares. A debug build keeps the placeholder `0.1.0` / `1`, so local work
  needs no properties at all.
- The two values are computed **once** in `packages/android/app/build.gradle.kts` and used both for the
  manifest (`defaultConfig.versionName` / `.versionCode`) and for the `BuildConfig` fields the app reads.
  That is why the number on screen cannot drift from the number that was packaged.
- The app displays it in **Perfil**, under the label *Versão do aplicativo*, read from
  `BuildConfig.APP_VERSION_NAME`. That line is drawn outside the block that needs a loaded profile, because
  an operator asking "did the update land?" is exactly the situation where the profile may not have loaded.

---

## 3. Cutting a release

1. **Make sure the commit you are about to tag is green.** A tag on `main` from which the CI workflow
   (`ci.yml`) passed — including the JVM suite and the Android lane. The release workflow does not re-run
   the unit suite: it exists to sign and to verify the signature.
2. **Tag and push the tag** (the tag is the entire release procedure):

   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```

3. **The `Android release` workflow runs**, and in this order:
   - checks out the tagged commit, inside the SDK container `ghcr.io/cirruslabs/android-sdk:35` (the same
     image `make ci-android` uses locally, so the toolchain is the one the lane is verified against);
   - derives `versionName` and `versionCode` from the tag by the rule in Section 2 and **refuses three
     kinds of tag**: `v1.2` and `vabc`, which its three-component regex does not read as a version at
     all, and `v1.100.0`, which it does read but whose `minor` is above 99 — that one would encode to
     `20000`, the `versionCode` of `v2.0.0`, so it is refused instead of published as a collision;
   - decodes the keystore from `ANDROID_KEYSTORE_BASE64` into the runner's temporary directory (never into
     the workspace) and deletes it again at the end of the job;
   - builds `:app:assembleRelease` with `-PapiBaseUrl`, `-PversionName`, `-PversionCode` and
     `-PrequireSigned=true`;
   - **verifies the APK before anything is published**: it confirms the output is `app-release.apk` and not
     `app-release-unsigned.apk`, runs `apksigner verify`, and compares the signer's SHA-256 certificate
     digest against the fingerprint pinned in the workflow (Section 6). A mismatch fails the release;
   - asserts the packaged version — `aapt2 dump badging` must report the `versionName` and `versionCode`
     the tag produced;
   - publishes the APK as the release asset `trindade-<version>.apk` on the GitHub release for the tag.

4. **If the run fails**, nothing was published. Fix the cause and re-run the failed job: the publish step
   updates an existing release for the tag in place rather than failing with "release already exists".

5. **Never move a tag.** Re-pointing a published tag at a different commit means two different APKs claim
   the same `versionCode`, and Android will install whichever one it sees.

---

## 4. Secrets and variables the workflow needs

Configure these **once**, on GitHub, before the first release. They are repository secrets
(*Settings → Secrets and variables → Actions*), and none of them belongs in a tracked file.

| Secret | Contents |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The release keystore, base64-encoded, as one line. |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore (store) password. |
| `ANDROID_KEY_ALIAS` | The key alias inside the keystore: `trindade`. |
| `ANDROID_KEY_PASSWORD` | *Optional.* The key password, only if it differs from the store password. A PKCS12 store built with `keytool` uses one password for both, so this is normally **left unset** and the build falls back to the store password. |

The command that produces the base64 value for the first one, run on the machine that holds the keystore:

```bash
# Linux
base64 -w0 ~/.android-keystores/trindade-release.jks
```

```bash
# macOS (base64 has no -w)
base64 -i ~/.android-keystores/trindade-release.jks | tr -d '\n'
```

The value is one long line with no newline; a trailing newline would survive `base64 --decode` harmlessly,
but an unencoded keystore would not, which is why the workflow fails loudly if decoding yields an empty
file. With the `gh` CLI the whole configuration is:

```bash
gh secret set ANDROID_KEYSTORE_BASE64 --body "$(base64 -w0 ~/.android-keystores/trindade-release.jks)"
gh secret set ANDROID_KEYSTORE_PASSWORD          # prompts, so the password stays out of your shell history
gh secret set ANDROID_KEY_ALIAS --body trindade
```

**Recommended repository variable** (*Settings → Secrets and variables → Actions → Variables*):

| Variable | Contents |
| --- | --- |
| `ANDROID_API_BASE_URL` | The base URL the release APK talks to, e.g. `https://trindademasas.duckdns.org/`. It must be `https://` and end with `/`. |

When unset, the workflow falls back to `https://trindademasas.duckdns.org/`, the same host
`.env.example` and `docs/deployment.md` name. A release build has no default of its own, so a base URL
that is missing, non-`https` or without its trailing slash fails the build rather than shipping an APK
that points at the development loopback.

**No secret holds the certificate fingerprint.** The fingerprint pinned in the workflow is public
(Section 6): pinning it is what turns "the APK is signed" into "the APK is signed by the key whose
fingerprint we published".

---

## 5. Building and signing an APK locally

The keystore and its password are supplied **from outside the repository** — through `-P` properties, the
environment, or a gitignored `keystore.properties`. They are never written into a tracked file, and the
keystore itself lives at `~/.android-keystores/trindade-release.jks`, never in the checkout.

### The three sources, and their precedence

The same four names are read in all three places, in this order — a `-P` property beats the environment,
which beats the properties file. `keystore.properties` is read from the app module
(`packages/android/app/keystore.properties`) or from the Gradle root beside it
(`packages/android/keystore.properties`); either location works.

| `-P` property | environment variable | `keystore.properties` key |
| --- | --- | --- |
| `trindadeKeystorePath` | `TRINDADE_KEYSTORE_PATH` | `trindadeKeystorePath` |
| `trindadeKeystorePassword` | `TRINDADE_KEYSTORE_PASSWORD` | `trindadeKeystorePassword` |
| `trindadeKeyAlias` | `TRINDADE_KEY_ALIAS` | `trindadeKeyAlias` |
| `trindadeKeyPassword` *(defaults to the store password)* | `TRINDADE_KEY_PASSWORD` | `trindadeKeyPassword` |

```properties
# packages/android/app/keystore.properties — gitignored, never committed
trindadeKeystorePath=/home/<user>/.android-keystores/trindade-release.jks
trindadeKeystorePassword=<the store password>
trindadeKeyAlias=trindade
```

`keystore.properties` and `*.jks` / `*.keystore` / `*.p12` / `*.pfx` are in `.gitignore`, but ignoring a file is not permission
to keep it in the tree: the point of these rules is that no password and no key can be committed by
accident.

### A signed release, in the container the project builds with

```bash
docker run --rm --user "$(id -u):$(id -g)" \
  --env GRADLE_USER_HOME=/gradle-home --env HOME=/gradle-home \
  --env TRINDADE_KEYSTORE_PATH=/keys/trindade-release.jks \
  --env TRINDADE_KEYSTORE_PASSWORD=<the store password> \
  --env TRINDADE_KEY_ALIAS=trindade \
  --volume "$HOME/.cache/trindade-gradle:/gradle-home" \
  --volume "$HOME/.cache/trindade-android-sdk:/opt/android-sdk-linux" \
  --volume "$HOME/.android-keystores:/keys" \
  --volume "$(git rev-parse --show-toplevel):/work" \
  --workdir /work/packages/android \
  ghcr.io/cirruslabs/android-sdk:35 \
  bash -c './gradlew :app:assembleRelease --no-daemon -PapiBaseUrl=https://trindade.example/ -PversionName=1.0.0 -PversionCode=10000 -PrequireSigned=true'
```

`-PapiBaseUrl` carries a placeholder host on purpose: the real one is supplied by the release
(`ANDROID_API_BASE_URL` in Section 4), and what this build exists to prove is the signature, not the
endpoint.

**Verified on 2026-09-19** with this exact command against the real keystore. It produced
`packages/android/app/build/outputs/apk/release/app-release.apk` — **9,683,207 bytes**, and no
`app-release-unsigned.apk` beside it — and `apksigner verify --print-certs` reported `Verifies` with
`Signer #1 certificate SHA-256 digest: ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6`,
which matches the fingerprint in Section 6. It was signed with the **v2** scheme (`Verified using v2 scheme:
true`, v1 `false`), which is what the `minSdk` 26 floor requires: Android 8.0 and later verify v2, so
there is no reason to carry the older JAR signature.

### `-PrequireSigned=true`, and what happens without a keystore

**A missing keystore is not an error by itself.** Without one, `assembleRelease` produces
`app-release-unsigned.apk`, which is how `scripts/ci-android.sh` proves the release path still builds on a
machine that has no key — and it is also how a half-configured keystore would silently produce an APK that
looks signed until someone checks.

- **`-PrequireSigned=true`** makes an unresolvable keystore fatal, in the same voice as the base-URL guard
  and for the same reason: *a published APK that cannot be verified as coming from this project is worse
  than no APK.* The release workflow passes it; the CI lane does not.
- **A partially configured keystore is always fatal**, even without `-PrequireSigned=true`: if any of the
  three settings is present but the set is incomplete or the file is unreadable, the build fails and names
  what is missing, rather than quietly assembling an unsigned APK.

**Verified:** the same release build without the keystore environment and with `-PrequireSigned=true`
fails with

```
> A release build with -PrequireSigned=true requires a keystore, and none could be resolved. Provide
trindadeKeystorePath, trindadeKeystorePassword and trindadeKeyAlias as -P properties, as
TRINDADE_KEYSTORE_PATH, TRINDADE_KEYSTORE_PASSWORD and TRINDADE_KEY_ALIAS in the environment, or in
keystore.properties. A published APK that cannot be verified as coming from this project is worse than no
APK.
```

and produces no new artifact — the previously built signed APK is left untouched.

### Checking a locally built APK yourself

```bash
# The signature and the certificate, in one shot
/opt/android-sdk-linux/build-tools/<version>/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk

# What the APK actually declares
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
```

`apksigner` and `aapt2` are not on `PATH` in the SDK image; they live under
`$ANDROID_HOME/build-tools/<version>/`.

---

## 6. The key, and the fingerprint everyone compares against

| | |
| --- | --- |
| Keystore file | `~/.android-keystores/trindade-release.jks` (outside the repository) |
| Type / alias | PKCS12 / `trindade` |
| Key | RSA 4096, self-signed, valid until 2054 |
| Certificate DN | `CN=Trindade Massas Operacoes, OU=Mobile, O=Trindade, L=Sao Paulo, ST=SP, C=BR` |
| **SHA-256 fingerprint** | `EE:0E:40:4B:21:10:52:B3:11:AE:0E:07:30:60:6E:9C:7D:55:54:31:54:63:D0:2F:9E:F3:3C:95:73:C4:31:F6` |
| SHA-1 fingerprint | `D4:8B:1B:D4:D9:8D:76:24:ED:1D:74:BA:BA:C6:9B:11:9A:1A:33:DF` (informational; compare SHA-256) |

Compare fingerprints with either tool, and mind the format difference: `keytool` prints them
colon-separated, `apksigner` prints the same digest as bare lowercase hex.

```bash
# From the keystore itself
keytool -list -v -keystore ~/.android-keystores/trindade-release.jks -alias trindade | grep SHA256

# From a built APK — this is the check the release workflow automates
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep 'certificate SHA-256'
```

If the APK's digest does not match this fingerprint, **do not publish it and do not install it**: an
installed app signed with this key cannot be updated by an APK signed with another, so the wrong key is
the one mistake that cannot be repaired later.

---

## 7. Back up the keystore. This is the part that cannot be undone

**Losing the keystore means every installed copy of the app can never be updated again.** Not "needs a new
build": Android refuses to install an APK whose signing certificate differs from the installed one
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). The only way forward would be to uninstall the app on every phone —
losing whatever local state it holds — and install the new one from scratch.

- Back up `trindade-release.jks` **and its password**, and keep them **separate** from each other, in at
  least two places that are not this repository and not the machine that builds releases.
- Never copy the keystore into the checkout, and never write its password into a file git tracks. The
  `.gitignore` rules for `keystore.properties`, `*.jks`, `*.keystore`, `*.p12` and `*.pfx` exist as a backstop for an
  accident, not as a place to store it.
- The keystore is valid until 2054 and self-signed; there is no rotation path that keeps installed apps
  updatable, which is exactly why the file must outlive every laptop that has touched it.

---

## 8. Where the crew downloads the APK

Every release is published at:

**https://github.com/wilkinbarban/Trindade/releases/latest**

The asset is named `trindade-<version>.apk` (for example `trindade-1.2.3.apk`), and the release notes
list the commits since the previous tag. On the phone, open that link, download the APK and open it; the
installer asks to allow installing from this source the first time. The version that was installed is
readable in the app under **Perfil → Versão do aplicativo**, which is the answer to "did this phone get
the update?".

An update installs over the existing app only when both APKs come from the same signing key — see
Section 7 for why that makes the keystore the most important file in this procedure.

---

## 9. What the CI lane does, and deliberately does not

`scripts/ci-android.sh` (`make ci-android`, and the `android` job in `.github/workflows/ci.yml`) builds a
release, but it does so **unsigned and keystore-free**, and that is the point:

- it passes `-PversionName=0.0.1 -PversionCode=1`, consistent placeholders, because a release build
  requires a version and this lane is not a release;
- it passes `-PapiBaseUrl=https://ci.invalid/`, a URL that is valid and deliberately unresolvable;
- it does **not** pass `-PrequireSigned=true`, so the unsigned path stays exercised;
- it asserts that a release build without a base URL fails with the base-URL guard's own sentence,
  `A release build requires -PapiBaseUrl=`. That assertion passes the version properties and greps that
  longer prefix on purpose: three rules now open with the words "A release build requires", and a check
  that greps only that prefix could be satisfied by a build that failed because no version was given.

The only place a keystore exists is the release workflow, and the only place the version stops being a
placeholder is a tag.
