# Android release v0.5.3: the formal tag, and the two gates that were red

Owner request, 2026-10-25. State: **in progress**.

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
| T1 | S3 | Verify `v0.5.3` → 5003 in the workflow; correct the stale `major * 10000 + minor * 100 + patch` in `docs/release-android.md` §2-§3 and in the lane comment in `scripts/ci-android.sh`; bump the debug placeholder in `build.gradle.kts` to 0.5.3 / 5003 | pending |
| T2 | S2, S3 | `CHANGELOG.md` `[0.5.3+5003]` section: the same four remediations, no functional change, and why the version moved | pending |
| T3 | S2 | `docs/release-android.md` §1 row for the 0.5.3 build, with the published numbers once there are any | pending |
| T4 | S1, S5 | Regenerate the stale `packages/contracts/openapi.json` so the `verify` job can go green: it is missing `MassasErrorResponse` from the 509 audit remediation | pending |
| T5 | S1, S5 | Stop `MassasLaunchViewModelTest` from leaking its polling view model into the next test, which is what makes the `android` job fail in `resetMain` | pending |
| T6 | S1 | Verify locally: the contract check, the Android lane (`scripts/ci-android.sh` in the pinned container) and the full suite | pending |
| T7 | S1, S4 | Push `main`, confirm CI green on that commit, tag `v0.5.3`, let the workflow publish, write the release body, verify the published APK and install it on the emulator | pending |
| T8 | S2, S4 | Close this record with the evidence and commit it | pending |

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
