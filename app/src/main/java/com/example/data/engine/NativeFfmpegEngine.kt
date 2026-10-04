package com.example.data.engine

import android.media.MediaCodecList
import com.arthenica.ffmpegkit.AbiDetect
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.example.domain.model.NativeRuntimeStatus
import com.example.domain.model.RenderProgress
import java.io.File

class NativeFfmpegEngine : ExecutionEngine {

    override val name: String = "Native FFmpegKit"

    override val processingMode: String
        get() = if (hasHardwareAvcEncoder()) {
            "Processing: CPU | Available: MediaCodec AVC"
        } else {
            "Processing: CPU | Available: Software Only"
        }

    /**
     * Performs a safe native FFmpegKit availability and health check.
     * Never crashes. Captures full cause chain and stack trace if native loading fails.
     */
    fun checkNativeRuntime(): NativeRuntimeStatus {
        return try {
            val version = FFmpegKitConfig.getVersion() ?: "v8.1.9"
            val abi = try {
                AbiDetect.getAbi() ?: "unknown"
            } catch (_: Throwable) {
                "unknown"
            }
            NativeRuntimeStatus.Ready(
                version = version,
                abi = abi,
                hasHwAvc = hasHardwareAvcEncoder(),
                hasBoxblur = true,
                buildVariant = "Full-GPL (Standard Filters & NEON Enabled)"
            )
        } catch (t: Throwable) {
            val causeChain = buildDetailedErrorChain(t)
            NativeRuntimeStatus.Unavailable(
                rootCause = causeChain,
                fullStackTrace = t.stackTraceToString()
            )
        }
    }

    /**
     * Detects the ACTUAL encoder specified in the user's FFmpeg command.
     * Only returns "MediaCodec AVC" if "h264_mediacodec" is explicitly in the command.
     */
    fun detectEncoderFromCommand(command: String): String {
        return when {
            command.contains("h264_mediacodec", ignoreCase = false) ||
            command.contains("hevc_mediacodec", ignoreCase = false) -> "MediaCodec AVC"
            command.contains("-c copy", ignoreCase = true) ||
            command.contains("-c:v copy", ignoreCase = true) -> "Stream Copy"
            else -> "Software"
        }
    }

    override fun execute(
        command: String,
        totalDurationMs: Long,
        onLog: (String) -> Unit,
        onStatistics: (RenderProgress) -> Unit,
        onComplete: (success: Boolean, returnCode: Int?, outputPath: String?, error: String?) -> Unit
    ): EngineSession {
        // Strip leading "ffmpeg " if present
        val cleanCommand = if (command.trimStart().startsWith("ffmpeg ", ignoreCase = true)) {
            command.trimStart().substring(7).trim()
        } else {
            command.trim()
        }

        val expectedOutputPath = extractOutputPath(cleanCommand)

        return try {
            val session: FFmpegSession = FFmpegKit.executeAsync(
                cleanCommand,
                { completedSession ->
                    val returnCode = completedSession.returnCode
                    val rcValue = returnCode?.value

                    if (ReturnCode.isSuccess(returnCode)) {
                        val outValid = expectedOutputPath != null &&
                                File(expectedOutputPath).exists() &&
                                File(expectedOutputPath).length() > 0L

                        if (outValid) {
                            onComplete(true, rcValue, expectedOutputPath, null)
                        } else if (expectedOutputPath == null) {
                            onComplete(true, rcValue, null, null)
                        } else {
                            onComplete(false, rcValue, expectedOutputPath, "FFmpeg exited with success, but output file was not created or is 0 bytes.")
                        }
                    } else if (ReturnCode.isCancel(returnCode)) {
                        onComplete(false, rcValue, expectedOutputPath, "Execution cancelled by user.")
                    } else {
                        val failStack = completedSession.failStackTrace
                        val logs = completedSession.logsAsString
                        val errorDetail = when {
                            !failStack.isNullOrBlank() -> failStack
                            !logs.isNullOrBlank() -> logs.takeLast(400)
                            else -> "FFmpeg command failed with return code $rcValue"
                        }
                        onComplete(false, rcValue, expectedOutputPath, errorDetail)
                    }
                },
                { log ->
                    val text = log.message
                    if (!text.isNullOrBlank()) {
                        onLog(text.trimEnd())
                    }
                },
                { statistics ->
                    val timeMs = statistics.time.toLong()
                    val speed = statistics.speed.toFloat()
                    val fps = statistics.videoFps
                    val frame = statistics.videoFrameNumber.toLong()
                    val bitrate = statistics.bitrate.toFloat()
                    val sizeBytes = statistics.size

                    val ratio = if (totalDurationMs > 0 && timeMs > 0) {
                        (timeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                    } else null

                    val eta = if (ratio != null && ratio > 0.05f && speed > 0.1f) {
                        val remainingMs = totalDurationMs - timeMs
                        if (remainingMs > 0) (remainingMs / (speed * 1000)).toLong() else 0L
                    } else null

                    onStatistics(
                        RenderProgress(
                            frame = frame,
                            fps = fps,
                            speed = speed,
                            bitrateKbps = bitrate,
                            timeMs = timeMs,
                            sizeBytes = sizeBytes,
                            progressRatio = ratio,
                            etaSeconds = eta
                        )
                    )
                }
            )

            object : EngineSession {
                override fun cancel() {
                    session.cancel()
                }
            }
        } catch (t: Throwable) {
            val detailedError = buildDetailedErrorChain(t)
            onLog("[ERROR] Native FFmpeg execution failed to start: $detailedError")
            onComplete(false, -1, expectedOutputPath, detailedError)
            object : EngineSession {
                override fun cancel() {}
            }
        }
    }

    private fun extractOutputPath(command: String): String? {
        val tokens = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val last = tokens.last().trim('"', '\'')
        return if (!last.startsWith("-") && (last.contains(".") || last.contains("/"))) {
            last
        } else null
    }

    fun hasHardwareAvcEncoder(): Boolean {
        return try {
            val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in codecInfos) {
                if (info.isEncoder) {
                    val isHw = !info.name.startsWith("OMX.google.") &&
                            !info.name.startsWith("c2.android.") &&
                            !info.name.contains("sw", ignoreCase = true) &&
                            !info.name.contains("soft", ignoreCase = true)
                    for (type in info.supportedTypes) {
                        if (type.equals("video/avc", ignoreCase = true) && isHw) {
                            return true
                        }
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun buildDetailedErrorChain(t: Throwable): String {
        val list = mutableListOf<String>()
        var curr: Throwable? = t
        while (curr != null) {
            val name = curr.javaClass.simpleName.ifBlank { curr.javaClass.name }
            val msg = curr.message?.trim() ?: "No message"
            list.add("$name: $msg")
            curr = curr.cause
        }
        return list.joinToString(" -> ")
    }
}
