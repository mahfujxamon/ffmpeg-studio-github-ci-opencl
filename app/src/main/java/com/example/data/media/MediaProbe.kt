package com.example.data.media

import android.media.MediaMetadataRetriever
import com.example.domain.model.ProbedMediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pure Android MediaMetadataRetriever probe.
 * NEVER executes FFmpegKit or FFprobeKit during video selection.
 */
class MediaProbe {

    suspend fun probeFile(file: File, displayName: String): Result<ProbedMediaInfo> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("File does not exist or has zero size: ${file.absolutePath}")
            )
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val captureFpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)

            var width = widthStr?.toIntOrNull() ?: 0
            var height = heightStr?.toIntOrNull() ?: 0
            val rotation = rotationStr?.toIntOrNull() ?: 0

            // Adjust dimensions for rotated portrait videos
            if (rotation == 90 || rotation == 270) {
                val temp = width
                width = height
                height = temp
            }

            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val bitrateKbps = (bitrateStr?.toLongOrNull() ?: 0L) / 1000
            val fps = captureFpsStr?.toFloatOrNull() ?: 30f

            val friendlyFormat = when {
                mimeType?.contains("mp4", ignoreCase = true) == true -> "MP4"
                mimeType?.contains("webm", ignoreCase = true) == true -> "WebM"
                mimeType?.contains("mkv", ignoreCase = true) == true -> "MKV"
                else -> file.extension.uppercase().ifBlank { "MP4" }
            }

            val videoCodec = when {
                mimeType?.contains("avc", ignoreCase = true) == true || mimeType?.contains("h264", ignoreCase = true) == true -> "H.264 (AVC)"
                mimeType?.contains("hevc", ignoreCase = true) == true || mimeType?.contains("h265", ignoreCase = true) == true -> "H.265 (HEVC)"
                mimeType?.contains("vp9", ignoreCase = true) == true -> "VP9"
                mimeType?.contains("av01", ignoreCase = true) == true -> "AV1"
                else -> mimeType ?: "Video Stream"
            }

            val audioCodec = if (hasAudio.equals("yes", ignoreCase = true)) "AAC / Audio Stream" else null

            val probedInfo = ProbedMediaInfo(
                fileName = displayName,
                localPath = file.absolutePath,
                fileSizeBytes = file.length(),
                durationMs = durationMs,
                width = width,
                height = height,
                fps = fps,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                bitrateKbps = bitrateKbps,
                formatName = friendlyFormat
            )

            Result.success(probedInfo)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}
