package io.legado.app.ui.book.manga

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

internal enum class MangaTranslationStatus {
    TRANSLATING, LOADING, PARTIAL, FAILED,
}

/** Compact overlay with its own contrast, independent of light/dark manga artwork. */
@Composable
internal fun MangaTranslationStatusChip(
    status: MangaTranslationStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = status == MangaTranslationStatus.TRANSLATING || status == MangaTranslationStatus.LOADING
    val accent = if (busy) Color(0xFFA0E4D3) else Color(0xFFF1D49C)
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (busy) Modifier else Modifier.clickable(role = Role.Button, onClick = onRetry))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = shape,
        color = Color(0xE6212B30),
        contentColor = Color(0xFFF5F8F7),
        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Row(
            modifier = Modifier
                .animateContentSize(tween(180))
                .heightIn(min = if (busy) 32.dp else 40.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.18f),
                    strokeWidth = 1.5.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh_black_24dp),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = when (status) {
                    MangaTranslationStatus.TRANSLATING -> "翻译中"
                    MangaTranslationStatus.LOADING -> "译图加载中"
                    MangaTranslationStatus.PARTIAL -> "补译"
                    MangaTranslationStatus.FAILED -> "重试"
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
