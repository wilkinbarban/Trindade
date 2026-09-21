package com.trindade.app.di

import com.trindade.app.logging.AndroidAppLogger
import com.trindade.app.logging.AppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {
    @Provides
    @Singleton
    fun provideAppLogger(): AppLogger = AndroidAppLogger
}
