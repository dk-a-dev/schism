package ai.schism.split.core.net

import ai.schism.split.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
