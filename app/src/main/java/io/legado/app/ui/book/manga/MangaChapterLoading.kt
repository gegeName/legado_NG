package io.legado.app.ui.book.manga

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.legado.app.ui.design.components.compose.NgLoadingContent
import io.legado.app.ui.design.components.compose.NgLoadingLayout
import io.legado.app.ui.design.components.compose.NgLoadingStatus
import io.legado.app.ui.design.components.compose.NgLoadingSurface

/** Both continuous and paged readers render the same chapter-tail states. */
@Composable
internal fun MangaChapterLoading(
    ui: MangaReaderState,
    onRetry: () -> Unit,
    layout: NgLoadingLayout = NgLoadingLayout.INLINE,
) {
    if (!ui.tailLoading && !ui.tailRetry) {
        // Chapter boundaries and the end of the book are static, never indeterminate work.
        Text(ui.tailMessage, color = Color.White.copy(alpha = .64f), fontSize = 13.sp,
            textAlign = TextAlign.Center)
        return
    }
    NgLoadingContent(
        title = if (ui.tailLoading) "正在加载下一章" else "下一章暂时没能加载",
        subtitle = if (layout == NgLoadingLayout.CENTERED && ui.tailLoading) "正在获取章节内容" else null,
        status = if (ui.tailLoading) NgLoadingStatus.LOADING else NgLoadingStatus.ERROR,
        layout = layout,
        surface = NgLoadingSurface.MANGA,
        active = ui.active,
        onRetry = onRetry.takeIf { ui.tailRetry && !ui.tailLoading },
    )
}
