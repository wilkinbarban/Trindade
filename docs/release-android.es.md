# Procedimiento de Release de Android — Trindade Massas

Este documento define el procedimiento oficial para empaquetar, firmar y publicar las versiones de producción de la aplicación móvil de Trindade Massas.

---

## 1. Principio Fundamental de Firma

Un archivo APK de producción posee una restricción inmutable en el sistema operativo Android:
**Una vez instalada en un dispositivo, la aplicación solo puede actualizarse si la nueva versión está firmada exactamente con el mismo certificado criptográfico (Keystore).**

Si se publica un APK firmado con una clave diferente o sin firmar, los teléfonos de los operarios rechazarán la actualización con el error `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, obligando a desinstalar la app y perdiendo los datos locales.

---

## 2. Origen y Reglas del Versionado

El número de versión **nunca se edita manualmente en el código**. Se deriva automáticamente a partir del tag de Git que inicia el proceso de release:

**Formato del tag: `v<major>.<minor>.<patch>`**

```text
versionName = <major>.<minor>.<patch>          (ej. v0.3.0 -> 0.3.0)
versionCode = major * 10000 + minor * 100 + patch   (ej. v0.3.0 -> 300)
```

| Tag Git | `versionName` | `versionCode` | Propósito |
|---|:---:|:---:|---|
| `v0.1.0` | `0.1.0` | `100` | Versión inicial de pruebas |
| `v0.2.0` | `0.2.0` | `200` | Primera versión con reportes y cargas |
| `v0.2.1` | `0.2.1` | `201` | Parche de verificación de actualizaciones |
| **`v0.3.0`** | **`0.3.0`** | **`300`** | **Paridad funcional total Android–Web (Actual)** |

---

## 3. Flujo Automatizado de Publicación en GitHub Actions

La publicación se realiza mediante el workflow `.github/workflows/android-release.yml`, el cual se activa exclusivamente al subir un tag de versión:

```bash
# 1. Asegurar que main está limpio y todas las pruebas pasan
make ci-clone

# 2. Crear y enviar el tag de release
git tag -a v0.3.0 -m "release: v0.3.0 - Paridade funcional Android-Web"
git push origin v0.3.0
```

### Acciones que ejecuta el pipeline en la nube:
1. Descarga el código correspondiente al tag dentro del contenedor oficial del SDK (`ghcr.io/cirruslabs/android-sdk:35`).
2. Deriva automáticamente `versionName=0.3.0` y `versionCode=300`.
3. Decodifica el Keystore protegido desde los GitHub Secrets (`ANDROID_KEYSTORE_BASE64`).
4. Ejecuta `:app:assembleRelease` con `-PrequireSigned=true` y `-PapiBaseUrl=https://trindademasas.duckdns.org/`.
5. Valida la firma del APK con `apksigner verify` y confirma que coincida con la huella oficial:
   `EE:0E:40:4B:21:10:52:B3:11:AE:0E:07:30:60:6E:9C:7D:55:54:31:54:63:D0:2F:9E:F3:3C:95:73:C4:31:F6`.
6. Publica el archivo `trindade-0.3.0.apk` directamente en la página de Releases de GitHub.

---

## 4. Secretos Requeridos en GitHub Actions

Configurados en *Settings → Secrets and variables → Actions*:

- `ANDROID_KEYSTORE_BASE64`: Clave de firma codificada en base64 de una sola línea.
- `ANDROID_KEYSTORE_PASSWORD`: Contraseña del almacén de claves (Keystore).
- `ANDROID_KEY_ALIAS`: Alias de la clave dentro del almacén (`trindade`).
- `ANDROID_KEY_PASSWORD`: Contraseña específica del alias.

Variable de repositorio (*Variables*):
- `ANDROID_API_BASE_URL`: `https://trindademasas.duckdns.org/`.

---

## 5. Resguardo y Seguridad del Keystore

El archivo de producción se encuentra en:
- **Local VPS**: `~/.android-keystores/trindade-release.jks`
- **Copia de seguridad externa en Windows**: `C:\Users\wilki\keystores\trindade-release.jks`
- **Hash SHA-256 verificado**: `ec185af21db6d2184fa10d073f44a234941046e9d4fa74a0eaf500f3f508a0ce`
