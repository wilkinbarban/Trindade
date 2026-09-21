package com.trindade.app.di

import com.trindade.app.logging.AndroidAppLogger
import com.trindade.app.logging.AppLogger
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The one binding the device's log line depends on.
 *
 * `AuthRepository` takes its `AppLogger` with a default of `NoOpAppLogger`, so a test that does not care
 * about logging does not have to name one. That default is safe only while every production instance
 * arrives through Hilt, which makes this single fact the thing keeping the log line alive on a phone:
 * `LoggingModule.provideAppLogger()` hands over `AndroidAppLogger`. Nothing asserted it until this test,
 * and a binding is exactly the kind of thing that changes without any other test noticing -- pointing
 * this provider at `NoOpAppLogger` would silence every failure log on every device, compile cleanly,
 * and leave the whole suite green.
 *
 * `assertSame` rather than `assertEquals`: both implementations are Kotlin `object`s, so identity is
 * the claim being made -- the provider hands over that implementation and not a copy that behaves
 * like it.
 */
class LoggingModuleTest {

    @Test
    fun `the logger binding hands the device its Android implementation`() {
        val logger: AppLogger = LoggingModule.provideAppLogger()

        assertSame(AndroidAppLogger, logger)
    }
}
