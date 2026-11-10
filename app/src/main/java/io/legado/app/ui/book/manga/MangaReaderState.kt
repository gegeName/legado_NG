package io.legado.app.ui.book.manga

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.help.config.AppConfig
import io.legado.app.integration.manga.NgMangaReaderBridge
import io.legado.app.integration.manga.NgMangaTranslationBridge
import io.legado.app.integration.manga.NgMangaLayout
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaReadingStyle
import io.legado.app.ui.book.manga.config.mangaScrollIntervals
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.read.ReadCatalogStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

internal enum class MangaPanel { NONE, MENU, READING, DISPLAY, SETTINGS, FILTER, FOOTER }
internal enum class MangaAutoMode { OFF, PAGE, SCROLL }
internal enum class MangaColorMode { ORIGINAL, GRAY, INK }
internal enum class MangaTap { NONE, MENU, PREVIOUS, NEXT }
internal enum class MangaAction {
    BACK, INFO, CHANGE_SOURCE, REFRESH, CATALOG, PREVIOUS_CHAPTER, NEXT_CHAPTER,
    OPEN_WEB, WEB_MODE, RETRY, TAIL_RETRY, RESET_READING, TRANSLATION_SETTINGS,
    SOURCE_LOGIN, SOURCE_EDIT, SOURCE_DISABLE, SOURCE_ENABLE, CHAPTER_PAY,
}
internal enum class MangaSetting {
    MODE, LAYOUT, DIRECTION, EFFECT, BOOK_STYLE, AUTO, INTERVAL, SPEED, SCALE, CLICK, PRELOAD, HINTS,
    CHAPTER_AHEAD, CHAPTER_BEHIND, IMAGE_CHAPTERS, DOUBLE_PAGE, PDF_AHEAD,
    COLOR, THRESHOLD, BRIGHTNESS, RED, GREEN, BLUE, ALPHA,
    FOOTER, FOOTER_LEFT, FOOTER_MIDDLE, FOOTER_RIGHT, CATALOG_STYLE,
}

/** 只持有渲染/手势暂态；章节、缓存和存储进度仍归 ReadManga。 */
@Stable
internal class MangaReaderState {
    var pages by mutableStateOf<List<BaseMangaPage>>(emptyList())
    var bookName by mutableStateOf("")
    var sourceName by mutableStateOf("")
    var sourceOrigin by mutableStateOf("")
    var sourceAvailable by mutableStateOf(false)
    var sourceHasLogin by mutableStateOf(false)
    var sourceEnabled by mutableStateOf(true)
    var chapterCanPay by mutableStateOf(false)
    var bookUrl by mutableStateOf("")
    var chapterUrl by mutableStateOf("")
    var chapterName by mutableStateOf("")
    var currentPage by mutableStateOf<MangaPage?>(null)
    var chapterIndex by mutableIntStateOf(0)
    var chapterCount by mutableIntStateOf(0)
    var imageIndex by mutableIntStateOf(0)
    var imageCount by mutableIntStateOf(0)
    var panel by mutableStateOf(MangaPanel.NONE)
    var catalogStyle by mutableStateOf(ReadCatalogStyle.CARD)
    var menuVisible by mutableStateOf(false)
    var reading by mutableStateOf(MangaReadingStyle())
    var bookStyle by mutableStateOf(false)
    val horizontal: Boolean get() = reading.horizontal
    val rtl: Boolean get() = reading.rtl
    val paged: Boolean get() = reading.paged
    var clickTurn by mutableStateOf(!AppConfig.disableClickScroll)
    var scaleEnabled by mutableStateOf(!AppConfig.disableMangaScale)
    var autoMode by mutableStateOf(MangaAutoMode.OFF)
    var autoInterval by mutableIntStateOf(AppConfig.mangaAutoPageSpeed.coerceIn(1, 9999))
    var autoScrollLevel by mutableIntStateOf(4)
    val autoScrollInterval: Int get() = mangaScrollIntervals[autoScrollLevel]
    var active by mutableStateOf(false)
    var touching by mutableStateOf(false)
    var touchUpRevision by mutableIntStateOf(0)
    var externalModal by mutableStateOf(false)
    var preload by mutableIntStateOf(AppConfig.mangaPreDownloadNum.coerceAtLeast(0))
    var chapterAhead by mutableIntStateOf(NgMangaReaderBridge.preferences.chapterAhead)
    var chapterBehind by mutableIntStateOf(NgMangaReaderBridge.preferences.chapterBehind)
    var imageChapters by mutableIntStateOf(NgMangaReaderBridge.preferences.imageChapters)
    var doublePage by mutableStateOf(NgMangaReaderBridge.preferences.doublePage)
    var pdfAhead by mutableIntStateOf(io.legado.app.integration.manga.NgMangaPdfBridge.ahead())
    var translationEnabled by mutableStateOf(NgMangaTranslationBridge.configuration.enabled)
    var translationRevision by mutableIntStateOf(0)
    var chapterHints by mutableStateOf(!AppConfig.hideMangaTitle)
    var colorMode by mutableStateOf(when {
        AppConfig.enableMangaEInk -> MangaColorMode.INK
        AppConfig.enableMangaGray -> MangaColorMode.GRAY
        else -> MangaColorMode.ORIGINAL
    })
    var threshold by mutableIntStateOf(AppConfig.mangaEInkThreshold.coerceIn(0, 255))
    var filter by mutableStateOf(GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter)
        .getOrNull() ?: MangaColorFilterConfig())
    var footer by mutableStateOf(GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig)
        .getOrNull() ?: MangaFooterConfig())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var retryAllowed by mutableStateOf(true)
    var tailMessage by mutableStateOf("加载中")
    var tailLoading by mutableStateOf(true)
    var tailRetry by mutableStateOf(false)
    var preserveScrollOnUpdate by mutableStateOf(false)
    var jumpIndex by mutableIntStateOf(0)
    var jumpRevision by mutableIntStateOf(0)
    var turnDirection by mutableIntStateOf(1)
    var turnRevision by mutableIntStateOf(0)
    var appliedTurnRevision by mutableIntStateOf(0)
    var scale by mutableFloatStateOf(1f)
    private val progressAnchor = io.legado.features.adapter.MangaReaderFeatures.Anchor()

    val autoRunning: Boolean
        get() = active && !loading && panel == MangaPanel.NONE && !menuVisible && !externalModal &&
            !touching && scale == 1f

    fun jumpTo(index: Int) {
        jumpIndex = index; jumpRevision++
        (pages.getOrNull(index) as? MangaPage)?.let {
            progressAnchor.begin(NgMangaLayout.neutral(it).key, jumpRevision.toLong(), android.os.SystemClock.elapsedRealtime())
        }
    }
    fun acceptProgress(page: BaseMangaPage): Boolean = (page as? MangaPage)?.let {
        progressAnchor.accept(NgMangaLayout.neutral(it).key, jumpRevision.toLong(), android.os.SystemClock.elapsedRealtime())
    } ?: true
    fun turn(direction: Int) { turnDirection = direction; turnRevision++ }
    fun claimTurn(): Int? {
        if (turnRevision <= appliedTurnRevision) return null
        appliedTurnRevision = turnRevision
        return turnDirection.takeIf { active && !loading }
    }
}

internal fun mangaPageKey(page: BaseMangaPage): String = "${page.chapterIndex}:${page.index}"
internal fun mangaTailKey(pages: List<BaseMangaPage>): String = "load-more:${pages.lastOrNull()?.let(::mangaPageKey)}"
internal fun mangaTailPage(pages: List<BaseMangaPage>): BaseMangaPage? {
    val last = pages.lastOrNull() ?: return null
    return pages.asReversed().asSequence().takeWhile { it.chapterIndex == last.chapterIndex }
        .flatMap { NgMangaLayout.realPages(it).asReversed().asSequence() }.firstOrNull() ?: last
}

internal fun mangaTapAction(x: Float, y: Float, width: Float, height: Float,
    clickTurn: Boolean, rtl: Boolean): MangaTap {
    if (width <= 0 || height <= 0) return MangaTap.NONE
    val nx = x / width
    val ny = y / height
    if (nx in 0.33f..0.66f && ny in 0.33f..0.66f) return MangaTap.MENU
    if (!clickTurn || ny < 0.66f) return MangaTap.NONE
    return when {
        nx < 0.33f -> if (rtl) MangaTap.NEXT else MangaTap.PREVIOUS
        nx > 0.66f -> if (rtl) MangaTap.PREVIOUS else MangaTap.NEXT
        else -> MangaTap.NONE
    }
}
