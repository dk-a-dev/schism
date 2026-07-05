package ai.schism.split.core.net

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/** Builds a configured [ApiService]. Base URL is provided by the caller (DI in production). */
object ApiClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun create(baseUrl: String, debugLogging: Boolean = false): ApiService {
        val clientBuilder = OkHttpClient.Builder()
        if (debugLogging) {
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY },
            )
        }
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(clientBuilder.build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    /** Retrofit requires the base URL to end with '/'. */
    private fun normalizeBaseUrl(url: String): String = if (url.endsWith("/")) url else "$url/"
}
