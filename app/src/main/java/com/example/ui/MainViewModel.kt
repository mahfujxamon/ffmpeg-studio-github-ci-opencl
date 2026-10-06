package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.engine.EngineSession
import com.example.data.engine.NativeFfmpegEngine
import com.example.data.media.AssetResolutionResult
import com.example.data.media.AssetResolver
import com.example.data.media.MediaProbe
import com.example.data.media.MediaPublisher
import com.example.data.media.MediaResolver
import com.example.domain.model.NativeRuntimeStatus
import com.example.domain.model.ProbedMediaInfo
import com.example.domain.model.RenderProgress
import com.example.domain.model.RenderState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mediaResolver = MediaResolver(application)
    private val mediaPublisher = MediaPublisher(application)
    private val assetResolver = AssetResolver(application)
    private val mediaProbe = MediaProbe()
    private val nativeEngine = NativeFfmpegEngine()

    // Native FFmpeg runtime status
    private val _nativeStatus = MutableStateFlow<NativeRuntimeStatus>(
        nativeEngine.checkNativeRuntime()
    )
    val nativeStatus: StateFlow<NativeRuntimeStatus> = _nativeStatus.asStateFlow()

    init {
        nativeEngine.probeOpenClAsync {
            _nativeStatus.value = nativeEngine.checkNativeRuntime()
        }
    }

    // State: App-managed FFmpeg assets
    private val _availableAssets = MutableStateFlow<List<File>>(assetResolver.listAssets())
    val availableAssets: StateFlow<List<File>> = _availableAssets.asStateFlow()

    // State: Selected media info
    private val _probedMedia = MutableStateFlow<ProbedMediaInfo?>(null)
    val probedMedia: StateFlow<ProbedMediaInfo?> = _probedMedia.asStateFlow()

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    private val _probingMessage = MutableStateFlow<String?>(null)
    val probingMessage: StateFlow<String?> = _probingMessage.asStateFlow()

    // State: User command input
    private val defaultCommandTemplate = "ffmpeg -i {input} -c:v h264_mediacodec -b:v 4M -c:a aac {output}"
    private val _commandInput = MutableStateFlow(defaultCommandTemplate)
    val commandInput: StateFlow<String> = _commandInput.asStateFlow()

    // State: Render lifecycle & monitor
    private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
    val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

    private val _currentProgress = MutableStateFlow(RenderProgress())
    val currentProgress: StateFlow<RenderProgress> = _currentProgress.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<String>>(emptyList())
    val terminalLogs: StateFlow<List<String>> = _terminalLogs.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var activeSession: EngineSession? = null

    val isHwEncoderAvailable: Boolean
        get() = nativeEngine.hasHardwareAvcEncoder()

    val processingModeDescription: String
        get() = nativeEngine.processingMode

    fun onCommandChange(newCommand: String) {
        _commandInput.value = newCommand
    }

    fun resetCommandTemplate() {
        _commandInput.value = defaultCommandTemplate
        emitMessage("Reset command to default template.")
    }

    fun importAsset(uri: Uri) {
        viewModelScope.launch {
            val result = assetResolver.importAsset(uri)
            if (result.isSuccess) {
                val file: File = result.getOrThrow()
                _availableAssets.value = assetResolver.listAssets()
                emitMessage("Imported asset: ${file.name}")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Asset import failed"
                emitMessage("Error: $err")
            }
        }
    }

    fun deleteAsset(file: File) {
        if (assetResolver.deleteAsset(file)) {
            _availableAssets.value = assetResolver.listAssets()
            emitMessage("Deleted asset: ${file.name}")
        }
    }

    /**
     * Re-checks the native FFmpeg runtime health safely.
     */
    fun refreshNativeStatus() {
        _nativeStatus.value = nativeEngine.checkNativeRuntime()
        nativeEngine.probeOpenClAsync {
            _nativeStatus.value = nativeEngine.checkNativeRuntime()
        }
    }

    /**
     * Called when the user picks a video in Android Photo Picker.
     * MANDATORY: NEVER executes FFmpeg or FFprobe during selection.
     * Uses ONLY ContentResolver and Android MediaMetadataRetriever.
     */
    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            _isProbing.value = true
            _probingMessage.value = "Caching media file to local app storage..."
            _renderState.value = RenderState.Probing("Caching media...")

            // 1. Copy content:// into app cache
            val resolveResult = mediaResolver.resolveContentUri(uri)
            if (resolveResult.isFailure) {
                val err = resolveResult.exceptionOrNull()?.message ?: "Failed to copy video to local cache."
                _isProbing.value = false
                _probingMessage.value = null
                _renderState.value = RenderState.Failed(err, null, emptyList())
                emitMessage("Error: $err")
                return@launch
            }

            val resolved = resolveResult.getOrThrow()
            _probingMessage.value = "Reading video metadata with MediaMetadataRetriever..."

            // 2. Probe with pure Android MediaMetadataRetriever (NEVER calls native FFmpeg)
            val probeResult = mediaProbe.probeFile(resolved.localFile, resolved.displayName)
            _isProbing.value = false
            _probingMessage.value = null

            if (probeResult.isSuccess) {
                val mediaInfo = probeResult.getOrThrow()
                _probedMedia.value = mediaInfo
                _renderState.value = RenderState.Ready(mediaInfo)
                emitMessage("Video ready: ${mediaInfo.resolutionFormatted} • ${mediaInfo.durationFormatted}")
            } else {
                val err = probeResult.exceptionOrNull()?.message ?: "Media probe failed."
                _probedMedia.value = null
                _renderState.value = RenderState.Failed(err, null, emptyList())
                emitMessage("Probe failed: $err")
            }
        }
    }

    fun clearSelectedVideo() {
        _probedMedia.value = null
        _renderState.value = RenderState.Idle
        _terminalLogs.value = emptyList()
        _currentProgress.value = RenderProgress()
    }

    /**
     * Executes real FFmpeg only when user explicitly presses RENDER.
     */
    fun startRender() {
        val rawCommand = _commandInput.value.trim()

        // Validation 1: Command must not be empty
        if (rawCommand.isBlank()) {
            emitMessage("Please enter an FFmpeg command.")
            return
        }

        // Validation 2: Check native FFmpeg runtime health
        val runtimeStatus = nativeEngine.checkNativeRuntime()
        _nativeStatus.value = runtimeStatus
        if (runtimeStatus is NativeRuntimeStatus.Unavailable) {
            val errMsg = "Native FFmpeg runtime is unavailable: ${runtimeStatus.rootCause}"
            _renderState.value = RenderState.Failed(errMsg, null, listOf(errMsg, runtimeStatus.fullStackTrace.take(500)))
            _terminalLogs.value = listOf("[CRITICAL ERROR] $errMsg")
            emitMessage(errMsg)
            return
        }

        // Validation 3: Video must be selected if {input} macro is used
        val media = _probedMedia.value
        if (rawCommand.contains("{input}")) {
            if (media == null) {
                emitMessage("Please select a video first.")
                return
            }
            val inputFile = File(media.localPath)
            if (!inputFile.exists() || inputFile.length() == 0L) {
                emitMessage("Selected cached video file is missing or empty.")
                return
            }
        }

        // Validation 4: Generate clean output target path in cache
        val tempOutputFile = mediaResolver.createOutputFile("rendered_output_${System.currentTimeMillis()}.mp4")
        val inputPathSafe = safePath(media?.localPath ?: "")
        val outputPathSafe = safePath(tempOutputFile.absolutePath)

        val substitutedCommand = if (rawCommand.contains("{output}")) {
            rawCommand
                .replace("{input}", inputPathSafe)
                .replace("{output}", outputPathSafe)
        } else {
            rawCommand.replace("{input}", inputPathSafe)
        }

        // Validation 5: Generic external asset resolution (movie=, amovie=, relative -i)
        val assetResolution = assetResolver.resolveAssetsInCommand(substitutedCommand)
        if (assetResolution is AssetResolutionResult.MissingAsset) {
            val missingPath = assetResolution.assetPath
            val errMsg = "Missing external asset: '$missingPath'. Please import it into FFmpeg Assets before rendering."
            _renderState.value = RenderState.Failed(errMsg, null, listOf(errMsg))
            _terminalLogs.value = listOf("[ERROR] $errMsg")
            emitMessage(errMsg)
            return
        }

        val commandWithAssets = (assetResolution as AssetResolutionResult.Success).resolvedCommand

        // Automatic sensible filter threading for complex filtergraphs
        val finalCommand = assetResolver.applyOptimalFilterThreading(commandWithAssets)

        // Validation 6: Final command must have arguments
        val cleanArgs = finalCommand.trimStart().removePrefix("ffmpeg").trim()
        if (cleanArgs.isBlank()) {
            emitMessage("Command has no arguments to execute.")
            return
        }

        val logsList = mutableListOf<String>()
        _terminalLogs.value = emptyList()
        _currentProgress.value = RenderProgress()

        val startedAt = System.currentTimeMillis()
        val runtimePlan = nativeEngine.prepareCommandForRuntime(finalCommand)
        val executionCommand = runtimePlan.command
        val detectedEncoder = runtimePlan.encoder

        val encoderDescription = when (detectedEncoder) {
            "MediaCodec AVC" -> "MediaCodec AVC"
            "Stream Copy" -> "Stream Copy"
            else -> "Software"
        }

        val processingMode = when {
            runtimePlan.openClUsed -> "Processing: OpenCL | Encoder: $encoderDescription"
            runtimePlan.openClFallback -> "Processing: CPU fallback (OpenCL unavailable) | Encoder: $encoderDescription"
            else -> "Processing: CPU | Encoder: $encoderDescription"
        }

        _renderState.value = RenderState.Rendering(
            processingMode = processingMode,
            progress = RenderProgress(),
            logs = emptyList(),
            command = executionCommand,
            startedAtMs = startedAt
        )

        logsList.add("[EXECUTION ENGINE] Native FFmpegKit")
        logsList.add("[PIPELINE] $processingMode")
        val openClPreparation = nativeEngine.currentOpenClPreparation()
        logsList.add("[OPENCL] ${openClPreparation.message}")
        if (openClPreparation.icdLibraries.isNotEmpty()) {
            logsList.add("[OPENCL] ICD candidates: ${openClPreparation.icdLibraries.joinToString()}")
        }
        if (openClPreparation.libraryCandidates.isNotEmpty()) {
            logsList.add("[OPENCL] System libOpenCL candidates: ${openClPreparation.libraryCandidates.joinToString()}")
        } else {
            logsList.add("[OPENCL] No readable system libOpenCL*.so candidate discovered.")
        }
        nativeEngine.currentOpenClProbe()?.detail?.let {
            logsList.add("[OPENCL PROBE] $it")
        }
        runtimePlan.notes.forEach { note -> logsList.add("[RUNTIME] $note") }
        if (executionCommand != finalCommand) {
            logsList.add("[COMMAND ORIGINAL] $finalCommand")
            logsList.add("[COMMAND EFFECTIVE] $executionCommand")
        } else {
            logsList.add("[COMMAND] $executionCommand")
        }
        _terminalLogs.value = logsList.toList()

        val totalDurationMs = media?.durationMs ?: 0L

        // Execute real FFmpegKit session
        activeSession = nativeEngine.execute(
            command = executionCommand,
            totalDurationMs = totalDurationMs,
            onLog = { logLine ->
                logsList.add(logLine)
                if (logsList.size > 600) {
                    logsList.removeAt(0)
                }
                _terminalLogs.value = logsList.toList()
            },
            onStatistics = { stats ->
                _currentProgress.value = stats
            },
            onComplete = { success, returnCode, outputPath, error ->
                activeSession = null
                val durationMs = System.currentTimeMillis() - startedAt

                if (success && outputPath != null) {
                    val outFile = File(outputPath)
                    val fileSize = if (outFile.exists()) outFile.length() else 0L

                    if (!outFile.exists() || fileSize == 0L) {
                        val errMsg = "FFmpeg completed but output file is missing or 0 bytes: $outputPath"
                        logsList.add("[ERROR] $errMsg")
                        _terminalLogs.value = logsList.toList()
                        _renderState.value = RenderState.Failed(errMsg, returnCode, logsList.toList())
                        emitMessage(errMsg)
                        return@execute
                    }

                    logsList.add("[COMPLETE] Finished rendering in ${durationMs / 1000}s (${fileSize / 1024} KB)")
                    logsList.add("[PUBLISHING] Publishing MP4 to Android MediaStore (Movies/HybridVideoEditor)...")
                    _terminalLogs.value = logsList.toList()

                    // Mandatory MediaStore publishing
                    viewModelScope.launch {
                        val publishResult = mediaPublisher.publishVideoToGallery(
                            sourceFile = outFile,
                            displayName = outFile.name
                        )

                        if (publishResult.isSuccess) {
                            val published = publishResult.getOrThrow()
                            logsList.add("[GALLERY SUCCESS] Saved to: ${published.publicPath}")
                            _terminalLogs.value = logsList.toList()

                            _renderState.value = RenderState.Completed(
                                localOutputPath = outputPath,
                                mediaStoreUri = published.uri,
                                publicDisplayPath = published.publicPath,
                                durationMs = durationMs,
                                finalSizeBytes = fileSize,
                                encoderUsed = detectedEncoder,
                                logs = logsList.toList()
                            )
                            emitMessage("Render complete! Saved to Movies/HybridVideoEditor")
                        } else {
                            val publishErr = publishResult.exceptionOrNull()?.message ?: "MediaStore publish failed"
                            logsList.add("[GALLERY WARNING] MediaStore publish failed: $publishErr")
                            logsList.add("[FALLBACK] Local cache file available at: $outputPath")
                            _terminalLogs.value = logsList.toList()

                            _renderState.value = RenderState.Completed(
                                localOutputPath = outputPath,
                                mediaStoreUri = null,
                                publicDisplayPath = outputPath,
                                durationMs = durationMs,
                                finalSizeBytes = fileSize,
                                encoderUsed = detectedEncoder,
                                logs = logsList.toList()
                            )
                            emitMessage("Render finished. (Gallery publication issue: $publishErr)")
                        }
                    }
                } else if (error?.contains("cancelled", ignoreCase = true) == true) {
                    logsList.add("[CANCELLED] FFmpeg execution cancelled by user.")
                    _terminalLogs.value = logsList.toList()
                    _renderState.value = RenderState.Cancelled(logsList.toList())
                    emitMessage("Render cancelled.")
                } else {
                    val errMsg = error ?: "Execution failed with return code $returnCode"
                    logsList.add("[ERROR] $errMsg")
                    _terminalLogs.value = logsList.toList()
                    _renderState.value = RenderState.Failed(errMsg, returnCode, logsList.toList())
                    emitMessage("Render Failed: $errMsg")
                }
            }
        )
    }

    /**
     * Cancels the active FFmpegKit session directly.
     */
    fun stopRender() {
        if (activeSession != null) {
            emitMessage("Cancelling FFmpegKit session...")
            activeSession?.cancel()
            activeSession = null
        }
    }

    fun copyLogsToClipboard() {
        val logs = _terminalLogs.value.joinToString("\n")
        copyToClipboard(logs, "FFmpeg Logs")
    }

    fun copyCommandToClipboard() {
        copyToClipboard(_commandInput.value, "FFmpeg Command")
    }

    fun openOutputVideo(context: Context, state: RenderState.Completed) {
        val uriToOpen: Uri = state.mediaStoreUri ?: run {
            val file = File(state.localOutputPath)
            if (!file.exists()) {
                emitMessage("Output file does not exist.")
                return
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriToOpen, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            emitMessage("No video player found to open: ${state.publicDisplayPath}")
        }
    }

    fun shareOutputVideo(context: Context, state: RenderState.Completed) {
        val uriToShare: Uri = state.mediaStoreUri ?: run {
            val file = File(state.localOutputPath)
            if (!file.exists()) {
                emitMessage("Output file does not exist.")
                return
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uriToShare)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share Rendered Video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            emitMessage("Unable to share video: ${e.message}")
        }
    }

    private fun safePath(path: String): String {
        return if (path.contains(" ") && !path.startsWith("\"") && !path.startsWith("'")) {
            "\"$path\""
        } else {
            path
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        try {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
            emitMessage("Copied to clipboard!")
        } catch (_: Exception) {
            emitMessage("Copied!")
        }
    }

    private fun emitMessage(msg: String) {
        viewModelScope.launch {
            _userMessage.emit(msg)
        }
    }
}
