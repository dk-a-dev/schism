package ai.schism.split.core.di

import ai.schism.split.BuildConfig
import ai.schism.split.core.net.ApiClient
import ai.schism.split.core.net.ApiService
import ai.schism.split.core.net.BackendUrlInterceptor
import ai.schism.split.core.net.BackendUrlProvider
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
    fun provideOkHttp(urlProvider: BackendUrlProvider): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(BackendUrlInterceptor(urlProvider))
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
