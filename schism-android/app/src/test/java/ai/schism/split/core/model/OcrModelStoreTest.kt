package ai.schism.split.core.model

import ai.schism.split.core.net.OcrArtifactDto
import ai.schism.split.core.net.OcrManifestDto
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrModelStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `promotes only a completely verified model version`() = runTest {
        server.enqueue(MockResponse().setBody("paddle"))
        server.enqueue(MockResponse().setBody("abcdef"))
        server.enqueue(MockResponse().setBody("def"))
        val store = store()

        val installed = store.install(manifest("v1")) { _, _ -> }

        assertEquals("paddle", installed.detection.readText())
        assertEquals("abcdef", installed.recognition.readText())
        assertEquals("def", installed.recognitionConfig.readText())
        assertEquals(installed, store.current())
    }

    @Test
    fun `failed replacement retains the previous current version`() = runTest {
        repeat(3) { index -> server.enqueue(MockResponse().setBody(listOf("paddle", "abcdef", "def")[index])) }
        val store = store()
        val first = store.install(manifest("v1")) { _, _ -> }
        server.enqueue(MockResponse().setBody("paddle"))
        server.enqueue(MockResponse().setBody("wrong!"))

        assertThrows(ArtifactDownloadException.Integrity::class.java) {
            kotlinx.coroutines.runBlocking { store.install(manifest("v2")) { _, _ -> } }
        }

        assertEquals(first, store.current())
    }

    @Test
    fun `empty store is null and unexpected artifact names are rejected`() = runTest {
        val store = store()
        assertNull(store.current())
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.install(
                    manifest("v1").copy(
                        artifacts = listOf(OcrArtifactDto("../det.onnx", 1, "00", "/bad")),
                    ),
                ) { _, _ -> }
            }
        }
    }

    private fun store(): OcrModelStore = OcrModelStore(
        root = File(temporaryFolder.root, "models"),
        downloader = ArtifactDownloader(OkHttpClient(), server.url("/")) { Long.MAX_VALUE },
    )

    private fun manifest(version: String) = OcrManifestDto(
        version = version,
        minimumAppVersionCode = 10300,
        totalBytes = 15,
        artifacts = listOf(
            OcrArtifactDto("det.onnx", 6, "a2afeec49a4a4c3d4cbc8c4bb60fdf30c54f575c3b3dfa565f59c57028da8568", "/det.onnx"),
            OcrArtifactDto("rec.onnx", 6, "bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721", "/rec.onnx"),
            OcrArtifactDto("rec.yml", 3, "cb8379ac2098aa165029e3938a51da0bcecfc008fd6795f401178647f96c5b34", "/rec.yml"),
        ),
    )
}
