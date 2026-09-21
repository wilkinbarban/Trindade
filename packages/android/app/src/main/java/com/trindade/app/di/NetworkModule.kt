package com.trindade.app.di

import com.trindade.app.BuildConfig
import com.trindade.app.auth.AuthInterceptor
import com.trindade.app.network.AuthApi
import com.trindade.app.network.LoadingApi
import com.trindade.app.network.ReportsApi
import com.trindade.app.network.SystemApi
import com.trindade.app.update.GitHubReleaseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
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
    // Only this pair moved, and the trio below did not. That is not a shared value being edited twice: the
    // GitHub check is a nicety nobody asked for, its bounds are deliberately tighter for the reason its own
    // comments give, and widening them because a login felt slow would be fixing the wrong client -- the
    // update check's silence is what the operator sees in place of an answer, so it is the last place that
    // may be allowed to wait longer.
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L

    // This check's own trio, deliberately not the backend's pair. What they bound is not the same thing:
    // the timeouts above are sized for a request an operator issued and is waiting on, so a slow answer is
    // worth waiting for. This check is a nicety nobody asked for, and its silence is what the operator sees
    // in place of an answer -- so it may not be able to last as long as a request they did make. Sharing one
    // pair between the two clients would keep the manual invariant and dress it as a shared value, which is
    // how the two would silently acquire each other's meaning again.
    private const val GITHUB_CONNECT_TIMEOUT_SECONDS = 5L
    private const val GITHUB_READ_TIMEOUT_SECONDS = 10L

    // The third of the trio, and the one that makes this check's silence a bound rather than a hope.
    // `readTimeout` bounds one idle socket read and not the call: a server that answers and then trickles a
    // byte inside every window keeps the call open for as long as it likes, with the screen showing its
    // `Checking` state and the socket and its coroutine still held, while neither of the timeouts above ever
    // fires. So this is the timeout the whole check is really measured against -- the read timeout is the
    // bound on one read, and nothing else here bounds the request.
    //
    // Above the *sum* of the two, not merely above `readTimeout`, and by as little as the shape allows:
    // `connectTimeout + readTimeout` is the latest instant at which the read timeout can still fire, so a
    // whole-call bound sitting on that sum would fire together with the read timeout on a call that spent its
    // whole connect budget and then went quiet, and which of the two ended the call would be a race. A socket
    // that really does go quiet has to be ended by the tighter, more specific bound. Being above the sum by
    // much would put this client back in the shape the comment above rejects -- a wait the operator never
    // agreed to, wearing a third name instead of a shared pair. `GitHubReleaseApiTest` asserts both halves of
    // this: the value, and the behaviour, by trickling a response that no single read timeout can end.
    private const val GITHUB_CALL_TIMEOUT_SECONDS = 16L

    /**
     * The client, and the Retrofit built on it, that speak to this project's own API.
     *
     * The one that carries a token: every request it sends passes through [AuthInterceptor].
     */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class BackendClient

    /**
     * The client, and the Retrofit built on it, that speak to GitHub -- and that carry no credentials at
     * all.
     *
     * Named as a destination rather than as an absence because the qualifier is about *where* a request
     * goes; the absence of a token is the property that follows from it, and it is the one the comment on
     * the provider below is about.
     */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class GitHubClient

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
            .build()

    /**
     * A second client with no interceptor on it, and the absence is the point of the whole check.
     *
     * [AuthInterceptor] attaches this app's JWT to every request it sees. A request to GitHub must never
     * carry it: it is a credential for this project's backend, it is nobody else's business, and a header
     * sent to a third party cannot be taken back. So GitHub gets a client that has never been given the
     * interceptor, rather than a path exemption inside the interceptor, and the difference is what can go
     * wrong later:
     *
     * - **A token that is not attached cannot leak.** The separation is a property of the object graph --
     *   this client is built with one builder, and that builder adds no interceptor -- so auditing it means
     *   reading this provider. Nothing at request time can put a header on a request that goes out through
     *   it.
     * - **An exemption list is a rule somebody has to keep.** [AuthInterceptor] already carries one for
     *   the calls that have no session to speak of, and it works by matching the tail of the request path.
     *   Adding another entry there for a host that is not even ours would make the safety of a
     *   third-party request depend on every future edit to that list, and a path rule says nothing about
     *   the host: an exemption written for `repos/.../releases/latest` would also exempt any of this
     *   project's own API paths that happened to end the same way.
     *
     * The two clients therefore differ in two respects, both named here rather than left to be read out of
     * the builders: the interceptor above is omitted, and the timeouts are this client's own constants rather
     * than the backend's. The timeout half is the one that used to be invisible, because repeating the same
     * two names made the pair look like a shared value: it was not, and a change to one client silently
     * changed the other's meaning. See the constants at the top of this module for why the values differ,
     * and note that this client carries a third one the backend's does not -- a bound on the whole call,
     * which is not the same thing as the bound on one read, and which is the one this check's silence is
     * measured against.
     *
     * The separate Retrofit below is what keeps the two from being confused for each other -- and it is not
     * possible to confuse them, because both providers are qualified: an unqualified `OkHttpClient` or
     * `Retrofit` is no longer a request Dagger can satisfy at all, so a new call site has to name the
     * destination it means.
     */
    @Provides
    @Singleton
    @GitHubClient
    fun provideGitHubOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(GITHUB_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(GITHUB_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Not a fourth bound on any one step: this is the cap on the whole call, the one that still
            // holds when the bytes keep arriving and no single read is ever idle. See the constant above.
            .callTimeout(GITHUB_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Backends evolve; never fail the whole payload on an unknown key.
        ignoreUnknownKeys = true
    }

    @Provides
    @Singleton
    @BackendClient
    fun provideRetrofit(@BackendClient client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    /**
     * GitHub's Retrofit, on the tokenless client and on GitHub's own base URL.
     *
     * Its own instance rather than a second API interface on the app's Retrofit, because the base URL and
     * the client are both properties of the destination and an interface can override neither. A
     * `GitHubReleaseApi` created from the backend Retrofit would send this project's own API a request for
     * GitHub's path; a GitHub Retrofit built on the token-carrying client would send this app's JWT to
     * `api.github.com`. The first is a broken call and the second is the leak this whole arrangement is
     * here to prevent, and neither is reachable by accident: both providers are qualified, so a request for
     * an unqualified `Retrofit` or `OkHttpClient` does not resolve.
     *
     * The `Json` is shared, and that is not a relaxation of the separation: it is a parser configuration,
     * it holds no credential, and the two payloads are both JSON this app decodes the same way.
     */
    @Provides
    @Singleton
    @GitHubClient
    fun provideGitHubRetrofit(@GitHubClient client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.GITHUB_API_BASE_URL)
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
    fun provideLoadingApi(@BackendClient retrofit: Retrofit): LoadingApi = retrofit.create(LoadingApi::class.java)

    @Provides
    @Singleton
    fun provideGitHubReleaseApi(@GitHubClient retrofit: Retrofit): GitHubReleaseApi =
        retrofit.create(GitHubReleaseApi::class.java)
}
