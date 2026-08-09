package ai.schism.split.core.model

import ai.schism.split.core.net.OcrManifestDto
import ai.schism.split.ocr.api.OcrModelFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OcrModelStore(
    private val root: File,
    private val downloader: ArtifactDownloader,
) {
    private val installMutex = Mutex()

    fun current(): OcrModelFiles? {
        val version = File(root, CURRENT_MARKER).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (!VERSION.matches(version)) return null
        return filesFor(File(root, "versions/$version")).takeIf { it.allPresent() }
    }

    suspend fun install(manifest: OcrManifestDto, progress: (Long, Long) -> Unit): OcrModelFiles =
        installMutex.withLock {
            validate(manifest)
            root.mkdirs()
            val staging = File(root, "staging/${manifest.version}").apply { mkdirs() }
            var completedBytes = 0L
            for (artifact in manifest.artifacts) {
                downloader.download(artifact, File(staging, artifact.name)) { downloaded, _ ->
                    progress(completedBytes + downloaded, manifest.totalBytes)
                }
                completedBytes += artifact.bytes
            }
            val stagedFiles = filesFor(staging)
            check(stagedFiles.allPresent()) { "OCR model set is incomplete" }

            val versions = File(root, "versions").apply { mkdirs() }
            val destination = File(versions, manifest.version)
            if (!destination.exists()) {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            writeCurrent(manifest.version)
            cleanupOldVersions(manifest.version)
            filesFor(destination)
        }

    private fun writeCurrent(version: String) {
        val marker = File(root, CURRENT_MARKER)
        val part = File(root, "$CURRENT_MARKER.part")
        part.writeText(version)
        Files.move(
            part.toPath(),
            marker.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun cleanupOldVersions(currentVersion: String) {
        val versions = File(root, "versions").listFiles { file -> file.isDirectory }.orEmpty()
        versions.filter { it.name != currentVersion }
            .sortedByDescending { it.lastModified() }
            .drop(1)
            .forEach(File::deleteRecursively)
    }

    private fun validate(manifest: OcrManifestDto) {
        require(VERSION.matches(manifest.version)) { "Invalid OCR model version" }
        require(manifest.totalBytes > 0L) { "OCR model size must be positive" }
        require(manifest.artifacts.map { it.name }.toSet() == REQUIRED_NAMES && manifest.artifacts.size == 3) {
            "OCR manifest must contain exactly the required artifacts"
        }
        require(manifest.artifacts.sumOf { it.bytes } == manifest.totalBytes) { "OCR manifest total is invalid" }
    }

    private fun filesFor(directory: File) = OcrModelFiles(
        detection = File(directory, "det.onnx"),
        recognition = File(directory, "rec.onnx"),
        recognitionConfig = File(directory, "rec.yml"),
    )

    private fun OcrModelFiles.allPresent() =
        detection.isFile && detection.length() > 0L &&
            recognition.isFile && recognition.length() > 0L &&
            recognitionConfig.isFile && recognitionConfig.length() > 0L

    private companion object {
        const val CURRENT_MARKER = "current"
        val VERSION = Regex("^[A-Za-z0-9._-]{1,64}$")
        val REQUIRED_NAMES = setOf("det.onnx", "rec.onnx", "rec.yml")
    }
}
