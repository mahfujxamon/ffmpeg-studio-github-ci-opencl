package com.example.domain.model

import android.net.Uri

sealed interface RenderState {
    data object Idle : RenderState

    data class Probing(val message: String) : RenderState

    data class Ready(val media: ProbedMediaInfo) : RenderState

    data class Rendering(
        val processingMode: String, // e.g. "Processing: CPU | Encoder: MediaCodec AVC" or "Processing: CPU | Encoder: Software"
        val progress: RenderProgress,
        val logs: List<String>,
        val command: String,
        val startedAtMs: Long
    ) : RenderState

    data class Completed(
        val localOutputPath: String,
        val mediaStoreUri: Uri?,
        val publicDisplayPath: String, // e.g. "Movies/HybridVideoEditor/rendered_output_123.mp4"
        val durationMs: Long,
        val finalSizeBytes: Long,
        val encoderUsed: String,
        val logs: List<String>
    ) : RenderState

    data class Failed(
        val error: String,
        val returnCode: Int?,
        val logs: List<String>
    ) : RenderState

    data class Cancelled(
        val logs: List<String>
    ) : RenderState
}
