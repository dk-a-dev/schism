package ai.schism.split.core.di

import ai.schism.split.BuildConfig
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.AuthEvents
import ai.schism.split.core.net.AuthInterceptor
import ai.schism.split.core.net.AuthTokenProvider
import ai.schism.split.core.net.BackendUrlInterceptor
import ai.schism.split.core.net.BackendUrlProvider
import ai.schism.split.core.net.SessionExpiredInterceptor
import ai.schism.split.core.settings.SettingsRepository
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttp(
        urlProvider: BackendUrlProvider,
        authProvider: AuthTokenProvider,
        settings: SettingsRepository,
        authEvents: AuthEvents,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(BackendUrlInterceptor(urlProvider))
            .addInterceptor(AuthInterceptor(authProvider))
            // Must run AFTER AuthInterceptor so it sees the Authorization header we attached (or its
            // absence), and after BackendUrlInterceptor so path checks below see the real request path.
            .addInterceptor(SessionExpiredInterceptor(settings, authEvents))
            // Bypass ngrok's free-tier browser interstitial on tunneled backends (harmless elsewhere).
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("ngrok-skip-browser-warning", "true").build(),
                )
            }
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY },
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideApiService(client: OkHttpClient): ApiService =
        Retrofit.Builder()
            // Placeholder base URL; BackendUrlInterceptor rewrites host/port per request.
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
}
