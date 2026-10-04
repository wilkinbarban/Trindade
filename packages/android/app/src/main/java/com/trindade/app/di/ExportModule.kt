package com.trindade.app.di

import com.trindade.app.export.AndroidPdfGenerator
import com.trindade.app.export.AndroidShareManager
import com.trindade.app.export.PdfGenerator
import com.trindade.app.export.ShareManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {

    /**
     * Binds [AndroidPdfGenerator] to the [PdfGenerator] interface.
     *
     * Bound as an interface so callers (such as ViewModels or export orchestrators) can be
     * tested against a test double on the JVM without invoking native PDF rendering.
     */
    @Binds
    @Singleton
    abstract fun bindPdfGenerator(generator: AndroidPdfGenerator): PdfGenerator

    /**
     * Binds [AndroidShareManager] to the [ShareManager] platform seam.
     *
     * Bound as an interface so callers can be tested against JVM test doubles without
     * attempting to launch Android system chooser activities.
     */
    @Binds
    @Singleton
    abstract fun bindShareManager(manager: AndroidShareManager): ShareManager
}
