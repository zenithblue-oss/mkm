package com.ivarna.mkm.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.mkm.R
import com.ivarna.mkm.data.model.FpsSample
import com.ivarna.mkm.data.model.FpsSession
import com.ivarna.mkm.data.model.FpsSource

@Composable
fun FpsRecordGraph(
    session: FpsSession,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    // The series may be downsampled on long sessions; summary numbers come from the exact aggregate
    val allSamples = session.samples
    val stats = session.stats

    val currentSample = allSamples.lastOrNull()
    val currentFps = if (currentSample != null && !currentSample.idle) currentSample.fps else 0f
    val avgFps = stats.avgFps
    val minFps = stats.minFps
    val maxFps = stats.maxFps

    val latestPkg = session.targetPkg ?: currentSample?.pkg ?: "—"
    val latestPid = currentSample?.pid ?: 0
    val latestSource = when (currentSample?.source) {
        FpsSource.ADRENO_INFLIGHT -> stringResource(R.string.fps_source_adreno)
        FpsSource.MALI_DMA_FENCE -> stringResource(R.string.fps_source_mali)
        FpsSource.SURFACEFLINGER -> stringResource(R.string.fps_source_sf)
        FpsSource.GFXINFO -> stringResource(R.string.fps_source_gfxinfo)
        FpsSource.FPS_MONITOR -> stringResource(R.string.fps_source_estimate)
        null -> "—"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Status indicator, Title, Target App
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(5.dp))
                        )
                    }
                    Text(
                        text = if (isRecording) stringResource(R.string.recording_in_progress)
                               else stringResource(R.string.fps_session_summary),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = latestSource,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Target app info
            if (latestPkg.isNotBlank() && latestPkg != "—") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (latestPid > 0) "$latestPkg ($latestPid)" else latestPkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // Metric Summary Row: Current, Avg, Min, Max, Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricPill(
                    label = stringResource(R.string.current_fps),
                    value = if (currentFps > 0f) String.format("%.1f", currentFps) else "—",
                    unit = "FPS",
                    color = MaterialTheme.colorScheme.primary
                )
                MetricPill(
                    label = stringResource(R.string.avg_fps),
                    value = if (avgFps > 0f) String.format("%.1f", avgFps) else "—",
                    unit = "FPS",
                    color = MaterialTheme.colorScheme.secondary
                )
                MetricPill(
                    label = stringResource(R.string.min_fps),
                    value = if (minFps > 0f) String.format("%.1f", minFps) else "—",
                    unit = "FPS",
                    color = MaterialTheme.colorScheme.error
                )
                MetricPill(
                    label = stringResource(R.string.max_fps),
                    value = if (maxFps > 0f) String.format("%.1f", maxFps) else "—",
                    unit = "FPS",
                    color = Color(0xFF4CAF50)
                )
                MetricPill(
                    label = stringResource(R.string.samples_count),
                    value = "${stats.activeCount}/${stats.sampleCount}",
                    unit = "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Frame-time stats (only when the source exposes per-frame timestamps)
            if (stats.frameCount > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricPill(
                        label = stringResource(R.string.fps_low_1),
                        value = String.format("%.1f", stats.low1Fps),
                        unit = "FPS",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    MetricPill(
                        label = stringResource(R.string.fps_low_01),
                        value = String.format("%.1f", stats.low01Fps),
                        unit = "FPS",
                        color = MaterialTheme.colorScheme.error
                    )
                    MetricPill(
                        label = stringResource(R.string.fps_jank),
                        value = stats.jankCount.toString(),
                        unit = "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MetricPill(
                        label = stringResource(R.string.fps_stutter),
                        value = String.format("%.1f", stats.stutterPct),
                        unit = "%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // FPS Graph Canvas
            val primaryColor = MaterialTheme.colorScheme.primary
            val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            val idleColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (allSamples.size < 2) return@Canvas

                    val maxY = maxOf(maxFps * 1.15f, 60f)
                    val width = size.width
                    val height = size.height

                    // Draw horizontal grid lines (30, 60, 90, 120 FPS as applicable)
                    val gridSteps = listOf(30f, 60f, 90f, 120f, 144f).filter { it < maxY }
                    for (step in gridSteps) {
                        val y = height - (step / maxY * height)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        )
                    }

                    // Plot non-idle samples
                    val stepX = width / (allSamples.size - 1).coerceAtLeast(1)
                    val linePath = Path()
                    var pathStarted = false

                    allSamples.forEachIndexed { index, sample ->
                        val x = index * stepX
                        if (sample.idle || sample.fps <= 0f) {
                            // Draw idle indicator at bottom
                            drawCircle(
                                color = idleColor,
                                radius = 2.dp.toPx(),
                                center = Offset(x, height - 2.dp.toPx())
                            )
                        } else {
                            val y = (height - (sample.fps / maxY * height)).coerceIn(0f, height)
                            if (!pathStarted) {
                                linePath.moveTo(x, y)
                                pathStarted = true
                            } else {
                                linePath.lineTo(x, y)
                            }
                            // Draw data point
                            drawCircle(
                                color = primaryColor,
                                radius = 2.5.dp.toPx(),
                                center = Offset(x, y)
                            )
                        }
                    }

                    if (pathStarted) {
                        drawPath(
                            path = linePath,
                            color = primaryColor,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            if (allSamples.size >= 2) {
                // Axis labels are offsets from the session start, not from the first plotted point
                val startSec = ((allSamples.first().tMs - session.startedAtMs) / 1000f).coerceAtLeast(0f)
                val endSec = ((allSamples.last().tMs - session.startedAtMs) / 1000f).coerceAtLeast(startSec)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatElapsed(startSec),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatElapsed(endSec),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // Frame-time graph: average (line) and worst (dots) frame per sample
            val frameSamples = allSamples.filter { !it.idle && it.frameMs > 0f }
            if (frameSamples.size >= 2) {
                Text(
                    text = stringResource(R.string.fps_frame_time_ms),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val ftColor = MaterialTheme.colorScheme.tertiary
                val worstColor = MaterialTheme.colorScheme.error
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val maxMs = maxOf(frameSamples.maxOf { maxOf(it.frameMs, it.maxFrameMs) } * 1.1f, 20f)
                        val stepX = size.width / (frameSamples.size - 1)
                        fun yOf(ms: Float) = (size.height - ms / maxMs * size.height).coerceIn(0f, size.height)
                        listOf(1000f / 60f, 1000f / 30f).filter { it < maxMs }.forEach { ms ->
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, yOf(ms)),
                                end = Offset(size.width, yOf(ms)),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )
                        }
                        val path = Path()
                        frameSamples.forEachIndexed { i, s ->
                            val x = i * stepX
                            if (i == 0) path.moveTo(x, yOf(s.frameMs)) else path.lineTo(x, yOf(s.frameMs))
                            if (s.maxFrameMs > 0f) drawCircle(worstColor, 2.dp.toPx(), Offset(x, yOf(s.maxFrameMs)))
                        }
                        drawPath(path, ftColor, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }
    }
}

private fun formatElapsed(sec: Float): String {
    val s = sec.toInt()
    return if (s >= 60) String.format("%d:%02d", s / 60, s % 60) else "${s}s"
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            if (unit.isNotBlank()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
