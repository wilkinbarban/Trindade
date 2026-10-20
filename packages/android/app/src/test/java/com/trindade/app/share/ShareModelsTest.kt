package com.trindade.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareModelsTest {

    @Test
    fun `buildShareMessage builds Android message without credentials in pt`() {
        val msg = buildShareMessage(
            ShareMessageParams(
                platform = SharePlatform.ANDROID,
                release = ShareReleaseInfo(version = "v0.5.0", apkUrl = "https://example.com/app.apk", webUrl = "https://example.com"),
                credentials = null,
                locale = "pt",
            ),
        )

        assertTrue(msg.contains("🥐 *Acesso ao Trindade Massas*"))
        assertTrue(msg.contains("📱 *Aplicativo Android:*"))
        assertTrue(msg.contains("Baixe e instale o APK oficial (v0.5.0):"))
        assertTrue(msg.contains("https://example.com/app.apk"))
        assertFalse(msg.contains("🌐 *Acesso Web"))
        assertFalse(msg.contains("🔐 *Suas Credenciais:*"))
        assertTrue(msg.contains("Qualquer dúvida ou suporte, procure a administração."))
    }

    @Test
    fun `buildShareMessage builds iOS message without credentials in pt`() {
        val msg = buildShareMessage(
            ShareMessageParams(
                platform = SharePlatform.IOS,
                release = ShareReleaseInfo(version = "v0.5.0", apkUrl = "https://example.com/app.apk", webUrl = "https://example.com"),
                credentials = null,
                locale = "pt",
            ),
        )

        assertTrue(msg.contains("🥐 *Acesso ao Trindade Massas*"))
        assertFalse(msg.contains("📱 *Aplicativo Android:*"))
        assertTrue(msg.contains("🌐 *Acesso Web (iPhone e Navegador):*"))
        assertTrue(msg.contains("https://example.com"))
    }

    @Test
    fun `buildShareMessage builds Both platforms with credentials and custom password in pt`() {
        val msg = buildShareMessage(
            ShareMessageParams(
                platform = SharePlatform.BOTH,
                release = ShareReleaseInfo(version = "v0.5.0", apkUrl = "https://example.com/app.apk", webUrl = "https://example.com"),
                credentials = ShareCredentials(
                    enabled = true,
                    displayName = "João Silva",
                    username = "joao.silva",
                    password = "CustomPassword123",
                ),
                locale = "pt",
            ),
        )

        assertTrue(msg.contains("Olá, *João Silva*! Seguem os seus acessos ao sistema:"))
        assertTrue(msg.contains("📱 *Aplicativo Android:*"))
        assertTrue(msg.contains("🌐 *Acesso Web (iPhone e Navegador):*"))
        assertTrue(msg.contains("🔐 *Suas Credenciais:*"))
        assertTrue(msg.contains("Usuário: *joao.silva*"))
        assertTrue(msg.contains("Senha: *CustomPassword123*"))
    }

    @Test
    fun `buildShareMessage builds Both platforms with credentials in es`() {
        val msg = buildShareMessage(
            ShareMessageParams(
                platform = SharePlatform.BOTH,
                release = ShareReleaseInfo(version = "v0.5.0", apkUrl = "https://example.com/app.apk", webUrl = "https://example.com"),
                credentials = ShareCredentials(
                    enabled = true,
                    displayName = "Carlos Pérez",
                    username = "carlos.perez",
                    password = "987654321",
                ),
                locale = "es",
            ),
        )

        assertTrue(msg.contains("🥐 *Acceso a Trindade Massas*"))
        assertTrue(msg.contains("Hola, *Carlos Pérez*! A continuación tienes tus accesos al sistema:"))
        assertTrue(msg.contains("📱 *Aplicación Android:*"))
        assertTrue(msg.contains("🌐 *Acceso Web (iPhone / Navegador):*"))
        assertTrue(msg.contains("🔐 *Tus Credenciales:*"))
        assertTrue(msg.contains("Usuario: *carlos.perez*"))
        assertTrue(msg.contains("Contraseña: *987654321*"))
        assertTrue(msg.contains("Cualquier duda o soporte, consulta a la administración."))
    }
}
