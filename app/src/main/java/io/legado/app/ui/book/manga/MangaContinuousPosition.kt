package io.legado.app.ui.book.manga

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import io.legado.app.integration.manga.NgMangaLayout
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** Keep existing indices during a drag/fling when a late previous chapter arrives. */
@Composable
internal fun MangaDeferPrepend(
    list: LazyListState,
    pages: List<BaseMangaPage>,
    touching: Boolean,
): List<BaseMangaPage> {
    val content = remember(list) { MangaMeasuredContent(pages) }
    val previous = content.pages
    val display = if (previous !== pages && (touching || list.isScrollInProgress)) {
        val firstKey = previous.firstOrNull()?.let(::mangaPageKey)
        remember(pages, firstKey) {
            val start = if (firstKey == null) -1 else pages.indexOfFirst { mangaPageKey(it) == firstKey }
            if (start > 0) pages.subList(start, pages.size) else pages
        }
    } else pages
    SideEffect { content.pages = display }
    return display
}

/** Rebase before measurement: LazyList's nearby key map cannot cover whole chapter replacements. */
@Composable
internal fun MangaMaintainScrollPosition(
    list: LazyListState,
    pages: List<BaseMangaPage>,
    relocating: Boolean,
): Boolean {
    val content = remember(list) { MangaMeasuredContent(pages) }
    var waitingForLayout by remember(list) { mutableStateOf(false) }
    val request = if (content.pages !== pages && !relocating) {
        val visible = list.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull { item -> pages.any { mangaPageKey(it) == item.key } }
            ?: visible.firstOrNull()
        if (first != null) {
            val index = pages.indexOfFirst { mangaPageKey(it) == first.key }
            // A removed tail becomes the first newly appended item.
            val target = if (index >= 0) index else if (first.key == mangaTailKey(content.pages)) {
                content.pages.lastOrNull()?.let { last ->
                    pages.indexOfFirst { mangaPageKey(it) == mangaPageKey(last) }
                        .takeIf { it >= 0 }?.plus(1)
                }
            } else null
            if (target != null && target != visible.first().index) {
                MangaScrollRequest(target, -first.offset, first.key, first.index)
            } else null
        } else null
    } else null
    SideEffect {
        if (request != null) {
            waitingForLayout = true
            MangaScrollDiagnostics.event("REBASE") {
                "key=${request.key} from=${request.from} to=${request.index} offset=${request.offset}"
            }
            list.requestScrollToItem(request.index, request.offset)
        }
        if (relocating) waitingForLayout = false
        content.pages = pages
    }
    LaunchedEffect(pages, waitingForLayout) {
        if (waitingForLayout) {
            snapshotFlow {
                val info = list.layoutInfo
                info.totalItemsCount == pages.size + 1 && info.visibleItemsInfo.all { item ->
                    if (item.index == pages.size) item.key == mangaTailKey(pages)
                    else NgMangaLayout.measuredPage(pages, item.index, item.key) != null
                }
            }.first { it }
            waitingForLayout = false
        }
    }
    return waitingForLayout || request != null
}

private class MangaMeasuredContent(var pages: List<BaseMangaPage>)
private data class MangaScrollRequest(val index: Int, val offset: Int, val key: Any, val from: Int)

/** A restored page is an identity; its list index can change while waiting for a frame. */
@Composable
internal fun MangaContinuousPosition(
    ui: MangaReaderState,
    list: LazyListState,
    pages: List<BaseMangaPage>,
    onPage: (BaseMangaPage) -> Unit,
): Boolean {
    val display by rememberUpdatedState(pages)
    val latestPage by rememberUpdatedState(onPage)
    var appliedJump by remember { mutableIntStateOf(0) }
    val relocating = ui.loading || appliedJump != ui.jumpRevision
    val rebasing = MangaMaintainScrollPosition(list, pages, relocating)
    LaunchedEffect(ui.jumpRevision) {
        if (ui.jumpRevision <= 0) return@LaunchedEffect
        val target = ui.jumpPage ?: return@LaunchedEffect
        val revision = ui.jumpRevision
        val key = mangaPageKey(target)
        while (true) {
            withFrameNanos { }
            val index = snapshotFlow {
                display.indexOfFirst { item ->
                    mangaPageKey(item) == key || NgMangaLayout.realPages(item).any { mangaPageKey(it) == key }
                }
            }.first { it >= 0 }
            MangaScrollDiagnostics.event("JUMP") { "revision=$revision key=$key index=$index" }
            try {
                list.scrollToItem(index)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                continue
            }
            withFrameNanos { }
            // A chapter may have arrived between scrollToItem and this measured frame.
            if (list.layoutInfo.visibleItemsInfo.any { visible ->
                    NgMangaLayout.measuredPage(display, visible.index, visible.key)?.let { item ->
                        mangaPageKey(item) == key || NgMangaLayout.realPages(item).any { mangaPageKey(it) == key }
                    } == true
                }) break
        }
        latestPage(target)
        appliedJump = revision
    }
    return relocating || rebasing
}
