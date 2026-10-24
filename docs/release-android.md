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
| The **0.4.0** release APK (versionCode 400) | Verified on 2026-10-14 in the pinned SDK container: `Verifies`, signed with the v2 scheme and a single signer whose SHA-256 digest is the fingerprint in Section 6, `aapt2` reports `versionCode='400'` and `versionName='0.4.0'`, the generated `BuildConfig` carries `API_BASE_URL = "https://trindademasas.duckdns.org/"` with `DEBUG = false`, the packaged manifest is not debuggable, and no `app-release-unsigned.apk` sits beside it. The APK is 10,440,140 bytes, `sha256 11d219393ae457e6776c0c675b9fed634f6850043e8c283658c214a18ecfc273`. **Not published**: it was built from `main` and no tag has been pushed, so the release workflow has not run for this version. |
| The **0.5.0** release APK (versionCode 501) | Verified on 2026-10-20 in the pinned SDK 35 container: `Verifies` with APK Signature Scheme v2 under `CN=Trindade Massas Operacoes`, `versionCode=501`, `versionName=0.5.0`, `API_BASE_URL="https://trindademasas.duckdns.org/"`. Exact size `10,588,860` bytes, `sha256 32ab931c338dd94700cfd79f91e7abb7d349c09b84d8ea0c811f77a18388adf1`. Published/clobbered on GitHub Release `v0.5.0`. |
| The **0.5.1** release APK (versionCode 506) | Verified in the pinned SDK 35 container: `Verifies` with APK Signature Scheme v2 under `CN=Trindade Massas Operacoes` (certificate SHA-256 `ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6`), `aapt2` reports `versionCode='506'`, `versionName='0.5.1'`, `API_BASE_URL="https://trindademasas.duckdns.org/"`, and `massas_read_only_badge` no longer appears in the packaged resources. Exact size `10,814,260` bytes, `sha256 0d0912a9162c654154956916d50bccfc8ad3c9625ee51f34c896d2774c3a670b`. Automatic system locale i18n (`pt-BR` and `es` parity), binary digest comparison for update checks, strict read-only mode for historical dates in Massas, and a header with a single "Voltar" action, the bold `titleLarge` title and the production date on its own line (no read-only badge). Published/clobbered on GitHub Release `v0.5.1`. |
| The **0.5.1** release APK (versionCode 507) | Verified on 2026-10-22 in the pinned SDK 35 container: `Verifies` with APK Signature Scheme v2 (v1 and v3 absent, one signer) under `CN=Trindade Massas Operacoes` (certificate SHA-256 `ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6`), `aapt2` reports `versionCode='507'` and `versionName='0.5.1'`, the generated `BuildConfig` carries `API_BASE_URL = "https://trindademasas.duckdns.org/"`, and no `app-release-unsigned.apk` sits beside it. Exact size `10,814,264` bytes, `sha256 3d03d5819423c05d9cc9bc61b28227363a4ce751bd86085bc8859cb725675cf7`. Massas: individual launches now above the collective summary, the worker filter applied to both, worker names per product (`👤 João Silva, Maria Souza`), no day total, amber product cards with dedicated `cx` and `un` badges, and a WhatsApp preview with `Resumo Coletivo` plus `*Por Trabalhador:*` and the leading `MASSA` prefix removed. Published/clobbered on GitHub Release `v0.5.1` as `trindade-0.5.1.apk`, with `trindade-0.5.1.apk.sha256` matching. The stale `app-release.apk` that the previous release left on the same tag was clobbered with these same bytes: `UpdateDetector` takes the first asset whose name ends in `.apk`, so leaving an older build under that name would have handed the updater the 506 binary. |
| The **0.5.1** release APK (versionCode 508) | Superseded the 507 build so that the report export fix ships with the same version name. Verified in the pinned SDK 35 container: `Verifies` with APK Signature Scheme v2 (one signer) under `CN=Trindade Massas Operacoes` (certificate SHA-256 `ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6`), `aapt2` reporting `versionCode='508'` and `versionName='0.5.1'`, `API_BASE_URL = "https://trindademasas.duckdns.org/"` in the release `BuildConfig`. Exact size `10,814,264` bytes, `sha256 091f5cf7be05b3214fedf75ec29b8c56cda0d2da27608d3e894734301522ae19`. Reports: the exported WhatsApp text no longer keeps the `MASSA` prefix on product lines of `Por Operário:` and `Resumo Coletivo:`. Published/clobbered on GitHub Release `v0.5.1` as `trindade-0.5.1.apk`, with `trindade-0.5.1.apk.sha256` and the leftover `app-release.apk` carrying the same bytes. |
| The **0.5.1** release APK (versionCode 509) | Carries the audit remediation on the same version name. Verified in the pinned SDK 35 container with `:app:testDebugUnitTest :app:assembleRelease -PversionCode=509 -PversionName=0.5.1 -PrequireSigned=true` (969 unit tests, 0 failures): `Verifies` with APK Signature Scheme v2 (v1 and v3 absent, one signer) under `CN=Trindade Massas Operacoes` (certificate SHA-256 `ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6`), `aapt2` reporting `versionCode='509'` and `versionName='0.5.1'`, `API_BASE_URL = "https://trindademasas.duckdns.org/"` in the release `BuildConfig`, and no `app-release-unsigned.apk` beside it. Exact size `10,879,792` bytes, `sha256 1545739f45625f711ef6b4fbe05faa7fbdfb204d11f37d9c588b6a49ffb58fd5`. Web renews the session with the refresh token; Massas answers `{ error, code }` and refuses future dates; the seven admin screens and the Massas, Stock and Report view models take their text from the locale catalogs (622 keys in pt and es); editing a launch is the author's alone; date pickers open in `America/Sao_Paulo`; a failed vehicle read is reported with a retry. Published/clobbered on GitHub Release `v0.5.1` as `trindade-0.5.1.apk`, with `trindade-0.5.1.apk.sha256` and the leftover `app-release.apk` carrying the same bytes, and the identical verified file copied to the Desktop. |
| The **0.5.1** release APK (versionCode 510) | Closes the administration ViewModel gap on the same version name. Verified in the pinned SDK 35 container with :app:testDebugUnitTest :app:assembleRelease -PversionCode=510 -PversionName=0.5.1 -PrequireSigned=true: 969 unit tests, 0 failures; apksigner Verifies with APK Signature Scheme v2 (v1 and v3 absent, one signer) under CN=Trindade Massas Operacoes (certificate SHA-256 ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6); aapt2 reports versionCode='510' and versionName='0.5.1'; no app-release-unsigned.apk beside it. Exact size 10,885,848 bytes, sha256 4d8440dbc66ee17adee22916406ca9497b66a2a428031bd440194cf58bb94b55. Admin: Users, Vehicles, Categories, Drivers, Tasks, Time slots and Audit view models carry their messages as UiText and the screens resolve them, 12 new keys per locale with 634 keys in pt and es and the Portuguese text byte-identical to 509. Published/clobbered on GitHub Release v0.5.1 as trindade-0.5.1.apk, with trindade-0.5.1.apk.sha256 and the leftover app-release.apk carrying the same bytes, and the identical verified file copied to the Desktop. |
| The **0.5.1** release APK built as versionCode 511 | Carries the stock message without the product code and the day's collective summary on both platforms. Verified in the pinned SDK 35 container with :app:testDebugUnitTest :app:assembleRelease -PversionCode=511 -PversionName=0.5.1 -PrequireSigned=true: 974 unit tests, 0 failures; apksigner Verifies with APK Signature Scheme v2 (v1 and v3 absent, one signer) under CN=Trindade Massas Operacoes (certificate SHA-256 ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6); aapt2 reports versionCode='511' and versionName='0.5.1'; aapt2 dump strings finds 'Resumo Coletivo do Dia' in the packaged resources; no app-release-unsigned.apk beside it. Exact size 10,888,124 bytes, sha256 04f3848bc2194fda7262bb3ce916122cb74942a96dba3fa6174995bd2554f593. Published/clobbered on GitHub Release v0.5.1 as app-release.apk, trindade-0.5.1.apk and trindade-0.5.1.apk.sha256, and the identical verified file copied to the Desktop. |
| The **0.5.2** release APK (versionCode 512) | Carries the Android design remediation: seventeen vector icons, the icon-only header, an eight-tab row, a thirteen-entry drawer in two groups, Tarefas and Motoristas folded into the admin panel for both roles, module roots without a repeated title or a way back, and a stock counter that always counts today. Verified in the pinned SDK 35 container with `:app:testDebugUnitTest :app:assembleRelease -PapiBaseUrl=https://trindademasas.duckdns.org/ -PversionName=0.5.2 -PversionCode=512 -PrequireSigned=true`: 975 unit tests, 0 failures; `apksigner` Verifies with APK Signature Scheme v2 (v1 and v3 absent, one signer) under CN=Trindade Massas Operacoes (certificate SHA-256 ee0e404b211052b311ae0e0730606e9c7d5554315463d02f9ef33c9573c431f6); `aapt2` reports versionCode='512' and versionName='0.5.2'; no app-release-unsigned.apk beside it. Exact size 10,900,292 bytes, sha256 f302cf5dc33e0e82fad0341df2689c4bc5c7cda70b00c6ca8009294d9e26811a. Checked on the Ubuntu `emulator-5554` before publishing: dashboard, drawer, admin panel, stock counter and massas launch, plus the released binary opening without the mandatory-update prompt. Published on GitHub Release `v0.5.2` as `trindade-0.5.2.apk` with `trindade-0.5.2.apk.sha256` describing those same bytes. Re-verified from the published download rather than from the build directory: both release files fetched over HTTPS, `sha256sum -c` reports `trindade-0.5.2.apk: OK`, `apksigner` on the downloaded APK reports v2 only (v1, v3, v3.1, v3.2 and v4 absent, one signer) with certificate SHA-256 `ee0e404b…431f6`, no unsigned APK beside it, and an `adb install -r` of that download over the 502 build succeeded in place and left the app on the dashboard at `versionCode=512`, which is the upgrade path a 0.5.1 install takes. The versionCode is 512 and not the code the workflow derived for this tag (502) because `v0.5.1` had been published by hand as 511: Android compares that number to decide what an update is, so the 502 build was one every 0.5.1 install would have refused as a downgrade. The published asset is the 512 build, and the derivation was widened below so that the next tag lands above it. |
| The absent-keystore path | Verified. `-PrequireSigned=true` with no keystore fails the build with the guard message in Section 5 and produces no APK at all. |
| `scripts/ci-android.sh` | Not re-run end to end after this change. The release build it performs and the version properties it passes are the ones exercised by the signed build below, and the guard sentence its check greps was checked directly against the guard (Section 9), but the script itself has not been run since the version properties and the tightened grep were added. |
| `.github/workflows/android-release.yml` | **Ran for the first time on the `v0.5.2` tag, and the run found two defects in it.** Both are fixed here and both are the kind only an execution shows, which is why this row no longer says UNVERIFIED.
| The version derivation | Was `major * 10000 + minor * 100 + patch`. On the 0.x line the major contributes nothing above the minor, so `v0.5.2` derived **502** while `v0.5.1` was already in the field as 511: the release the tag produced was a downgrade no 0.5.1 install would accept. Now `major * 1000000 + minor * 1000 + patch`, with the digit budget and the collision it prevents written next to it. Verified by extracting this step from the workflow file and running it over a table of refs: `v0.3.0`→3000, `v0.5.2`→5002, `v0.5.3`→5003, `v0.6.0`→6000, `v1.0.0`→1000000, the repair branch→301, every tag code above the published 512 and increasing, and `v0.5.1000`, `v1.1000.0`, `v0.5`, `refs/heads/main` and a mismatched tag name all failing closed. |
| The release checksum | The publish job published only `release-assets/*.apk`; the `trindade-*.apk.sha256` beside it was something an operator uploaded by hand. On `v0.5.2` that produced the worst possible pair: the tag push made the workflow replace the APK with its own build, while the checksum kept describing the file it had just replaced, so the release advertised a digest no download could match. The publish job now writes the sidecar itself, with `sha256sum` over the exact bytes it is about to upload, and refuses to publish anything but the APK plus that sidecar. Verified by running the step's own body over a scratch `release-assets` directory: the sidecar is written, `sha256sum -c` passes against it, the publish guard counts two assets, and an empty artifact set fails closed. |
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
   - checks out the tagged commit, inside the SDK container
     `ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9`
     (the same image `make ci-android` uses locally, so the toolchain is the one the lane is verified
     against);
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

The digest, and not only the tag, is what pins the toolchain here, and it is written in **six places across
four files**, all of which must move together: the two `docker run` lines in the `Makefile`
(`make ci-android`), `.github/workflows/ci.yml`, `.github/workflows/android-release.yml`, and **two copies
on this page** — the container the release workflow checks out (Section 3) and the local build command in
Section 5. There is deliberately no shared variable file or script between them: the image changes once or
twice a year, and a mechanism would cost more to keep correct than the six edits it saves. When the image
moves, change all six to the same digest, this page included: its two copies are the ones a maintainer
reads and copies from, so a page left teaching the old digest is exactly the drift this paragraph exists
to prevent, and it is the copy nothing else can catch.

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
| `ANDROID_API_BASE_URL` | The base URL the release APK talks to, e.g. `https://trindademasas.duckdns.org/`. It must be an `https://` URL with a host, and end with `/`. |

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
  ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9 \
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
