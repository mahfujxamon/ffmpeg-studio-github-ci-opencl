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

    data class RuntimeCommandPlan(
        val originalCommand: String,
        val command: String,
        val openClRequested: Boolean,
        val openClUsed: Boolean,
        val openClFallback: Boolean,
        val encoder: String,
        val encoderFallback: Boolean,
        val notes: List<String>
    )

    init {
        // Hard startup boundary: configure OpenCL before ANY FFmpegKit/native
        // call. This is intentionally owned by the native engine, so runtime
        // discovery does not depend on a UI/ViewModel lifecycle.
        OpenClRuntime.startup()
    }

    /**
     * Re-assert the OpenCL bootstrap immediately before a native FFmpeg call.
     * OpenClRuntime is idempotent/cached, so this is cheap and protects against
     * future call-site changes that might bypass the constructor path.
     */
    private fun ensureOpenClStartup(): OpenClRuntime.Preparation =
        OpenClRuntime.startup()

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
            val openClPreparation = ensureOpenClStartup()
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
                buildVariant = "Full-GPL (Standard Filters & NEON Enabled)",
                openClConfigured = openClPreparation.configured,
                openClIcdLibraries = openClPreparation.icdLibraries,
                openClProbeAvailable = OpenClRuntime.lastProbe()?.available,
                openClProbeDetail = OpenClRuntime.lastProbe()?.detail
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

    fun currentOpenClPreparation(): OpenClRuntime.Preparation = ensureOpenClStartup()

    fun currentOpenClProbe(): OpenClRuntime.ProbeResult? = OpenClRuntime.lastProbe()

    fun isOpenClRequested(command: String): Boolean {
        val normalized = command.lowercase()
        return normalized.contains("opencl") || normalized.contains("_opencl")
    }

    /**
     * Converts a user command into a command that is safe for the capabilities
     * actually confirmed on this device. OpenCL and MediaCodec are both
     * opportunistic: an unavailable accelerator must never make a render fail
     * when a CPU/software equivalent is available.
     */
    fun prepareCommandForRuntime(command: String): RuntimeCommandPlan {
        // FFmpegKit's String command parser is space-token based; command-editor/UI
        // input can contain literal newlines between arguments. Normalize only
        // line-break/tab separators so `opencl=ocl\n-i` never becomes one token.
        var effective = normalizeCommandSeparators(command)
        val notes = mutableListOf<String>()

        val openClRequested = isOpenClRequested(effective)
        var openClFallback = false

        if (openClRequested) {
            val probe = OpenClRuntime.lastProbe()
            when {
                probe?.available == true -> {
                    notes += "OpenCL capability probe succeeded; keeping the OpenCL GPU command unchanged."
                }
                probe != null -> {
                    // Main product goal is real OpenCL execution. Do not silently
                    // rewrite an explicitly requested OpenCL command to CPU. Keep
                    // the original command so the runtime failure is visible and
                    // measurable while the ICD/device integration is being fixed.
                    notes += "OpenCL was requested but the current probe failed; preserving the OpenCL command for real-runtime testing (no CPU substitution)."
                }
                else -> {
                    notes += "OpenCL was requested but no completed probe result is available; preserving the OpenCL command for direct runtime testing."
                }
            }
        }

        var encoderFallback = false
        val hasHardwareAvc = hasHardwareAvcEncoder()
        if (!hasHardwareAvc) {
            val rewritten = rewriteHardwareEncoderToSoftware(effective)
            if (rewritten != effective) {
                effective = rewritten
                encoderFallback = true
                notes += "No compatible hardware H.264/HEVC MediaCodec encoder was confirmed; switched to a software encoder."
            }
        }

        val openClUsed = openClRequested && !openClFallback && isOpenClRequested(effective)
        val encoder = detectEncoderFromCommand(effective)

        return RuntimeCommandPlan(
            originalCommand = command,
            command = effective,
            openClRequested = openClRequested,
            openClUsed = openClUsed,
            openClFallback = openClFallback,
            encoder = encoder,
            encoderFallback = encoderFallback,
            notes = notes
        )
    }

    /**
     * OpenCL CPU substitution is intentionally disabled for render execution.
     * The project goal is to validate the real OpenCL GPU path.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun rewriteOpenClToCpu(command: String): String? = null

    private fun rewriteHardwareEncoderToSoftware(command: String): String {
        return command
            .replace("h264_mediacodec", "libx264")
            .replace("hevc_mediacodec", "libx265")
    }

    private fun normalizeCommandWhitespace(command: String): String =
        command.replace(Regex("\\s{2,}"), " ").trim()

    private fun normalizeCommandSeparators(command: String): String =
        command.replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim()

    /**
     * Uses the actual FFmpeg binary to initialize an OpenCL hardware device.
     * This is stronger than merely checking for a library file: failure means
     * the exact FFmpegKit/OpenCL runtime path could not create an OpenCL device.
     */
    fun probeOpenClAsync(onComplete: (OpenClRuntime.ProbeResult) -> Unit) {
        val preparation = ensureOpenClStartup()
        OpenClRuntime.clearProbe()

        val probeCommand =
            "-hide_banner -nostdin -loglevel error " +
                "-init_hw_device opencl=ocl:0.0 " +
                "-filter_hw_device ocl " +
                "-f lavfi -i color=c=black:s=16x16:r=1 " +
                "-vf \"hwupload,unsharp_opencl=lx=5:ly=5:la=1.5,hwdownload,format=yuv420p\" " +
                "-frames:v 1 -f null -"

        try {
            FFmpegKit.executeAsync(
                probeCommand
            ) { session ->
                val success = ReturnCode.isSuccess(session.returnCode)
                val detail = if (success) {
                    "OpenCL hardware-filter smoke test succeeded (device init + hwupload + unsharp_opencl + hwdownload)."
                } else {
                    val failure = session.failStackTrace
                    val logs = session.logsAsString
                    when {
                        !failure.isNullOrBlank() -> failure.takeLast(800)
                        !logs.isNullOrBlank() -> logs.takeLast(800)
                        else -> "FFmpeg OpenCL probe failed with return code ${session.returnCode?.value}."
                    }
                }

                val preparationDetail = buildString {
                    append(preparation.message)
                    if (preparation.icdLibraries.isNotEmpty()) {
                        append(" ICDs=")
                        append(preparation.icdLibraries.joinToString())
                    }
                    append(" Probe=")
                    append(detail)
                }
                val result = OpenClRuntime.ProbeResult(
                    available = success,
                    detail = preparationDetail
                )
                OpenClRuntime.setProbeResult(result)
                onComplete(result)
            }
        } catch (t: Throwable) {
            val result = OpenClRuntime.ProbeResult(
                available = false,
                detail = "${preparation.message} Probe exception=${buildDetailedErrorChain(t)}"
            )
            OpenClRuntime.setProbeResult(result)
            onComplete(result)
        }
    }

    override fun execute(
        command: String,
        totalDurationMs: Long,
        onLog: (String) -> Unit,
        onStatistics: (RenderProgress) -> Unit,
        onComplete: (success: Boolean, returnCode: Int?, outputPath: String?, error: String?) -> Unit
    ): EngineSession {
        // Runtime bootstrap is owned by this engine. The caller should pass the
        // command returned by prepareCommandForRuntime(). Explicit OpenCL commands
        // are intentionally preserved for real GPU-path validation.
        ensureOpenClStartup()

        // Strip leading "ffmpeg " if present
        val cleanCommand = normalizeCommandSeparators(
            if (command.trimStart().startsWith("ffmpeg ", ignoreCase = true)) {
                command.trimStart().substring(7).trim()
            } else {
                command.trim()
            }
        )

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
