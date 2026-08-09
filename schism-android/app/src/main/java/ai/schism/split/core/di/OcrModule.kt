package ai.schism.split.core.di

import ai.schism.split.core.model.ArtifactDownloader
import ai.schism.split.core.model.OcrModelStore
import ai.schism.split.core.net.BackendUrlProvider
import ai.schism.split.ocr.OcrDownloadController
import ai.schism.split.ocr.WorkManagerOcrDownloadController
import ai.schism.split.ocr.OcrCoordinator
import ai.schism.split.sms.receipt.ReceiptScanner
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object OcrModule {
    @Provides
    @Singleton
    fun provideArtifactDownloader(
        client: OkHttpClient,
        backendUrlProvider: BackendUrlProvider,
    ): ArtifactDownloader = ArtifactDownloader(client, backendUrlProvider.baseUrl.ensureTrailingSlash().toHttpUrl())

    @Provides
    @Singleton
    fun provideOcrModelStore(
        @ApplicationContext context: Context,
        downloader: ArtifactDownloader,
    ): OcrModelStore = OcrModelStore(context.filesDir.resolve("ocr"), downloader)

    @Provides
    @Singleton
    fun provideOcrDownloadController(
        controller: WorkManagerOcrDownloadController,
    ): OcrDownloadController = controller

    @Provides
    @Singleton
    fun provideReceiptScanner(coordinator: OcrCoordinator): ReceiptScanner = ReceiptScanner(coordinator)

    private fun String.ensureTrailingSlash() = if (endsWith('/')) this else "$this/"
}
