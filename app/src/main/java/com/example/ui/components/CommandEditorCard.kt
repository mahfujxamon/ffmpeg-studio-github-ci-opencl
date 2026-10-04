package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurfaceCard
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioViolet
import com.example.ui.theme.TerminalBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun CommandEditorCard(
    command: String,
    onCommandChange: (String) -> Unit,
    onResetCommand: () -> Unit,
    onCopyCommand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("command_editor_card"),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceCard),
        shape = RoundedCornerShape(20.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(StudioBorder, StudioBorder.copy(alpha = 0.4f))
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = StudioCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FFMPEG COMMAND EDITOR",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StudioCyan,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onResetCommand,
                        modifier = Modifier.size(28.dp).testTag("reset_command_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = "Reset Template",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onCopyCommand,
                        modifier = Modifier.size(28.dp).testTag("copy_command_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Command",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Enter any valid FFmpeg command. Use {input} and {output} macros for automatic path injection.",
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Multi-line Command Input Field
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .testTag("ffmpeg_command_input"),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = TextPrimary
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioCyan,
                    unfocusedBorderColor = StudioBorder,
                    focusedContainerColor = TerminalBg,
                    unfocusedContainerColor = TerminalBg
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Insertion Macro Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QuickInsertChip("{input}") {
                    onCommandChange(insertToken(command, "{input}"))
                }
                QuickInsertChip("{output}") {
                    onCommandChange(insertToken(command, "{output}"))
                }
                QuickInsertChip("-vf hflip") {
                    onCommandChange(insertToken(command, "-vf hflip"))
                }
                QuickInsertChip("-c:v libx264") {
                    onCommandChange(insertToken(command, "-c:v libx264"))
                }
                QuickInsertChip("-preset ultrafast") {
                    onCommandChange(insertToken(command, "-preset ultrafast"))
                }
                QuickInsertChip("-c:v h264_mediacodec") {
                    onCommandChange(insertToken(command, "-c:v h264_mediacodec"))
                }
                QuickInsertChip("-c:a aac") {
                    onCommandChange(insertToken(command, "-c:a aac"))
                }
                QuickInsertChip("-b:v 4M") {
                    onCommandChange(insertToken(command, "-b:v 4M"))
                }
                QuickInsertChip("-b:v 2M") {
                    onCommandChange(insertToken(command, "-b:v 2M"))
                }
                QuickInsertChip("-an") {
                    onCommandChange(insertToken(command, "-an"))
                }
            }
        }
    }
}

@Composable
private fun QuickInsertChip(token: String, onInsert: () -> Unit) {
    Surface(
        color = StudioSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .clickable { onInsert() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "+ $token",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = StudioCyan
            )
        }
    }
}

private fun insertToken(current: String, token: String): String {
    return if (current.endsWith(" ")) {
        "$current$token "
    } else {
        "$current $token "
    }
}
