# Android release v0.5.3: the formal tag, and the two gates that were red

Owner request, 2026-10-25. State: **closed**.

The four remediations published as build 514 sit on the `v0.5.2` tag, whose assets were clobbered
twice. A clobbered tag cannot be moved and cannot be tagged again, so the release is cut formally as
`v0.5.3`, whose tag-derived `versionCode` (5003) is above the 514 already in the field.

## Specs

S1 (verbatim). "Wilkin dio luz verde para pasar este último release formalmente a la v0.5.3 en GitHub."
The release `v0.5.3` exists on GitHub, published by the tag workflow, carrying the signed APK and its
`.sha256`, and installs over the 514 build.

S2 (verbatim). "Prepará la documentación necesaria (CHANGELOG.md, docs/release-android.md y las notas de
la release)." The changelog gains a `0.5.3+5003` section, `docs/release-android.md` gains its verified row,
and the release body is written rather than generated.

S3 (verbatim). "Acordate de chequear la derivación de versiones en el workflow (con la fórmula actual
major*1000000 + minor*1000 + patch v0.5.3 mapea a 5003, superando a 514) y actualizar build.gradle.kts si
corresponde." The workflow encoding is verified against the tag, the stale copies of the old formula are
corrected, and the debug placeholder in `build.gradle.kts` follows the current line.

S4 (verbatim). "Creá la release formal v0.5.3 en GitHub con el APK firmado, su .sha256 y la documentación
cerrada."

S5 (verbatim). "Todo directo a main, sin ramas y sin Co-Authored-By."

## Tasks

| Task | Specs | Work | Commit |
| --- | --- | --- | --- |
| T1 | S3 | Verify `v0.5.3` → 5003 in the workflow; correct the stale `major * 10000 + minor * 100 + patch` in `docs/release-android.md` §2-§3 and in the lane comment in `scripts/ci-android.sh`; bump the debug placeholder in `build.gradle.kts` to 0.5.3 / 5003 | 54e9c5c |
| T2 | S2, S3 | `CHANGELOG.md` `[0.5.3+5003]` section: the same four remediations, no functional change, and why the version moved | 54e9c5c |
| T3 | S2 | `docs/release-android.md` §1 row for the 0.5.3 build, with the published numbers once there are any | this commit |
| T4 | S1, S5 | Regenerate the stale `packages/contracts/openapi.json` so the `verify` job can go green: it is missing `MassasErrorResponse` from the 509 audit remediation | f861329 |
| T5 | S1, S5 | Stop `MassasLaunchViewModelTest` from leaking its polling view model into the next test, which is what makes the `android` job fail in `resetMain` | 11710c3 |
| T6 | S1 | Verify locally: the contract check, the Android lane (`scripts/ci-android.sh` in the pinned container) and the full suite | 1029 tests, 0 failures |
| T7 | S1, S4 | Push `main`, confirm CI green on that commit, tag `v0.5.3`, let the workflow publish, write the release body, verify the published APK and install it on the emulator | 54e9c5c + tag `v0.5.3` |
| T8 | S2, S4 | Close this record with the evidence and commit it | this commit |

## Log

L1 (verbatim). "Wilkin dio luz verde para pasar este último release formalmente a la v0.5.3 en GitHub.
Prepará la documentación necesaria (CHANGELOG.md, docs/release-android.md y las notas de la release).
Acordate de chequear la derivación de versiones en el workflow (con la fórmula actual major*1000000 +
minor*1000 + patch v0.5.3 mapea a 5003, superando a 514) y actualizar build.gradle.kts si corresponde. Creá
la release formal v0.5.3 en GitHub con el APK firmado, su .sha256 y la documentación cerrada. Todo directo
a main, sin ramas y sin Co-Authored-By."

L2: The workflow's own comment already names the pair, so the encoding is not in doubt: "v0.5.2 is 5002,
v0.5.3 is 5003". The documentation lags it in three places — §2 still teaches `major * 10000 + minor * 100
+ patch`, §3 still refuses a `minor` above 99 with the 20000 collision example, and the Android lane comment
in `scripts/ci-android.sh` repeats the old formula — and those are corrected here, because a release page
that teaches the wrong encoding is how the next tag gets chosen wrongly.

L3: Two jobs of `ci.yml` were red before this task started, and both block the documented precondition of
§3 step 1 ("a tag on `main` from which the CI workflow passed"). `verify` fails because
`packages/contracts/openapi.json` was never regenerated after the 509 audit remediation added
`MassasErrorResponse`; `android` fails in `MassasLaunchViewModelTest`'s `tearDown` with
`IllegalStateException` from `TestMainDispatcher` ("Dispatchers.Main is used concurrently with setting it"),
because the view model created in `setUp` keeps polling on `viewModelScope` while the test resets `Main`.
Both are repaired as their own commits before the tag, since a release cut from a red tree is precisely what
the procedure forbids.

## Closing evidence

L4: The tree went green before the tag, which is the precondition §3 step 1 asks for. Run `40189095006` on
`54e9c5c` reports `verify: success` and `android: success`; the two red jobs that opened this task are the
two that now pass, so the repairs in `f861329` (contract regeneration) and `11710c3` (scope cancelled before
`resetMain`) are what closed them rather than a retry that got lucky.

L5: The tag published itself. `v0.5.3` was pushed as an annotated tag on `54e9c5c`, and run `40190131112`
came back with `build: success` and `publish: success` — the first release this workflow has produced without
an operator uploading or fixing anything afterwards. The assets are `trindade-0.5.3.apk` (10,964,228 bytes)
and `trindade-0.5.3.apk.sha256`, the sidecar written by the job that uploaded the APK.

L6: The published bytes, not the build directory, are what was verified. Both assets were fetched over HTTPS
from the release; `sha256sum -c` printed `trindade-0.5.3.apk: OK`; `apksigner` inside the pinned SDK 35
container printed `Verifies` with v2 only (v1, v3, v3.1 and v4 absent), one signer, `CN=Trindade Massas
Operacoes`, certificate SHA-256 `ee0e404b…431f6`; `aapt2 dump badging` printed `versionCode='5003'` and
`versionName='0.5.3'`. Published `sha256` is
`b658a952a5863e3a7bba207e98f0781450e2087f9a70dd1b2bc480f7e1d1fb58`.

L7: The upgrade path was exercised on the Ubuntu `emulator-5554`, which was carrying the 514 build
(`versionCode=514`, `versionName=0.5.2`) from the previous release. `adb install -r` of the downloaded
`trindade-0.5.3.apk` printed `Success` and left `versionCode=5003` / `versionName=0.5.3` in place, with no
uninstall and no data loss; the app opened on the dashboard without the mandatory-update prompt, because the
installed binary is now the one the newest release advertises. The profile reports `Versão do aplicativo
0.5.3` and `Você já está na versão mais recente.` with the outlined `Verificar atualizações` action only —
no filled download button, which is the same rule the 514 work introduced, now exercised against a real
release rather than a stub.

L8: The release body is curated rather than generated, as S2 asks: it explains why a 0.5.3 exists at all
(the tag cannot be moved and its assets were replaced twice), what the four remediations are, how to install
and check the download, and what was verified. The title follows the line's shape,
`Trindade Android v0.5.3 — A mesma entrega da 0.5.2, na versão que a tag declara`.

L9: One documentation row was corrected beyond the release itself: the `scripts/ci-android.sh` entry said the
lane had not been re-run since the version properties and the tightened grep landed. It has now been run end
to end in the container, so the row says what happened instead of what was assumed.
