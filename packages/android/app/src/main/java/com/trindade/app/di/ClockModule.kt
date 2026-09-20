package com.trindade.app.di

import com.trindade.app.update.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    /**
     * The system clock, bound as the interface so the update check's cache can be exercised with a fake
     * one. [System.currentTimeMillis] rather than an Android API, because nothing here needs a time zone
     * or a wall-clock reading: the cache compares two readings and only their difference matters.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock { System.currentTimeMillis() }
}
