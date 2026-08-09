package ai.schism.split.core.model

import ai.schism.split.core.net.OcrArtifactDto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class ArtifactDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class InsufficientStorage(val required: Long, val available: Long) :
        ArtifactDownloadException("OCR download needs $required bytes but only $available are available")

    class Integrity(message: String) : ArtifactDownloadException(message)
    class Network(message: String, cause: Throwable? = null) : ArtifactDownloadException(message, cause)
}

class ArtifactDownloader(
    private val client: OkHttpClient,
    private val backendBaseUrl: HttpUrl,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    suspend fun download(
        spec: OcrArtifactDto,
        partFile: File,
        progress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        validate(spec)
        partFile.parentFile?.mkdirs()
        val etagFile = File(partFile.parentFile, "${partFile.name}.etag")
        if (partFile.length() > spec.bytes) {
            partFile.delete()
            etagFile.delete()
        }
        val remaining = spec.bytes - partFile.length()
        val available = availableBytes(partFile.parentFile ?: partFile)
        if (available < remaining) throw ArtifactDownloadException.InsufficientStorage(remaining, available)

        transfer(spec, partFile, etagFile, allowResume = true, progress = progress)
        if (partFile.length() != spec.bytes) {
            throw ArtifactDownloadException.Integrity(
                "${spec.name} size mismatch: expected ${spec.bytes}, got ${partFile.length()}",
            )
        }
        val actualHash = partFile.sha256()
        if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
            throw ArtifactDownloadException.Integrity("${spec.name} SHA-256 mismatch")
        }
        partFile
    }

    private suspend fun transfer(
        spec: OcrArtifactDto,
        partFile: File,
        etagFile: File,
        allowResume: Boolean,
        progress: (Long, Long) -> Unit,
    ) {
        val existing = if (allowResume) partFile.length() else 0L
        val savedEtag = etagFile.takeIf { existing > 0L && it.isFile }?.readText()?.trim()?.ifEmpty { null }
        val url = backendBaseUrl.resolve(spec.downloadPath)
            ?: throw ArtifactDownloadException.Network("Invalid OCR download path")
        val request = Request.Builder().url(url).apply {
            if (existing > 0L) {
                header("Range", "bytes=$existing-")
                savedEtag?.let { header("If-Range", it) }
            }
        }.build()

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ArtifactDownloadException.Network("Unable to download ${spec.name}", error)
        }
        response.use { httpResponse ->
            val append = existing > 0L && httpResponse.code == 206
            if (httpResponse.code !in listOf(200, 206)) {
                throw ArtifactDownloadException.Network("${spec.name} returned HTTP ${httpResponse.code}")
            }
            if (append) {
                val expectedPrefix = "bytes $existing-"
                val contentRange = httpResponse.header("Content-Range").orEmpty()
                if (!contentRange.startsWith(expectedPrefix)) {
                    partFile.delete()
                    etagFile.delete()
                    return transfer(spec, partFile, etagFile, allowResume = false, progress = progress)
                }
                val responseEtag = httpResponse.header("ETag")
                if (savedEtag != null && responseEtag != null && savedEtag != responseEtag) {
                    partFile.delete()
                    etagFile.delete()
                    return transfer(spec, partFile, etagFile, allowResume = false, progress = progress)
                }
            }

            val body = httpResponse.body ?: throw ArtifactDownloadException.Network("${spec.name} response was empty")
            val responseEtag = httpResponse.header("ETag")
            if (!append) partFile.delete()
            responseEtag?.let { etagFile.writeText(it) }
            var downloaded = if (append) existing else 0L
            FileOutputStream(partFile, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > spec.bytes) {
                            throw ArtifactDownloadException.Integrity("${spec.name} exceeded declared size")
                        }
                        output.write(buffer, 0, count)
                        progress(downloaded, spec.bytes)
                    }
                    output.fd.sync()
                }
            }
        }
    }

    private fun validate(spec: OcrArtifactDto) {
        require(spec.name in ALLOWED_NAMES) { "Unexpected OCR artifact name" }
        require(spec.bytes > 0L) { "OCR artifact size must be positive" }
        require(SHA256.matches(spec.sha256)) { "OCR artifact SHA-256 is invalid" }
        require(spec.downloadPath.startsWith("/v1/models/ocr/") || spec.downloadPath == "/${spec.name}") {
            "OCR artifact path is invalid"
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        val ALLOWED_NAMES = setOf("det.onnx", "rec.onnx", "rec.yml")
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
