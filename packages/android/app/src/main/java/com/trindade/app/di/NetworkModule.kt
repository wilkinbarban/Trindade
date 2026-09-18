package com.trindade.app.di

import com.trindade.app.BuildConfig
import com.trindade.app.auth.AuthInterceptor
import com.trindade.app.network.AuthApi
import com.trindade.app.network.SystemApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // An APPLICATION interceptor, not a network one. A network interceptor runs after
            // redirects and retries have been handled and cannot re-issue a request across them;
            // the whole job here is to observe a 401 and send the request again with a fresh token,
            // which is only possible above that layer.
            .addInterceptor(authInterceptor)
            .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Backends evolve; never fail the whole payload on an unknown key.
        ignoreUnknownKeys = true
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    // The hand-written interfaces are the only ones Retrofit is asked to implement. Nothing generated
    // is exposed here on purpose: the contract supplies the types, and the calls are this client's.
    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideSystemApi(retrofit: Retrofit): SystemApi = retrofit.create(SystemApi::class.java)
}
