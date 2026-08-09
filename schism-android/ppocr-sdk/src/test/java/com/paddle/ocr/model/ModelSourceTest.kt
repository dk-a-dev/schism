package com.paddle.ocr.model

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelSourceTest {
    @Test
    fun `asset retains a safe relative path`() {
        assertEquals("models/det/inference.onnx", ModelSource.Asset("models/det/inference.onnx").path)
    }

    @Test
    fun `asset rejects traversal and absolute paths`() {
        assertFailsWith<OCRError.ModelNotFound> { ModelSource.Asset("../secret") }
        assertFailsWith<OCRError.ModelNotFound> { ModelSource.Asset("/absolute/model") }
    }

    @Test
    fun `file source rejects missing empty and directory sources`() {
        val root = createTempDirectory("ocr-source-").toFile()
        try {
            assertFailsWith<OCRError.ModelNotFound> { ModelSource.FilePath(File(root, "missing")).validatedFile() }
            val empty = File(root, "empty.onnx").apply { createNewFile() }
            assertFailsWith<OCRError.ModelNotFound> { ModelSource.FilePath(empty).validatedFile() }
            assertFailsWith<OCRError.ModelNotFound> { ModelSource.FilePath(root).validatedFile() }
        } finally {
            root.deleteRecursively()
        }
    }
}
