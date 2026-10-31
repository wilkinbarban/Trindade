package com.trindade.app.di

import com.trindade.app.BuildConfig
import com.trindade.app.auth.AuthInterceptor
import com.trindade.app.network.AdminApi
import com.trindade.app.network.AuditApi
import com.trindade.app.network.AuthApi
import com.trindade.app.network.BigDecimalSerializer
import com.trindade.app.network.GuestReadonlyInterceptor
import com.trindade.app.network.LoadingApi
import com.trindade.app.massas.MassasDaySource
import com.trindade.app.massas.MassasRepository
import com.trindade.app.network.MassasApi
import com.trindade.app.network.ReportsApi
import com.trindade.app.network.StockApi
import com.trindade.app.network.SystemApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // 15 seconds, where it used to be 10, and the reason is about where this server is rather than about
    // the request: the operators work a few thousand kilometres from this deployment, often on a mobile
    // radio, so a connect this client starts can spend most of a ten-second budget on DNS and the TCP and
    // TLS round trips alone and give up on a connection that was going to succeed. A login is the one
    // request an operator issued and is watching, which is what makes waiting on it worth more than
    // failing it early.
    //
    // What this is not: evidence that a timeout is what the operator hit. Their working 401 from inside the
    // emulator proves this app can reach the host -- it says nothing about how long a handset takes to get
    // there -- and this bound only decides when the client stops waiting. It is a wait, not a diagnosis:
    // the diagnosis is the failure's own class, which `AuthRepository` now logs and reports.
    //
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L

    // The download's own pair, and deliberately not the backend's. These are two different jobs: an API call
    // is a small answer an operator issued and is waiting on, while this one moves a file of some megabytes
    // over whatever network the plant has. The read timeout has to survive the pauses a mobile radio makes
    // between reads without cutting a transfer that is progressing.
    //
    // No whole-call cap here, and its absence is the same decision. `callTimeout` would bound the file by
    // wall-clock time rather than by progress: eleven megabytes at a rate the handset really achieves in the
    // plant can outlast any cap short enough to be useful as a liveness check, and the result would be a
    // download cancelled a few bytes from the end. What bounds this one is the operator cancelling it and the
    // progress the screen shows them.
    private const val DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 15L
    private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 60L

    /**
     * The client, and the Retrofit built on it, that speak to this project's own API.
     *
     * The one that carries a token: every request it sends passes through [AuthInterceptor].
     */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class BackendClient

    /**
     * The client that fetches a published file by the address the API hands out, and that carries no
     * credentials at all.
     *
     * Named as a destination rather than as an absence because the qualifier is about *where* a request
     * goes; the absence of a token is the property that follows from it, and it is the one the comment on
     * the provider below is about.
     */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class PublicFileClient

    /**
     * El cuerpo que viaja a nuestro backend, que omite los campos que nadie llenó.
     *
     * Nuestros esquemas son estrictos y distinguen "no lo mando" de "lo mando vacío"; el ERP de la empresa
     * es al revés y lee el null explícito como "todos", así que el [Json] compartido conserva los nulls y
     * sólo el cliente del backend usa esta variante.
     */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class BackendJson

    @Provides
    @Singleton
    @BackendClient
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // An APPLICATION interceptor, not a network one. A network interceptor runs after
            // redirects and retries have been handled and cannot re-issue a request across them;
            // the whole job here is to observe a 401 and send the request again with a fresh token,
            // which is only possible above that layer.
            .addInterceptor(authInterceptor)
            // Después de auth y no antes: aquel puede renovar el token y reintentar una vez, y
            // este tiene que ver la respuesta FINAL -- la del reintento incluido.
            .addInterceptor(GuestReadonlyInterceptor)
            // The plant's log and this backend's log are read by a person looking at several clients at
            // once; the header is how a line is attributed to the APK that sent it (S20).
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Trindade-Android/${BuildConfig.APP_VERSION_NAME}")
                        .build(),
                )
            }
            .build()

    /**
     * The client that moves a published file, with no interceptor on it -- and the absence is the point.
     *
     * [AuthInterceptor] attaches this app's JWT to every request it sees. A download must never carry it:
     * the address comes from a payload, the file is a static object rather than an authorized read, and a
     * header sent to a host somebody else named cannot be taken back. So the download gets a client that has
     * never been given the interceptor, rather than a path exemption inside the interceptor, and the
     * difference is what can go wrong later:
     *
     * - **A token that is not attached cannot leak.** The separation is a property of the object graph --
     *   this client is built with one builder, and that builder adds no interceptor -- so auditing it means
     *   reading this provider. Nothing at request time can put a header on a request that goes out through
     *   it.
     * - **An exemption list is a rule somebody has to keep.** [AuthInterceptor] already carries one for
     *   the calls that have no session to speak of, and it works by matching the tail of the request path.
     *   Adding another entry for a path that serves bytes would make the safety of that request depend on
     *   every future edit to the list, and a path rule says nothing about the host it ends up reaching.
     *
     * The two clients differ in timeouts too, both named here rather than left to be read out of the
     * builders: the API's pair above is sized for a small answer somebody is waiting on, and this one for a
     * file transfer that pauses without failing. See the constants at the top of this module, and note what
     * this client deliberately does *not* carry: a whole-call cap, which would bound eleven megabytes by
     * wall-clock time and cancel a transfer that was still making progress.
     */
    @Provides
    @Singleton
    @PublicFileClient
    fun providePublicFileOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Backends evolve; never fail the whole payload on an unknown key.
        ignoreUnknownKeys = true
        // The contract carries decimal money and temperature values as JSON numbers, and BigDecimal is
        // not one of the primitive types kotlinx.serialization handles on its own: without this mapping
        // the whole payload fails to decode instead of the one field.
        serializersModule = SerializersModule {
            contextual(BigDecimal::class, BigDecimalSerializer)
        }
    }

    @Provides
    @Singleton
    @BackendJson
    fun provideBackendJson(json: Json): Json = Json(from = json) { explicitNulls = false }

    @Provides
    @Singleton
    @BackendClient
    fun provideRetrofit(@BackendClient client: OkHttpClient, @BackendJson json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    // The hand-written interfaces are the only ones Retrofit is asked to implement. Nothing generated
    // is exposed here on purpose: the contract supplies the types, and the calls are this client's.
    @Provides
    @Singleton
    fun provideAuthApi(@BackendClient retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideSystemApi(@BackendClient retrofit: Retrofit): SystemApi = retrofit.create(SystemApi::class.java)

    @Provides
    @Singleton
    fun provideReportsApi(@BackendClient retrofit: Retrofit): ReportsApi = retrofit.create(ReportsApi::class.java)

    @Provides
    @Singleton
    fun provideAdminApi(@BackendClient retrofit: Retrofit): AdminApi = retrofit.create(AdminApi::class.java)

    @Provides
    @Singleton
    fun provideAuditApi(@BackendClient retrofit: Retrofit): AuditApi = retrofit.create(AuditApi::class.java)

    @Provides
    @Singleton
    fun provideStockApi(@BackendClient retrofit: Retrofit): StockApi = retrofit.create(StockApi::class.java)

    @Provides
    @Singleton
    fun provideLoadingApi(@BackendClient retrofit: Retrofit): LoadingApi = retrofit.create(LoadingApi::class.java)

    @Provides
    @Singleton
    fun provideMassasApi(@BackendClient retrofit: Retrofit): MassasApi = retrofit.create(MassasApi::class.java)

    // The reports' "Massa do dia" card reads the day and nothing else, so the interface it is given is the one
    // method it needs rather than the whole repository the launch screen writes through.
    @Provides
    @Singleton
    fun provideMassasDaySource(repository: MassasRepository): MassasDaySource = repository
}
