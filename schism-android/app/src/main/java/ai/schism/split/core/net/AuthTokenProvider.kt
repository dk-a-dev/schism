package ai.schism.split.core.net

import ai.schism.split.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current bearer auth token (kept in sync with settings) so a synchronous OkHttp
 * interceptor can attach it. Empty until the device has registered during onboarding.
 */
@Singleton
class AuthTokenProvider @Inject constructor(
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    @Volatile
    var token: String = ""
        private set

    init {
        settings.authToken.onEach { token = it }.launchIn(scope)
    }
}

/** Attaches `Authorization: Bearer <token>` when a token is present. */
class AuthInterceptor(private val provider: AuthTokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = provider.token
        val request = if (token.isNotBlank()) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

/**
 * App-wide auth signals, decoupled from any particular screen or ViewModel. Today this is just "our
 * session is no longer valid" (raised by [SessionExpiredInterceptor]); the top-level UI ([MainActivity])
 * collects it and routes back to sign-in. `replay = 0` is deliberate: a late subscriber (e.g. after the
 * UI has already reacted and moved on) must NOT immediately re-fire and loop back to the auth screen.
 */
@Singleton
class AuthEvents @Inject constructor() {
    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    /** Raised when a request that carried our bearer token comes back 401 — our session has ended. */
    fun notifySessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }
}

/**
 * Watches responses for requests WE authenticated (i.e. we attached `Authorization`). A 401 there
 * means the server no longer recognizes our session — the token was revoked, expired, or the account
 * was deleted — NOT a login attempt with bad credentials, so:
 *  - requests with no Authorization header (anonymous calls during onboarding, invite links, etc.)
 *    are ignored — a 401 there is unrelated to any session.
 *  - the login/register endpoints are excluded even though they're unauthenticated-by-design: they
 *    never carry our own Authorization header, but excluding them explicitly documents that a 401
 *    there means "wrong credentials," which the auth screen already surfaces on its own — it must
 *    NOT also trigger a global sign-out.
 * On a genuine session expiry: clears the stored token (so we stop sending a dead one) and emits
 * [AuthEvents.sessionExpired] so the UI can clear its back stack and route to sign-in.
 */
class SessionExpiredInterceptor(
    private val settings: SettingsRepository,
    private val authEvents: AuthEvents,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val hadAuthHeader = !request.header("Authorization").isNullOrBlank()
        val path = request.url.encodedPath
        val isAuthEndpoint = path == "/v1/auth/login" || path == "/v1/auth/register"

        if (response.code == 401 && hadAuthHeader && !isAuthEndpoint) {
            // Interceptors run off the main thread; blocking here for a local DataStore write is safe
            // and ensures the token is gone before this response reaches the caller.
            runBlocking { settings.clearAuthToken() }
            authEvents.notifySessionExpired()
        }
        return response
    }
}
