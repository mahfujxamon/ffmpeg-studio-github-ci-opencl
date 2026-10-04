package com.example.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ResolvedMedia(
    val displayName: String,
    val localFile: File
)

class MediaResolver(private val context: Context) {

    private val inputsDir: File by lazy {
        File(context.cacheDir, "input_videos").apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun resolveContentUri(uri: Uri): Result<ResolvedMedia> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // 1. Extract Display Name
            var displayName = "selected_video.mp4"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) {
                            displayName = name
                        }
                    }
                }
            } catch (_: Exception) {}

            // 2. Generate safe local target in app cache
            val ext = displayName.substringAfterLast('.', "mp4")
            val sanitizedBase = displayName.substringBeforeLast('.')
                .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                .take(32)
            val uniqueLocalFile = File(inputsDir, "${sanitizedBase}_${System.currentTimeMillis()}.$ext")

            // 3. Copy bytes from ContentResolver to local file
            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Unable to open ContentResolver stream for: $uri"))

            inputStream.use { input ->
                FileOutputStream(uniqueLocalFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!uniqueLocalFile.exists() || uniqueLocalFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Resolved file was not written or is empty."))
            }

            Result.success(ResolvedMedia(displayName = displayName, localFile = uniqueLocalFile))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getOutputsDir(): File {
        return File(context.cacheDir, "rendered_outputs").apply {
            if (!exists()) mkdirs()
        }
    }

    fun createOutputFile(preferredName: String = "rendered_video.mp4"): File {
        val dir = getOutputsDir()
        val baseName = preferredName.substringBeforeLast('.', "rendered_video")
        val ext = preferredName.substringAfterLast('.', "mp4")
        return File(dir, "${baseName}_${System.currentTimeMillis()}.$ext")
    }
}
