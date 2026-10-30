package com.trindade.app.di

import com.trindade.app.aero.AeroDispatcher
import com.trindade.app.aero.AeroErpAdapter
import com.trindade.app.aero.AeroErpPort
import com.trindade.app.aero.AeroMirrorAdapter
import com.trindade.app.aero.AeroMirrorPort
import com.trindade.app.aero.AeroSettingsPort
import com.trindade.app.aero.AeroSettingsStore
import com.trindade.app.aero.TabletAckPort
import com.trindade.app.aero.TabletAckStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AeroModule {

    /**
     * The two ports, bound by hand instead of `@Binds`.
     *
     * The adapters are the only two classes that know both a client and a port, and building the dispatcher
     * here keeps the sequence testable: a test constructs [AeroDispatcher] with its own fakes and never needs
     * Hilt, OkHttp or a device.
     */
    @Provides
    @Singleton
    fun provideErpPort(adapter: AeroErpAdapter): AeroErpPort = adapter

    @Provides
    @Singleton
    fun provideMirrorPort(adapter: AeroMirrorAdapter): AeroMirrorPort = adapter

    @Provides
    @Singleton
    fun provideSettingsPort(store: AeroSettingsStore): AeroSettingsPort = store

    @Provides
    @Singleton
    fun provideTabletAckPort(store: TabletAckStore): TabletAckPort = store

    @Provides
    @Singleton
    fun provideDispatcher(erp: AeroErpPort, mirror: AeroMirrorPort): AeroDispatcher =
        AeroDispatcher(erp = erp, mirror = mirror)
}
