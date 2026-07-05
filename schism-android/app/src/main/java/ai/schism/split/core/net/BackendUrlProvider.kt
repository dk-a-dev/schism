package ai.schism.split.core.net

import ai.schism.split.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the backend base URL from build/env config ([BuildConfig.BACKEND_URL], overridable via the
 * SCHISM_BACKEND_URL env var or `schism.backendUrl` Gradle property) — it is deliberately NOT a user
 * setting. Retrofit is built with a placeholder base URL; [BackendUrlInterceptor] rewrites each
 * request's scheme/host/port to this value so a single long-lived [ApiService] targets the right host.
 */
@Singleton
class BackendUrlProvider @Inject constructor() {
    val baseUrl: String = BuildConfig.BACKEND_URL
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
