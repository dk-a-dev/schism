package ai.schism.split.core.net

import ai.schism.split.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current backend base URL (kept in sync with settings) so a single long-lived
 * [ApiService] can target a user-configurable host. Retrofit is built with a placeholder base URL;
 * [BackendUrlInterceptor] rewrites each request's scheme/host/port to the current value.
 */
@Singleton
class BackendUrlProvider @Inject constructor(
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    @Volatile
    var baseUrl: String = SettingsRepository.DEFAULT_BACKEND_URL
        private set

    init {
        settings.backendUrl
            .onEach { if (it.isNotBlank()) baseUrl = it }
            .launchIn(scope)
    }
}

class BackendUrlInterceptor(private val provider: BackendUrlProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val configured = provider.baseUrl.toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())
        val request = chain.request()
        val newUrl = request.url.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
