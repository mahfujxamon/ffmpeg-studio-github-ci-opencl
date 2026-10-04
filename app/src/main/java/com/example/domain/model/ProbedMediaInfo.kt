package com.example.domain.model

import java.util.Locale

data class ProbedMediaInfo(
    val fileName: String,
    val localPath: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val videoCodec: String?,
    val audioCodec: String?,
    val bitrateKbps: Long,
    val formatName: String?
) {
    val durationFormatted: String
        get() {
            if (durationMs <= 0) return "00:00"
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

    val resolutionFormatted: String
        get() = if (width > 0 && height > 0) "${width}x${height}" else "Unknown Res"

    val sizeFormatted: String
        get() {
            val mb = fileSizeBytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                val kb = fileSizeBytes.toDouble() / 1024.0
                String.format(Locale.US, "%.0f KB", kb)
            }
        }

    val fpsFormatted: String
        get() = if (fps > 0) String.format(Locale.US, "%.1f fps", fps) else "N/A"
}
