package com.trindade.app.network

import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.SetupRequest
import com.trindade.app.contract.models.SetupResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateProfileRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST

/**
 * The authentication surface, written by hand rather than generated.
 *
 * The types come from the generated contract, but the calls do not: a generated client would expose
 * all thirty-seven operations, including the admin and audit surfaces this app has no business
 * reaching, and it would fix a shape for the calls that is the generator's choice rather than this
 * client's. The interface is where the caller's intent lives, so it is written.
 *
 * Every call returns [Response] rather than the body directly. The reason is the auth flow: a 401 on
 * almost any call is the signal to refresh and retry, while a 400 carries a validation message worth
 * showing, and both arrive as a non-2xx status that a bare return type would flatten into one
 * exception. Returning the response lets each caller decide which of those it is looking at.
 *
 * The paths are relative to the configured base URL, which ends in a slash, so they must not begin
 * with one.
 */
interface AuthApi {

    /** Exchange credentials for an access token and a refresh session. */
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    /** Rotate a refresh session into a new token pair. */
    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<RefreshResponse>

    /**
     * End the session. Idempotent by contract: an unknown or already-ended token still succeeds, so a
     * client can clear local state without treating a failure as unfinished business.
     */
    @POST("api/auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<SuccessResponse>

    /** The authenticated user, as the server sees them. */
    @GET("api/auth/me")
    suspend fun me(): Response<ProfileResponse>

    /** The authenticated user's profile. */
    @GET("api/auth/profile")
    suspend fun profile(): Response<ProfileResponse>

    @PATCH("api/auth/profile")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): Response<ProfileResponse>

    /**
     * Change the password. The server ends every session as part of this, including the one making
     * the call, so a success here means the client must sign in again rather than carry on.
     */
    @POST("api/auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<SuccessResponse>

    /** Create the first administrator. Answers 201. */
    @POST("api/auth/setup")
    suspend fun setup(@Body body: SetupRequest): Response<SetupResponse>

    /** Whether setup is still required, which is only true while no user exists at all. */
    @GET("api/auth/setup/status")
    suspend fun setupStatus(): Response<SetupStatusResponse>
}
