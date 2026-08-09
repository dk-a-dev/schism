package ai.schism.split.ocr

import ai.schism.split.core.model.OcrModelStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface OcrDownloadController {
    fun observe(): Flow<OcrDownloadState>
    fun enqueue(allowCellular: Boolean)
    fun cancel()
}

@Singleton
class OcrCoordinator @Inject constructor(
    private val modelStore: OcrModelStore,
    private val downloads: OcrDownloadController,
) {
    fun observe(): Flow<OcrAvailability> = if (modelStore.current() != null) {
        flowOf(OcrAvailability.Ready)
    } else {
        downloads.observe().map(::toAvailability)
    }

    fun prepare(allowCellular: Boolean): Flow<OcrAvailability> {
        if (modelStore.current() != null) return flowOf(OcrAvailability.Ready)
        downloads.enqueue(allowCellular)
        return downloads.observe().map(::toAvailability)
    }

    fun cancelDownload() = downloads.cancel()

    fun currentModels() = modelStore.current()

    private fun toAvailability(state: OcrDownloadState): OcrAvailability = when (state) {
        OcrDownloadState.Idle -> OcrAvailability.ConsentRequired(MODEL_BYTES, featureBytes = null)
        OcrDownloadState.Queued -> OcrAvailability.Queued
        OcrDownloadState.WaitingForWifi -> OcrAvailability.WaitingForWifi
        is OcrDownloadState.Running -> OcrAvailability.Downloading(
            state.downloadedBytes,
            state.totalBytes,
            state.stage,
        )
        OcrDownloadState.Complete -> OcrAvailability.Ready
        is OcrDownloadState.Failed -> OcrAvailability.Failed(state.reason, state.canRetry)
    }

    companion object {
        const val MODEL_BYTES = 6_298_800L
    }
}
