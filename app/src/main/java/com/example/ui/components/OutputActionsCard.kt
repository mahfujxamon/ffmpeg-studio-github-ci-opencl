package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.StudioSurfaceCard
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun OutputActionsCard(
    outputPath: String,
    publicDisplayPath: String,
    durationMs: Long,
    fileSizeBytes: Long,
    encoderUsed: String,
    onOpenOutput: () -> Unit,
    onShareOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizeMb = fileSizeBytes.toDouble() / (1024.0 * 1024.0)
    val formattedSize = if (sizeMb >= 1.0) {
        String.format(Locale.US, "%.1f MB", sizeMb)
    } else {
        String.format(Locale.US, "%.0f KB", fileSizeBytes.toDouble() / 1024.0)
    }

    val totalSec = durationMs / 1000
    val formattedDuration = String.format(Locale.US, "%.1fs", totalSec.toFloat())

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("output_actions_card"),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceCard),
        shape = RoundedCornerShape(20.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(StudioEmerald.copy(alpha = 0.6f), StudioCyan.copy(alpha = 0.6f))
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(StudioEmerald.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = StudioEmerald,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OUTPUT READY",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = StudioEmerald
                    )
                    Text(
                        text = outputPath.substringAfterLast('/'),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Specs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StudioSurfaceElevated, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Size: $formattedSize", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(text = "Duration: $formattedDuration", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(text = "Encoder: $encoderUsed", fontSize = 11.sp, color = StudioCyan, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Gallery / Public storage location banner
            Surface(
                color = StudioSurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = StudioCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Saved to: $publicDisplayPath",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actions: Open and Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenOutput,
                    modifier = Modifier.weight(1f).testTag("open_output_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = StudioCyan, contentColor = Color(0xFF03101E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PLAY", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onShareOutput,
                    modifier = Modifier.weight(1f).testTag("share_output_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SHARE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
