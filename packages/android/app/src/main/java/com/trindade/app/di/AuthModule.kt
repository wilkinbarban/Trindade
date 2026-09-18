package com.trindade.app.di

import com.trindade.app.auth.AuthInterceptor
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.KeystoreTokenStore
import com.trindade.app.auth.TokenStore
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    /** Bound as the interface, so the JVM tests can substitute their own store. */
    @Provides
    @Singleton
    fun provideTokenStore(store: KeystoreTokenStore): TokenStore = store

    /**
     * The interceptor takes a refresh callback rather than the repository, and that is what breaks
     * the cycle this would otherwise be: the OkHttp client needs the interceptor, AuthApi needs the
     * client, the repository needs AuthApi, and the interceptor would need the repository.
     *
     * `Lazy` defers the lookup to the first 401 instead of to construction, by which time every part
     * exists. The alternative -- a second OkHttp client for refresh, without this interceptor -- also
     * works and costs a duplicate client, so it is worth knowing it was considered and not overlooked.
     */
    @Provides
    @Singleton
    fun provideAuthInterceptor(
        tokenStore: TokenStore,
        repository: Lazy<AuthRepository>,
    ): AuthInterceptor =
        AuthInterceptor(
            tokenStore = tokenStore,
            refreshSession = { repository.get().refreshBlocking() },
        )
}
