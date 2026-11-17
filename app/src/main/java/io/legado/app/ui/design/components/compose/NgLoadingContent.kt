package io.legado.app.ui.design.components.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.ui.design.theme.NgTheme

enum class NgLoadingLayout { CENTERED, INLINE }
enum class NgLoadingSurface { THEME, MANGA }
enum class NgLoadingStatus { LOADING, ERROR }

/** Shared book-page halo, including the compact next-chapter variant. No task or retry policy. */
@Composable
fun NgLoadingContent(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    layout: NgLoadingLayout = NgLoadingLayout.CENTERED,
    surface: NgLoadingSurface = NgLoadingSurface.THEME,
    status: NgLoadingStatus = NgLoadingStatus.LOADING,
    active: Boolean = true,
    onRetry: (() -> Unit)? = null,
) {
    val snapshot = NgTheme.snapshot
    val readerBackground = colorResource(R.color.book_ant_10)
    val background = if (surface == NgLoadingSurface.MANGA) readerBackground else Color(snapshot.colors.background)
    val foreground = if (surface == NgLoadingSurface.MANGA) {
        if (background.luminance() < .5f) Color.White else Color.Black
    } else Color(snapshot.colors.onSurface)
    val secondary = if (surface == NgLoadingSurface.MANGA) foreground.copy(alpha = .64f)
        else Color(snapshot.colors.onSurfaceVariant)
    val primary = Color(snapshot.colors.primary)
    val accent = remember(primary, background, foreground) {
        // Keep the selected theme hue visible on the manga's fixed dark canvas.
        var resolved = primary
        repeat(10) {
            val light = maxOf(resolved.luminance(), background.luminance())
            val dark = minOf(resolved.luminance(), background.luminance())
            if ((light + .05f) / (dark + .05f) < 3f) resolved = lerp(resolved, foreground, .18f)
        }
        resolved
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val animate = active && lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
        snapshot.motion.enabled && !snapshot.isEInk
    val fraction = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val inline = layout == NgLoadingLayout.INLINE

    @Composable
    fun Indicator() {
        NgBookLoadingIndicator(
            modifier = Modifier.size(if (inline) 28.dp else 80.dp),
            accent = accent,
            background = background,
            progress = fraction,
            status = status,
            animate = animate,
        )
    }

    @Composable
    fun Label() {
        Column(
            horizontalAlignment = if (inline) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (inline) 3.dp else 8.dp),
        ) {
            Text(title, color = foreground, fontSize = if (inline) 14.sp else 16.sp,
                fontWeight = FontWeight.Medium, textAlign = if (inline) TextAlign.Start else TextAlign.Center)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = secondary, fontSize = 12.sp,
                textAlign = if (inline) TextAlign.Start else TextAlign.Center)
        }
    }

    @Composable
    fun Retry() {
        if (status == NgLoadingStatus.ERROR && onRetry != null) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                    .background(accent.copy(alpha = if (snapshot.isEInk) 0f else .12f))
                    .clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text("重新加载", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }

    if (inline) {
        Row(modifier.widthIn(max = 480.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Indicator()
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f, fill = false)) { Label() }
            if (status == NgLoadingStatus.ERROR && onRetry != null) Spacer(Modifier.width(12.dp))
            Retry()
        }
    } else {
        Column(modifier.widthIn(max = 360.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Indicator()
            Spacer(Modifier.height(24.dp))
            Label()
            if (status == NgLoadingStatus.ERROR && onRetry != null) Spacer(Modifier.height(20.dp))
            Retry()
        }
    }
}

@Composable
private fun NgBookLoadingIndicator(
    modifier: Modifier,
    accent: Color,
    background: Color,
    progress: Float?,
    status: NgLoadingStatus,
    animate: Boolean,
) {
    val phase = if (animate && status == NgLoadingStatus.LOADING) {
        rememberInfiniteTransition(label = "book_loading").animateFloat(0f, 360f,
            infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "book_loading_rotation")
    } else null
    val displayedProgress = animateFloatAsState(progress ?: 0f,
        animationSpec = tween(if (animate) 180 else 0), label = "book_loading_progress")
    val painter = remember(accent, background) { NgBookLoadingPainter(accent.toArgb(), background.toArgb()) }
    Box(modifier.then(if (status == NgLoadingStatus.LOADING) Modifier.semantics {
        progressBarRangeInfo = progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
            ?: ProgressBarRangeInfo.Indeterminate
    } else Modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = (size.minDimension * .032f).coerceAtLeast(1.8.dp.toPx())
            // Native Canvas stays inside Compose's draw pass, including captured manga pages.
            // Animation values are read here so a frame never triggers layout/recomposition.
            drawIntoCanvas {
                painter.draw(it.nativeCanvas, size.width, size.height, stroke,
                    if (progress == null) null else displayedProgress.value,
                    phase?.value ?: 0f, status == NgLoadingStatus.ERROR)
            }
        }
    }
}
