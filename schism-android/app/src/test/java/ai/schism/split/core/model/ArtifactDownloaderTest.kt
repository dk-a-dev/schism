package ai.schism.split.core.model

import ai.schism.split.core.net.OcrArtifactDto
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactDownloaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams and verifies a fresh artifact`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody("paddle"))
        val part = File(temporaryFolder.root, "det.onnx.part")
        val progress = mutableListOf<Long>()

        ArtifactDownloader(OkHttpClient(), server.url("/")) { Long.MAX_VALUE }.download(
            artifact("det.onnx", 6, "a2afeec49a4a4c3d4cbc8c4bb60fdf30c54f575c3b3dfa565f59c57028da8568"),
            part,
        ) { downloaded, _ -> progress += downloaded }

        assertEquals("paddle", part.readText())
        assertEquals(6L, progress.last())
        assertEquals("\"v1\"", File(part.parentFile, "${part.name}.etag").readText())
    }

    @Test
    fun `resumes a matching partial response with Range and If-Range`() = runTest {
        val part = File(temporaryFolder.root, "det.onnx.part").apply { writeText("abc") }
        File(part.parentFile, "${part.name}.etag").writeText("\"v1\"")
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes 3-5/6")
                .setBody("def"),
        )

        ArtifactDownloader(OkHttpClient(), server.url("/")) { Long.MAX_VALUE }.download(
            artifact("det.onnx", 6, "bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721"),
            part,
        ) { _, _ -> }

        assertEquals("abcdef", part.readText())
        val request = server.takeRequest()
        assertEquals("bytes=3-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
    }

    @Test
    fun `restarts when server ignores an existing range`() = runTest {
        val part = File(temporaryFolder.root, "det.onnx.part").apply { writeText("abc") }
        server.enqueue(MockResponse().setResponseCode(200).setBody("paddle"))

        ArtifactDownloader(OkHttpClient(), server.url("/")) { Long.MAX_VALUE }.download(
            artifact("det.onnx", 6, "a2afeec49a4a4c3d4cbc8c4bb60fdf30c54f575c3b3dfa565f59c57028da8568"),
            part,
        ) { _, _ -> }

        assertEquals("paddle", part.readText())
    }

    @Test
    fun `rejects low space and integrity mismatch without deleting resumable bytes`() = runTest {
        val part = File(temporaryFolder.root, "det.onnx.part")
        val downloader = ArtifactDownloader(OkHttpClient(), server.url("/")) { 2L }
        assertThrows(ArtifactDownloadException.InsufficientStorage::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(
                    artifact("det.onnx", 6, "a2afeec49a4a4c3d4cbc8c4bb60fdf30c54f575c3b3dfa565f59c57028da8568"),
                    part,
                ) { _, _ -> }
            }
        }

        server.enqueue(MockResponse().setResponseCode(200).setBody("paddle"))
        val integrityDownloader = ArtifactDownloader(OkHttpClient(), server.url("/")) { Long.MAX_VALUE }
        assertThrows(ArtifactDownloadException.Integrity::class.java) {
            kotlinx.coroutines.runBlocking {
                integrityDownloader.download(artifact("det.onnx", 6, "0000000000000000000000000000000000000000000000000000000000000000"), part) { _, _ -> }
            }
        }
        assertEquals("paddle", part.readText())
    }

    private fun artifact(name: String, bytes: Long, sha256: String) = OcrArtifactDto(
        name = name,
        bytes = bytes,
        sha256 = sha256,
        downloadPath = "/$name",
    )
}
