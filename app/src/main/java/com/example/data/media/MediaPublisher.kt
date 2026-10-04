package com.example.data.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class PublishedMedia(
    val uri: Uri,
    val publicPath: String, // e.g. "Movies/HybridVideoEditor/filename.mp4"
    val localFile: File
)

class MediaPublisher(private val context: Context) {

    suspend fun publishVideoToGallery(
        sourceFile: File,
        displayName: String
    ): Result<PublishedMedia> = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalStateException("Source video file does not exist or has zero size: ${sourceFile.absolutePath}")
            )
        }

        try {
            val resolver = context.contentResolver
            val sanitizedName = if (displayName.endsWith(".mp4", ignoreCase = true)) {
                displayName
            } else {
                "$displayName.mp4"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ (API 29+): Scoped Storage via MediaStore with IS_PENDING
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HybridVideoEditor")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                }

                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val itemUri = resolver.insert(collection, contentValues)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Failed to insert MediaStore record for video.")
                    )

                try {
                    resolver.openOutputStream(itemUri).use { outStream ->
                        if (outStream == null) {
                            throw IllegalStateException("Unable to open output stream for MediaStore URI: $itemUri")
                        }
                        FileInputStream(sourceFile).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }

                    // Release pending flag so Gallery / Google Photos indexes it immediately
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)

                    val publicDisplayPath = "Movies/HybridVideoEditor/$sanitizedName"
                    Result.success(
                        PublishedMedia(
                            uri = itemUri,
                            publicPath = publicDisplayPath,
                            localFile = sourceFile
                        )
                    )
                } catch (e: Exception) {
                    try {
                        resolver.delete(itemUri, null, null)
                    } catch (_: Exception) {}
                    throw e
                }
            } else {
                // Android <= 28 (API 24 to 28)
                @Suppress("DEPRECATION")
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appMoviesDir = File(moviesDir, "HybridVideoEditor")
                if (!appMoviesDir.exists()) {
                    appMoviesDir.mkdirs()
                }

                val targetFile = File(appMoviesDir, sanitizedName)
                FileInputStream(sourceFile).use { inStream ->
                    FileOutputStream(targetFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }

                var scannedUri: Uri? = null
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("video/mp4")
                ) { _, uri ->
                    scannedUri = uri
                }

                val finalUri = scannedUri ?: Uri.fromFile(targetFile)
                val publicDisplayPath = "Movies/HybridVideoEditor/$sanitizedName"
                Result.success(
                    PublishedMedia(
                        uri = finalUri,
                        publicPath = publicDisplayPath,
                        localFile = targetFile
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
