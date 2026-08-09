package ai.schism.split.ocr

import ai.schism.split.core.model.ArtifactDownloader
import ai.schism.split.core.model.OcrModelStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `first use asks before downloading and reports the exact payload size`() = runTest {
        val downloads = FakeDownloads()
        val coordinator = coordinator(downloads)

        assertEquals(
            OcrAvailability.ConsentRequired(modelBytes = 6_298_800L, featureBytes = null),
            coordinator.observe().first(),
        )
        assertEquals(0, downloads.starts.size)
    }

    @Test
    fun `wifi only preparation maps waiting progress and completion`() = runTest {
        val downloads = FakeDownloads()
        val coordinator = coordinator(downloads)

        val states = coordinator.prepare(allowCellular = false)
        assertEquals(listOf(false), downloads.starts)
        assertEquals(OcrAvailability.WaitingForWifi, states.first())

        downloads.state.value = OcrDownloadState.Running(2_000_000, 6_298_800, "models")
        assertEquals(
            OcrAvailability.Downloading(2_000_000, 6_298_800, "models"),
            states.first(),
        )
        downloads.state.value = OcrDownloadState.Complete
        assertEquals(OcrAvailability.Ready, states.first())
    }

    @Test
    fun `cellular opt in and actionable failures are retained`() = runTest {
        val downloads = FakeDownloads()
        val coordinator = coordinator(downloads)

        val states = coordinator.prepare(allowCellular = true)
        assertEquals(listOf(true), downloads.starts)

        downloads.state.value = OcrDownloadState.Failed(OcrFailure.NoSpace, canRetry = false)
        assertEquals(
            OcrAvailability.Failed(OcrFailure.NoSpace, canRetry = false),
            states.first(),
        )
        downloads.state.value = OcrDownloadState.Failed(OcrFailure.Integrity, canRetry = true)
        assertEquals(
            OcrAvailability.Failed(OcrFailure.Integrity, canRetry = true),
            states.first(),
        )
    }

    @Test
    fun `cancel returns to consent without deleting resumable data`() = runTest {
        val downloads = FakeDownloads()
        val coordinator = coordinator(downloads)

        coordinator.cancelDownload()

        assertEquals(1, downloads.cancels)
        assertEquals(OcrAvailability.ConsentRequired(6_298_800, null), coordinator.observe().first())
    }

    private fun coordinator(downloads: FakeDownloads): OcrCoordinator {
        val root = File(temporaryFolder.root, "ocr")
        val store = OcrModelStore(
            root,
            ArtifactDownloader(OkHttpClient(), "https://example.invalid/".toHttpUrl()) { Long.MAX_VALUE },
        )
        return OcrCoordinator(store, downloads)
    }

    private class FakeDownloads : OcrDownloadController {
        val state = MutableStateFlow<OcrDownloadState>(OcrDownloadState.Idle)
        val starts = mutableListOf<Boolean>()
        var cancels = 0

        override fun observe(): Flow<OcrDownloadState> = state

        override fun enqueue(allowCellular: Boolean) {
            starts += allowCellular
            state.value = if (allowCellular) OcrDownloadState.Queued else OcrDownloadState.WaitingForWifi
        }

        override fun cancel() {
            cancels++
            state.value = OcrDownloadState.Idle
        }
    }
}
