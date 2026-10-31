# Actualizador contra el backend de Trindade

El chequeo de actualización de la app Android deja de preguntarle a GitHub y le pregunta a nuestro backend, que es
quien sabe qué binario sirve esta instalación.

## Specs

S1. El chequeo consulta `GET /api/app/latest-release` de la API del proyecto, a través del cliente de backend
(`@BackendClient`). El dispositivo no hace ninguna consulta a un host de terceros: sin `api.github.com`, sin token de
GitHub y sin el presupuesto de rate limit que devolvía `403` a la consulta del operario.

S2. La respuesta se tipa con el modelo ya generado en el contrato Kotlin,
`com.trindade.app.contract.models.LatestReleaseResponse`, y el actualizador lee `version`, `apkUrl`, `apkSize` y
`publishedAt` de ahí. Los tipos del payload de GitHub (`GitHubRelease`, `GitHubAsset`) y la interfaz que los pedía
(`GitHubReleaseApi`) se eliminan de la app.

S3. La comparación de versiones no cambia: `version` mayor que la instalada es una actualización; igual entra en las
reglas de parche; menor no ofrece nada, porque degradar la app no es una actualización; y una versión ilegible es
"no se pudo verificar".

S4. Las reglas de parche para la misma versión se adaptan a los campos que el backend publica: si hay `apkSize` y
tamaño instalado, el parche es un tamaño distinto; si el tamaño no alcanza, `publishedAt` posterior a la hora de
compilación es un parche. No hay regla de digest porque el backend no publica ninguno.

S5. La descarga usa el `apkUrl` que devuelve el backend (`/api/app/download-apk`), no una URL de GitHub. Un `apkUrl`
vacío no ofrece descarga: la pantalla responde "no se pudo verificar" en lugar de un botón que no lleva a ningún lado.

S6. La descarga del APK mantiene un cliente HTTP sin token y con los timeouts largos que ya tenía, renombrado según el
destino que describe (un archivo, no GitHub). El JWT de este proyecto no viaja a un host de terceros y una descarga de
once megabytes no se corta por el timeout de una llamada de API.

S7. Un fallo de transporte, una respuesta que no sea 2xx (incluidos el `403` y el `502` que la ruta declara) y un
cuerpo que no decodifica son todos "no se pudo verificar": nunca un crash, nunca una actualización ofrecida de mentira
y nunca una degradación.

## Tasks

| ID | Specs | Qué hace | Riesgo | Commit |
| --- | --- | --- | --- | --- |
| T1 | S1, S2, S5, S7 | `SystemApi.latestRelease()` y `UpdateRepository` sobre el backend, con test de ruta, decodificación y respuestas no exitosas | — | |
| T2 | S3, S4 | `UpdateDetector` tipado con `LatestReleaseResponse`, con las reglas de parche de tamaño y fecha | — | |
| T3 | S5, S7 | `ProfileViewModel` y `MainActivity` consumen `apkUrl`/`apkSize`/`publishedAt` del backend | — | |
| T4 | S2, S6 | Se borran `GitHubReleaseApi`, los modelos de GitHub, el Retrofit de GitHub y el `GITHUB_API_BASE_URL`; el cliente sin token pasa a nombrar su destino real | — | |
| T5 | S1 | Documentación del actualizador y suite completa en el contenedor fijado | — | |

## Log

### L1 — Pedido del usuario (verbatim)

> Revisé la causa raíz del error de actualización en Android:
>
> El actualizador interno en la app ( y  en ) todavía está hardcodeado apuntando a GitHub (). En GitHub la última versión publicada es v0.5.3 (no hay releases públicas para 0.6.x ni 0.7.0 por política del proyecto), por lo que la consulta o falla por rate limit 403 de GitHub o intenta degradar a 0.5.3 provocando error.
>
> La remediación es desacoplar el actualizador de GitHub y conectarlo directamente a nuestro backend:
> 1. Agregar en la API del backend ( o  bajo ) la consulta a , tipada con el modelo existente .
> 2. Adaptar  y  para que consuman  (version, apkUrl, apkSize) del backend de Trindade en lugar del payload de GitHub.
> 3. El download del APK debe usar la  devuelta por el backend ().
>
> Avanzá con la especificación y los tests bajo TDD directo en main.

### L2 — Nombres que el pedido dejó implícitos (verbatim)

> - Endpoint en backend: GET /api/app/latest-release
> - Modelo ya generado en Kotlin contract: com.trindade.app.contract.models.LatestReleaseResponse (campos version, apkUrl, apkSize, publishedAt)
> - Interfaz en Android: agregar latestRelease() a SystemApi (o AppApi) anotada con @BackendClient
> - Actualizador en Android: adaptar UpdateRepository y UpdateDetector para evaluar LatestReleaseResponse en vez de GitHubRelease.
> - Descarga: consumir la apkUrl que entrega el backend (/api/app/download-apk).
