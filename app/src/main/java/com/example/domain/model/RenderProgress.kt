package com.example.domain.model

import java.util.Locale

data class RenderProgress(
    val frame: Long = 0L,
    val fps: Float = 0f,
    val speed: Float = 0f,
    val bitrateKbps: Float = 0f,
    val timeMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val progressRatio: Float? = null, // 0.0 to 1.0 when total duration is known
    val etaSeconds: Long? = null
) {
    val formattedSpeed: String
        get() = if (speed > 0f) String.format(Locale.US, "%.2fx", speed) else "--"

    val formattedFps: String
        get() = if (fps > 0f) String.format(Locale.US, "%.1f", fps) else "--"

    val formattedBitrate: String
        get() = if (bitrateKbps > 0f) String.format(Locale.US, "%.0f kbps", bitrateKbps) else "--"

    val formattedTime: String
        get() {
            val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60
            val millis = (timeMs % 1000) / 100
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d.%d", hours, minutes % 60, seconds, millis)
            } else {
                String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, millis)
            }
        }

    val formattedSize: String
        get() {
            val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                val kb = sizeBytes.toDouble() / 1024.0
                String.format(Locale.US, "%.0f KB", kb)
            }
        }
}
