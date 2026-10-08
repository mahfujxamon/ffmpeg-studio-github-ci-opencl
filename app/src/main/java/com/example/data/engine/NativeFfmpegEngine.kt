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

    private val openClProbeCallbacks = mutableListOf<(OpenClRuntime.ProbeResult) -> Unit>()
    @Volatile
    private var openClProbeInFlight = false

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
        val normalized = normalizeCommandSeparators(command)

        // Do not treat an arbitrary filename/path containing `opencl` as a GPU
        // request. Detect the actual FFmpeg OpenCL device options or filter names
        // such as `unsharp_opencl`, `nlmeans_opencl`, and `tonemap_opencl`.
        val hasOpenClDeviceOption = Regex(
            "(?:^|\\s)-(?:init_hw_device\\s+opencl\\s*=|filter_hw_device\\s+ocl(?:\\s|$))",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalized)
        val hasOpenClFilter = Regex(
            "(?:^|[,\\s\\[])\\w+_opencl(?:[=:,\\]\\s;]|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalized)
        val hasOpenClSource = Regex(
            "(?:^|[,\\s])opencl(?:src)?(?:[=:,\\]\\s;]|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(normalized)

        return hasOpenClDeviceOption || hasOpenClFilter || hasOpenClSource
    }

    /**
     * Converts a user command into a command that is safe for the capabilities
     * actually confirmed on this device. OpenCL is a real GPU path: when the
     * user requests an OpenCL filter, the command is automatically decorated
     * with an explicit FFmpeg OpenCL hardware device and filter-device binding.
     * MediaCodec encoder fallback remains opportunistic.
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
            // A raw command such as `-vf hwupload,unsharp_opencl=...` used to
            // reach FFmpeg without an AVHWDeviceRef, causing `hwupload` to fail.
            // Make the explicit device propagation deterministic at the app
            // boundary, while preserving any already-specified OpenCL options.
            val decorated = ensureOpenClHardwareDeviceOptions(effective)
            if (decorated != effective) {
                effective = decorated
                notes += "OpenCL request detected; added explicit opencl=ocl:0.0 initialization and filter device binding."
            }

            val probe = OpenClRuntime.lastProbe()
            when {
                probe?.available == true -> {
                    notes += "OpenCL capability probe succeeded; executing the GPU filter path without CPU substitution."
                }
                probe != null -> {
                    // Main product goal is real OpenCL execution. Do not silently
                    // rewrite an explicitly requested OpenCL command to CPU. Keep
                    // the command intact so runtime failures remain visible.
                    notes += "OpenCL was requested but the current probe failed; preserving the OpenCL command for direct GPU-path testing (no CPU substitution)."
                }
                else -> {
                    notes += "OpenCL was requested but no completed probe result is available; preserving the OpenCL command for direct GPU-path testing."
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
     * Ensures every OpenCL command has the two FFmpeg global options required
     * for `hwupload`/OpenCL filters to resolve an AVHWDeviceRef:
     *
     *   -init_hw_device opencl=ocl:0.0
     *   -filter_hw_device ocl
     *
     * Existing options are preserved and never duplicated. The options are
     * prepended so they are unquestionably parsed as global FFmpeg options.
     */
    private fun ensureOpenClHardwareDeviceOptions(command: String): String {
        var effective = normalizeCommandSeparators(command)

        val hasOpenClInit = Regex(
            "(?:^|\\s)-init_hw_device\\s+opencl\\s*=\\s*ocl(?::\\d+(?:\\.\\d+)?)?(?:\\s|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(effective)

        val hasOpenClFilterDevice = Regex(
            "(?:^|\\s)-filter_hw_device\\s+ocl(?:\\s|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(effective)

        val missing = buildList {
            if (!hasOpenClInit) add("-init_hw_device opencl=ocl:0.0")
            if (!hasOpenClFilterDevice) add("-filter_hw_device ocl")
        }

        if (missing.isEmpty()) return effective

        val prefix = missing.joinToString(" ")
        val hasFfmpegExecutable = effective.equals("ffmpeg", ignoreCase = true) ||
                effective.startsWith("ffmpeg ", ignoreCase = true)

        return if (hasFfmpegExecutable) {
            val args = effective.substring(6).trimStart()
            "ffmpeg $prefix $args".trim()
        } else {
            "$prefix $effective".trim()
        }
    }

    /**
     * Uses the actual FFmpeg binary to initialize an OpenCL hardware device.
     * This is stronger than merely checking for a library file: failure means
     * the exact FFmpegKit/OpenCL runtime path could not create an OpenCL device.
     */
    fun probeOpenClAsync(onComplete: (OpenClRuntime.ProbeResult) -> Unit) {
        val cached = OpenClRuntime.lastProbe()
        if (cached != null) {
            onComplete(cached)
            return
        }

        synchronized(openClProbeCallbacks) {
            openClProbeCallbacks += onComplete
            if (openClProbeInFlight) return
            openClProbeInFlight = true
        }

        val preparation = ensureOpenClStartup()

        Thread({
            val nativeReport = try {
                OpenClNativeProbe.probe()
            } catch (t: Throwable) {
                "native probe exception: ${buildDetailedErrorChain(t)}"
            }

            val probeCommand =
                "-hide_banner -nostdin -loglevel error " +
                    "-init_hw_device opencl=ocl:0.0 " +
                    "-filter_hw_device ocl " +
                    "-f lavfi -i color=c=black:s=16x16:r=1 " +
                    "-vf \"hwupload,unsharp_opencl=lx=5:ly=5:la=1.5,hwdownload,format=yuv420p\" " +
                    "-frames:v 1 -f null -"

            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeoutMs = 12_000L

            fun complete(result: OpenClRuntime.ProbeResult) {
                if (!finished.compareAndSet(false, true)) return
                OpenClRuntime.setProbeResult(result)
                val callbacks = synchronized(openClProbeCallbacks) {
                    openClProbeInFlight = false
                    val pending = openClProbeCallbacks.toList()
                    openClProbeCallbacks.clear()
                    pending
                }
                callbacks.forEach { callback ->
                    try { callback(result) } catch (_: Throwable) { }
                }
            }

            try {
                // Use executeAsync here. A few Android vendor OpenCL loaders can
                // block inside clGetPlatformIDs during process initialization.
                // The old synchronous execute() made the UI wait forever on those
                // devices. A bounded async probe lets us cancel the stuck session
                // and return a real diagnostic instead.
                val session = FFmpegKit.executeAsync(probeCommand) { completed ->
                    val success = ReturnCode.isSuccess(completed.returnCode)
                    val ffmpegDetail = if (success) {
                        "FFmpeg OpenCL hardware-filter smoke test succeeded (device init + hwupload + unsharp_opencl + hwdownload)."
                    } else {
                        val failure = completed.failStackTrace
                        val logs = completed.logsAsString
                        when {
                            !failure.isNullOrBlank() -> failure.takeLast(1600)
                            !logs.isNullOrBlank() -> logs.takeLast(1600)
                            else -> "FFmpeg OpenCL probe failed with return code ${completed.returnCode?.value}."
                        }
                    }
                    val detail = buildString {
                        append(preparation.message)
                        if (preparation.icdLibraries.isNotEmpty()) {
                            append(" ICDs=")
                            append(preparation.icdLibraries.joinToString())
                        }
                        append(" Native=")
                        append(nativeReport.replace('\n', ' ').takeLast(2400))
                        append(" Probe=")
                        append(ffmpegDetail)
                    }
                    complete(OpenClRuntime.ProbeResult(success, detail))
                }

                Thread({
                    try {
                        Thread.sleep(timeoutMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (!finished.get()) {
                        try { session.cancel() } catch (_: Throwable) { }
                        val detail = buildString {
                            append(preparation.message)
                            append(" Native=")
                            append(nativeReport.replace('\n', ' ').takeLast(2400))
                            append(" Probe=timed out after ${timeoutMs}ms and was cancelled. ")
                            append("The device OpenCL runtime did not respond through the Khronos FFmpeg path.")
                        }
                        complete(OpenClRuntime.ProbeResult(false, detail))
                    }
                }, "opencl-probe-timeout").start()
            } catch (t: Throwable) {
                complete(
                    OpenClRuntime.ProbeResult(
                        false,
                        "${preparation.message} Native=${nativeReport.replace('\n', ' ').takeLast(2400)} Probe exception=${buildDetailedErrorChain(t)}"
                    )
                )
            }
        }, "opencl-capability-probe").start()
    }

    override fun execute(
        command: String,
        totalDurationMs: Long,
        onLog: (String) -> Unit,
        onStatistics: (RenderProgress) -> Unit,
        onComplete: (success: Boolean, returnCode: Int?, outputPath: String?, error: String?) -> Unit
    ): EngineSession {
        // Runtime bootstrap is owned by this engine. The caller normally passes
        // the command returned by prepareCommandForRuntime(), but execution is
        // defensive too: an OpenCL command can never reach FFmpeg without the
        // required device options.
        ensureOpenClStartup()

        val strippedCommand = normalizeCommandSeparators(
            if (command.trimStart().startsWith("ffmpeg ", ignoreCase = true)) {
                command.trimStart().substring(7).trim()
            } else {
                command.trim()
            }
        )

        val cleanCommand = if (isOpenClRequested(strippedCommand)) {
            ensureOpenClHardwareDeviceOptions(strippedCommand)
        } else {
            strippedCommand
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
