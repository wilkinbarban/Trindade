package com.trindade.app.share

/**
 * Pure models and message builder for sharing the application via WhatsApp or other channels.
 */

/** How the credentials in the message got an account: created here, or picked from the ones that exist. */
enum class ShareCredentialMode {
    CREATE,
    EXISTING,
}

/** The role a worker created from this screen gets, and nothing else. */
const val QUICK_WORKER_ROLE_ID = 2

/** The digits a generated password is padded with, in order, as long as they are needed. */
internal const val PASSWORD_SEQUENCE = "1234567890123456789012345678901234567890"

/** The floor every generated password meets. */
internal const val PASSWORD_MIN_LENGTH = 15

/**
 * The temporary password handed to a new operator: the login plus the running digit sequence.
 *
 * Written out rather than randomized so the same login always yields the same password, which is what lets the
 * screen show the value before the account exists. It mirrors the web screen character for character.
 */
fun quickWorkerPassword(username: String): String {
    val base = username.trim().lowercase().take(PASSWORD_MIN_LENGTH)
    if (base.length >= PASSWORD_MIN_LENGTH) return base
    return base + PASSWORD_SEQUENCE.take(PASSWORD_MIN_LENGTH - base.length)
}

/**
 * The login a display name suggests: lower-case, no accents, words joined with dots.
 *
 * A suggestion, not a rule: the admin can type over it, so it strips whatever a login cannot carry instead of
 * trying to transliterate it.
 */
fun deriveUsername(displayName: String): String {
    val withoutAccents = java.text.Normalizer.normalize(displayName, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return withoutAccents
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
}

/** Why the new worker could not be created; the screen turns each one into its own string. */
enum class ShareCreateError {
    NAME_REQUIRED,
    USERNAME_REQUIRED,
    DUPLICATED,
    REFUSED,
    UNREACHABLE,
}

enum class SharePlatform {
    ANDROID,
    IOS,
    BOTH,
}

data class ShareReleaseInfo(
    val version: String = "v0.5.0",
    val apkUrl: String = "https://trindademasas.duckdns.org/api/app/download-apk",
    val webUrl: String = "https://trindademasas.duckdns.org",
)

data class ShareCredentials(
    val enabled: Boolean = true,
    val displayName: String? = null,
    val username: String? = null,
    val password: String? = null,
)

data class ShareMessageParams(
    val platform: SharePlatform = SharePlatform.ANDROID,
    val release: ShareReleaseInfo = ShareReleaseInfo(),
    val credentials: ShareCredentials? = null,
    val locale: String = "pt",
)

fun buildShareMessage(params: ShareMessageParams): String {
    val platform = params.platform
    val release = params.release
    val credentials = params.credentials
    val isEs = params.locale.startsWith("es")

    val parts = mutableListOf<String>()

    val title = if (isEs) "🥐 *Acceso a Trindade Massas*" else "🥐 *Acesso ao Trindade Massas*"
    parts.add(title)

    val displayName = credentials?.displayName?.trim()
    if (!displayName.isNullOrEmpty()) {
        val greeting = if (isEs) {
            "Hola, *$displayName*! A continuación tienes tus accesos al sistema:"
        } else {
            "Olá, *$displayName*! Seguem os seus acessos ao sistema:"
        }
        parts.add(greeting)
    } else {
        val intro = if (isEs) {
            "A continuación tienes los accesos oficiales al sistema:"
        } else {
            "Seguem os acessos oficiais ao sistema:"
        }
        parts.add(intro)
    }

    if (platform == SharePlatform.ANDROID || platform == SharePlatform.BOTH) {
        val androidTitle = if (isEs) "📱 *Aplicación Android:*" else "📱 *Aplicativo Android:*"
        val androidAction = if (isEs) {
            "Descarga e instala el APK oficial (${release.version}):"
        } else {
            "Baixe e instale o APK oficial (${release.version}):"
        }
        parts.add("$androidTitle\n$androidAction\n${release.apkUrl}")
    }

    if (platform == SharePlatform.IOS || platform == SharePlatform.BOTH) {
        val webTitle = if (isEs) {
            "🌐 *Acceso Web (iPhone / Navegador):*"
        } else {
            "🌐 *Acesso Web (iPhone e Navegador):*"
        }
        val webAction = if (isEs) "Ingresa por el enlace oficial:" else "Acesse pelo link oficial:"
        parts.add("$webTitle\n$webAction\n${release.webUrl}")
    }

    if (credentials?.enabled == true && !credentials.username.isNullOrBlank()) {
        val credTitle = if (isEs) "🔐 *Tus Credenciales:*" else "🔐 *Suas Credenciais:*"
        val userLabel = if (isEs) "Usuario" else "Usuário"
        val passLabel = if (isEs) "Contraseña" else "Senha"
        val passValue = if (credentials.password.isNullOrBlank()) "123456789" else credentials.password.trim()

        parts.add("$credTitle\n$userLabel: *${credentials.username.trim()}*\n$passLabel: *$passValue*")
    }

    val footer = if (isEs) {
        "Cualquier duda o soporte, consulta a la administración."
    } else {
        "Qualquer dúvida ou suporte, procure a administração."
    }
    parts.add(footer)

    return parts.joinToString("\n\n")
}
