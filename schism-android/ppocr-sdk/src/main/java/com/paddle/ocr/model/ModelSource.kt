package com.paddle.ocr.model

import android.content.Context
import java.io.File
import java.io.InputStream

sealed interface ModelSource {
    data class Asset(val path: String) : ModelSource {
        init {
            if (path.isBlank() || path.startsWith('/') || path.split('/').any { it == ".." }) {
                throw OCRError.ModelNotFound(path)
            }
        }
    }

    data class FilePath(val file: File) : ModelSource {
        fun validatedFile(): File {
            val canonical = try {
                file.canonicalFile
            } catch (error: Throwable) {
                throw OCRError.ModelNotFound(file.path, error)
            }
            if (!canonical.isFile || canonical.length() <= 0L) {
                throw OCRError.ModelNotFound(canonical.path)
            }
            return canonical
        }
    }

    fun open(context: Context): InputStream = when (this) {
        is Asset -> try {
            context.assets.open(path)
        } catch (error: Throwable) {
            throw OCRError.ModelNotFound(path, error)
        }
        is FilePath -> try {
            validatedFile().inputStream()
        } catch (error: OCRError.ModelNotFound) {
            throw error
        } catch (error: Throwable) {
            throw OCRError.ModelNotFound(file.path, error)
        }
    }
}
