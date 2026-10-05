package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.NativeRuntimeStatus
import com.example.domain.model.RenderState
import com.example.ui.components.CommandEditorCard
import com.example.ui.components.OutputActionsCard
import com.example.ui.components.RenderMonitorCard
import com.example.ui.components.VideoPreviewCard
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBg
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioRose
import com.example.ui.theme.StudioSurfaceCard
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nativeStatus by viewModel.nativeStatus.collectAsState()
    val probedMedia by viewModel.probedMedia.collectAsState()
    val isProbing by viewModel.isProbing.collectAsState()
    val probingMessage by viewModel.probingMessage.collectAsState()
    val command by viewModel.commandInput.collectAsState()
    val renderState by viewModel.renderState.collectAsState()
    val currentProgress by viewModel.currentProgress.collectAsState()
    val terminalLogs by viewModel.terminalLogs.collectAsState()

    val isRendering = renderState is RenderState.Rendering
    val availableAssets by viewModel.availableAssets.collectAsState()

    // File picker launcher for generic FFmpeg external assets (watermarks, audio, overlays)
    val assetPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importAsset(uri)
        }
    }

    // Photo picker launcher: zero-permission media selection (compliant with Play policy)
    // NEVER executes FFmpeg during selection.
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onVideoSelected(uri)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBg)
            .padding(horizontal = 16.dp)
            .testTag("main_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            // 1. App Header with genuine runtime status
            AppHeader(
                nativeStatus = nativeStatus,
                isHwAvailable = viewModel.isHwEncoderAvailable
            )
        }

        // 2. Video Preview & Probed Info & SELECT VIDEO button
        item {
            VideoPreviewCard(
                mediaInfo = probedMedia,
                isProbing = isProbing,
                probingMessage = probingMessage,
                onSelectVideo = {
                    mediaPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                onClearVideo = { viewModel.clearSelectedVideo() }
            )
        }

        // 3. User-controlled FFmpeg Command Editor
        item {
            CommandEditorCard(
                command = command,
                onCommandChange = { viewModel.onCommandChange(it) },
                onResetCommand = { viewModel.resetCommandTemplate() },
                onCopyCommand = { viewModel.copyCommandToClipboard() }
            )
        }

        // 4. App-managed Generic FFmpeg Assets (watermarks, overlays, audio)
        item {
            com.example.ui.components.AssetsCard(
                assets = availableAssets,
                onImportAsset = { assetPickerLauncher.launch("*/*") },
                onDeleteAsset = { viewModel.deleteAsset(it) },
                onInsertAssetToken = { assetName ->
                    val token = if (assetName.endsWith(".mp3", ignoreCase = true) ||
                        assetName.endsWith(".wav", ignoreCase = true) ||
                        assetName.endsWith(".aac", ignoreCase = true) ||
                        assetName.endsWith(".m4a", ignoreCase = true)
                    ) {
                        "amovie=$assetName"
                    } else {
                        "movie=$assetName"
                    }
                    val current = viewModel.commandInput.value
                    viewModel.onCommandChange("$current $token")
                }
            )
        }

        // 5. Primary RENDER or STOP button
        item {
            if (isRendering) {
                Button(
                    onClick = { viewModel.stopRender() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("stop_action_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioRose,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STOP RENDERING",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
            } else {
                Button(
                    onClick = { viewModel.startRender() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("render_action_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCyan,
                        contentColor = Color(0xFF03101E)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RENDER VIDEO",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }

        // 5. Output Ready Card (Play & Share from Gallery / MediaStore)
        item {
            if (renderState is RenderState.Completed) {
                val completed = renderState as RenderState.Completed
                OutputActionsCard(
                    outputPath = completed.localOutputPath,
                    publicDisplayPath = completed.publicDisplayPath,
                    durationMs = completed.durationMs,
                    fileSizeBytes = completed.finalSizeBytes,
                    encoderUsed = completed.encoderUsed,
                    onOpenOutput = { viewModel.openOutputVideo(context, completed) },
                    onShareOutput = { viewModel.shareOutputVideo(context, completed) }
                )
            }
        }

        // 6. Real Render Monitor & Live Terminal Logs
        item {
            val shouldShowMonitor = isRendering ||
                    renderState is RenderState.Completed ||
                    renderState is RenderState.Failed ||
                    renderState is RenderState.Cancelled ||
                    terminalLogs.isNotEmpty()

            if (shouldShowMonitor) {
                val processingMode = when (val state = renderState) {
                    is RenderState.Rendering -> state.processingMode
                    is RenderState.Completed -> "Processing: CPU | Encoder: ${state.encoderUsed}"
                    else -> viewModel.processingModeDescription
                }

                RenderMonitorCard(
                    isRendering = isRendering,
                    processingMode = processingMode,
                    progress = currentProgress,
                    logs = terminalLogs,
                    onStop = { viewModel.stopRender() },
                    onCopyLogs = { viewModel.copyLogsToClipboard() }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AppHeader(
    nativeStatus: NativeRuntimeStatus,
    isHwAvailable: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(StudioCyan, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Native FFmpeg Engine",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StudioCyan,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "HYBRID VIDEO EDITOR",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary,
                    letterSpacing = 0.5.sp
                )
            }

            // Real Hardware capability badge (honest wording: "Available: ...")
            Surface(
                color = StudioSurfaceCard,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isHwAvailable) StudioEmerald else TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isHwAvailable) "Available: MediaCodec AVC" else "Available: Software",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isHwAvailable) StudioEmerald else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Diagnostic native runtime status banner
        Surface(
            color = StudioSurfaceElevated,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (nativeStatus is NativeRuntimeStatus.Ready) StudioEmerald.copy(alpha = 0.3f) else StudioRose.copy(alpha = 0.4f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (nativeStatus is NativeRuntimeStatus.Ready) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (nativeStatus is NativeRuntimeStatus.Ready) StudioEmerald else StudioRose,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val statusText = when (nativeStatus) {
                    is NativeRuntimeStatus.Ready -> {
                        val openClText = when (nativeStatus.openClProbeAvailable) {
                            true -> "OpenCL: READY"
                            false -> "OpenCL: UNAVAILABLE"
                            null -> if (nativeStatus.openClConfigured) {
                                "OpenCL: CONFIGURED / CHECKING"
                            } else {
                                "OpenCL: DEFAULT DISCOVERY"
                            }
                        }
                        "Native FFmpeg: READY • FFmpegKit ${nativeStatus.version} • ${nativeStatus.abi} • $openClText"
                    }
                    is NativeRuntimeStatus.Unavailable -> "Native FFmpeg: UNAVAILABLE (${nativeStatus.rootCause.take(60)})"
                }
                Text(
                    text = statusText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (nativeStatus is NativeRuntimeStatus.Ready) StudioEmerald else StudioRose
                )
            }
        }
    }
}
