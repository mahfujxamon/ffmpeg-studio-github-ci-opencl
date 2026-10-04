package com.example.data.engine

import com.example.domain.model.RenderProgress

interface EngineSession {
    fun cancel()
}

interface ExecutionEngine {
    val name: String
    val processingMode: String

    fun execute(
        command: String,
        totalDurationMs: Long,
        onLog: (String) -> Unit,
        onStatistics: (RenderProgress) -> Unit,
        onComplete: (success: Boolean, returnCode: Int?, outputPath: String?, error: String?) -> Unit
    ): EngineSession
}
