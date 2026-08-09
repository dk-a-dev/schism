package ai.schism.split.core.billing

import ai.schism.split.core.net.ApiClient
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

/** Wires the monetization boundary onto the app's existing authenticated OkHttp client. */
@Module
@InstallIn(SingletonComponent::class)
object BillingModule {

    @Provides
    @Singleton
    fun provideBillingApi(client: OkHttpClient): BillingApi =
        Retrofit.Builder()
            // Placeholder base URL; BackendUrlInterceptor rewrites host/port per request.
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BillingApi::class.java)

    @Provides
    @Singleton
    fun providePlusBilling(impl: PlayBilling): PlusBilling = impl
}
