package io.legado.app.ui.book.manga
import io.legado.app.help.book.isPdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.R
import io.legado.app.model.BookCover
import io.legado.app.model.ReadManga
import io.legado.app.integration.manga.NgMangaReaderBridge
import io.legado.app.integration.manga.NgMangaTranslationBridge
import io.legado.app.integration.manga.rememberNgMangaTranslation
import io.legado.app.integration.manga.NgMangaLayout
import io.legado.app.integration.manga.NgMangaSpread
import io.legado.app.integration.manga.NgMangaSpreadImage
import io.legado.app.integration.manga.NgMangaPdfBridge
import io.legado.app.integration.manga.rememberNgPdfPreview
import io.legado.features.api.manga.TranslationPageStatus
import io.legado.features.api.manga.DisplayItem
import io.legado.features.adapter.MangaReaderFeatures
import io.legado.app.integration.image.NgOriginalImageResources
import io.legado.features.api.host.ResourcePriority
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.EpaperTransformation
import io.legado.app.ui.book.manga.entities.GrayscaleTransformation
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun MangaImages(ui: MangaReaderState, onPage: (BaseMangaPage) -> Unit,
    onMenu: () -> Unit, onRetryTail: () -> Unit) {
    LaunchedEffect(ui.bookUrl, ui.currentPage?.mImageUrl, ui.active) {
        val book = ReadManga.book?.takeIf { it.bookUrl == ui.bookUrl && it.isPdf }?.copy()
        val href = ui.currentPage?.mImageUrl
        if (book != null && href != null && ui.active) {
            try { NgMangaPdfBridge.window(book, href) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Visible renderer reports invalid PDF pages. */ }
        }
    }
    LaunchedEffect(ui.bookUrl, ui.sourceOrigin, ui.pages, ui.active, ui.translationEnabled, ui.translationRevision) {
        val book = ReadManga.book?.takeIf { it.bookUrl == ui.bookUrl && it.origin == ui.sourceOrigin }?.copy()
        if (book == null || !ui.active || !ui.translationEnabled) { NgMangaTranslationBridge.stopBackground(); return@LaunchedEffect }
        var previous = ui.currentPage?.let { ui.pages.indexOf(it) } ?: -1
        try {
            snapshotFlow { ui.currentPage }.collect { page ->
                val position = page?.let { ui.pages.indexOf(it) } ?: -1
                if (position >= 0) {
                    NgMangaTranslationBridge.updateBackground(book, ui.pages, position, position >= previous, ui.translationRevision)
                    previous = position
                }
            }
        } finally { NgMangaTranslationBridge.stopBackground() }
    }
    key(ui.paged, ui.horizontal, ui.rtl, ui.doublePage) {
        if (ui.paged) MangaPagedImages(ui, onPage, onMenu, onRetryTail)
        else MangaContinuousImages(ui, onPage, onMenu, onRetryTail)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun MangaContinuousImages(ui: MangaReaderState, onPage: (BaseMangaPage) -> Unit,
    onMenu: () -> Unit, onRetryTail: () -> Unit) {
    val displayPages by rememberUpdatedState(remember(ui.pages, ui.horizontal, ui.doublePage, ui.rtl) {
        NgMangaLayout.display(ui.pages, ui.horizontal && ui.doublePage, ui.rtl)
    })
    val prefetchItems = remember(displayPages) { NgMangaLayout.prefetchItems(displayPages) }
    val displayJump = NgMangaLayout.slot(displayPages, ui.pages.getOrNull(ui.jumpIndex), ui.jumpIndex)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val latestPage by rememberUpdatedState(onPage)
    val flipController = remember(ui, list) {
        MangaFlipController(
            isLoading = { ui.loading },
            currentChapter = { ReadManga.durChapterIndex },
            renderedChapter = { ui.chapterIndex },
            currentPage = { ui.currentPage },
            moveChapter = { direction ->
                if (direction > 0) ReadManga.moveToNextChapter(preservePosition = true)
                else ReadManga.moveToPrevChapter(preservePosition = true)
            },
            updatePage = { latestPage(it) },
            preloadNextChapter = ReadManga::preloadNextChapter,
        )
    }
    val latestMenu by rememberUpdatedState(onMenu)
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pendingJump by remember { mutableStateOf(ui.jumpRevision > 0) }
    var appliedJump by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val imageRatios = remember(ui.bookUrl, ui.sourceOrigin) { MangaImageRatioCache() }
    val imageTransformation = remember(ui.colorMode, ui.threshold) {
        when (ui.colorMode) {
            MangaColorMode.INK -> EpaperTransformation(ui.threshold)
            MangaColorMode.GRAY -> GrayscaleTransformation()
            else -> null
        }
    }
    val preloader = remember(context, ui.bookUrl, ui.sourceOrigin) {
        MangaImagePreloader(context, imageRatios, scope, ui.sourceOrigin)
    }
    DisposableEffect(preloader) { onDispose { preloader.clear() } }
    LaunchedEffect(ui.bookUrl, ui.sourceOrigin, ui.jumpRevision) { flipController.reset() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val normalFling = ScrollableDefaults.flingBehavior()

        LaunchedEffect(list) {
            if (!MangaScrollDiagnostics.enabled) return@LaunchedEffect
            snapshotFlow {
                val info = list.layoutInfo
                "first=${list.firstVisibleItemIndex} offset=${list.firstVisibleItemScrollOffset} " +
                    "scrolling=${list.isScrollInProgress} touch=${ui.touching} scale=${ui.scale} pan=$pan " +
                    "jump=${ui.jumpRevision} applied=$appliedJump pending=$pendingJump " +
                    "viewport=${info.viewportStartOffset}:${info.viewportEndOffset} total=${info.totalItemsCount} " +
                    "items=${info.visibleItemsInfo.joinToString(";") { "${it.key},${it.offset},${it.size}" }}"
            }.collect { details -> MangaScrollDiagnostics.event("LAYOUT") { details } }
        }

        LaunchedEffect(ui.scaleEnabled) {
            if (!ui.scaleEnabled) { ui.scale = 1f; pan = Offset.Zero }
        }
        LaunchedEffect(ui.jumpRevision) {
            if (ui.jumpRevision > 0 && displayPages.isNotEmpty()) {
                val revision = ui.jumpRevision
                pendingJump = true
                try {
                    MangaScrollDiagnostics.event("JUMP") { "revision=$revision index=${displayJump}" }
                    withFrameNanos { }
                    list.scrollToItem(displayJump.coerceIn(displayPages.indices))
                    withFrameNanos { }
                    ui.pages.getOrNull(ui.jumpIndex)?.let(latestPage)
                } finally { if (ui.jumpRevision == revision) { appliedJump = revision; pendingJump = false } }
            }
        }
        LaunchedEffect(ui.chapterIndex, ui.loading, ui.tailRetry, ui.error) {
            if (ui.tailRetry || ui.error != null) flipController.onLoadFail()
        }
        suspend fun turn(direction: Int) {
            val distance = (if (ui.horizontal) width else height / ui.scale.coerceAtMost(1f)) * direction
            list.animateScrollBy(distance)
        }
        LaunchedEffect(ui.turnRevision) {
            ui.claimTurn()?.takeIf { displayPages.isNotEmpty() }?.let { turn(it) }
        }
        LaunchedEffect(ui.autoRunning, ui.autoMode, ui.autoScrollInterval) {
            if (!ui.autoRunning) return@LaunchedEffect
            when (ui.autoMode) {
                MangaAutoMode.SCROLL -> {
                    var previous = withFrameNanos { it }
                    while (isActive) {
                        if (!list.canScrollForward || list.isScrollInProgress) {
                            snapshotFlow { list.canScrollForward && !list.isScrollInProgress }.first { it }
                            previous = withFrameNanos { it }
                        }
                        val now = withFrameNanos { it }
                        if (!ui.autoRunning) break
                        val viewport = if (ui.horizontal) width else height
                        val distance = ((now - previous) / 1_000_000_000f).coerceAtMost(0.064f) * viewport / ui.autoScrollInterval
                        previous = now
                        if (!list.isScrollInProgress && list.canScrollForward) {
                            try { list.scrollBy(distance) } catch (_: CancellationException) {
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        LaunchedEffect(displayPages, ui.horizontal) {
            snapshotFlow {
                val center = if (ui.horizontal) width / 2 - pan.x / ui.scale
                    else (height / 2 - pan.y) / ui.scale
                val info = list.layoutInfo
                val layoutMatches = info.totalItemsCount == displayPages.size + 1 &&
                    info.visibleItemsInfo.all { visible ->
                        if (visible.index == displayPages.size) visible.key == mangaTailKey(displayPages)
                        else NgMangaLayout.measuredPage(displayPages, visible.index, visible.key) != null
                    }
                if (pendingJump || appliedJump != ui.jumpRevision || !layoutMatches) null else {
                    val measured = info.visibleItemsInfo.mapNotNull { visible ->
                        NgMangaLayout.measuredPage(displayPages, visible.index, visible.key)?.let { page ->
                            MangaReaderFeatures.VisibleItem(mangaPageKey(page), page.chapterIndex, visible.offset, visible.size)
                        }
                    }
                    val key = MangaReaderFeatures.visiblePage(ui.chapterIndex, measured,
                        info.viewportStartOffset, info.viewportEndOffset, center)
                    val selected = info.visibleItemsInfo.firstOrNull { it.key == key }?.let {
                        NgMangaLayout.measuredPage(displayPages, it.index, it.key)
                    }
                    val currentVisible = measured.any { it.chapter == ui.chapterIndex &&
                        it.offset < info.viewportEndOffset && it.offset.toLong() + it.size > info.viewportStartOffset }
                    selected to currentVisible
                }
            }.distinctUntilChanged().collect { item ->
                item?.let { (centered, currentVisible) ->
                    flipController.onVisiblePage(centered, currentChapterVisible = currentVisible)
                }
            }
        }
        LaunchedEffect(displayPages, ui.chapterIndex, ui.horizontal) {
            snapshotFlow {
                val info = list.layoutInfo
                val threshold = (if (ui.horizontal) width else height) / 2
                info.visibleItemsInfo.any { visible ->
                    val page = NgMangaLayout.measuredPage(displayPages, visible.index, visible.key)
                    page?.chapterIndex == ui.chapterIndex &&
                        visible.offset + visible.size >= info.viewportEndOffset - threshold
                }
            }.distinctUntilChanged().collect { nearChapterEnd ->
                flipController.onNearChapterEnd(nearChapterEnd)
            }
        }
        LaunchedEffect(displayPages, ui.active, imageTransformation) {
            if (!ui.active) { preloader.clear(); return@LaunchedEffect }
            var previous = list.firstVisibleItemIndex
            var forward = true
            launch {
                NgOriginalImageResources.imagesReady.collect { image ->
                    if (image.bookId == ui.bookUrl) preloader.update(
                        displayPages, prefetchItems, list.firstVisibleItemIndex,
                        MangaImagePreloader.IMAGE_LOOKAHEAD_COUNT, forward, transformation = imageTransformation,
                    )
                }
            }
            snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { position ->
                forward = position >= previous
                preloader.update(
                    displayPages, prefetchItems, position, MangaImagePreloader.IMAGE_LOOKAHEAD_COUNT, forward,
                    transformation = imageTransformation,
                )
                previous = position
            }
        }

        fun bounded(offset: Offset, scale: Float): Offset {
            val x = width * (scale - 1f).coerceAtLeast(0f) / 2
            val y = height * (scale - 1f).coerceAtLeast(0f)
            return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, 0f))
        }
        // Preserve Android's pinch / double-tap-and-drag recognition without hosting an old View.
        val scaleDetector = remember(context, ui.scaleEnabled, width, height) {
            ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldScale = ui.scale
                    val scale = (oldScale * detector.scaleFactor).coerceIn(0.5f, 3f)
                    pan = bounded(Offset(pan.x, pan.y + height * (oldScale - scale) / 2), scale)
                    ui.scale = scale
                    return true
                }
            })
        }
        val gestures = Modifier.mangaTouchObserver(ui)
            .motionEventSpy { event ->
                MangaScrollDiagnostics.event("TOUCH") {
                    "action=${event.actionMasked} eventTime=${event.eventTime} x=${event.x} y=${event.y} pointers=${event.pointerCount} " +
                        "scale=${ui.scale} scaling=${scaleDetector.isInProgress}"
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) ui.touchUpRevision++
                if (ui.scaleEnabled && !ui.loading) scaleDetector.onTouchEvent(event)
            }
            .pointerInput(ui.scaleEnabled, ui.horizontal, ui.rtl, ui.loading) {
                if (ui.loading) return@pointerInput
                detectTapGestures(onTap = { point ->
                    when (mangaTapAction(point.x, point.y, width, height, ui.clickTurn, ui.horizontal && ui.rtl)) {
                        MangaTap.MENU -> latestMenu()
                        MangaTap.PREVIOUS -> ui.turn(-1)
                        MangaTap.NEXT -> ui.turn(1)
                        else -> Unit
                    }
                }, onDoubleTap = if (ui.scaleEnabled) { focus ->
                    scope.launch {
                        val target = if (ui.scale == 1f) 2f else 1f
                        val initialScale = ui.scale
                        val initialPan = pan
                        val targetPan = if (target == 1f) Offset.Zero else bounded(Offset(width / 2 - focus.x, -focus.y), target)
                        Animatable(0f).animateTo(1f, tween(200)) {
                            ui.scale = initialScale + (target - initialScale) * value
                            pan = bounded(initialPan + (targetPan - initialPan) * value, ui.scale)
                        }
                    }
                } else null)
            }
            .pointerInput(ui.horizontal, ui.rtl, ui.loading, ui.scale, displayPages, ui.chapterIndex, flipController) {
                if (ui.loading) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var total = Offset.Zero
                    var triggered = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            total += event.calculatePan()
                            triggered = flipController.onTouchDrag(
                                total = total,
                                touchSlop = viewConfiguration.touchSlop,
                                horizontal = ui.horizontal,
                                rtl = ui.rtl,
                                scale = ui.scale,
                                list = list,
                                pages = displayPages,
                                chapterIndex = ui.chapterIndex,
                                pointerCount = event.changes.count { it.pressed },
                            ) || triggered
                            if (triggered) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        flipController.onTouchEnd()
                    }
                }
            }
            .pointerInput(ui.scaleEnabled, ui.horizontal, ui.rtl, ui.loading, list, scaleDetector) {
                if (!ui.scaleEnabled || ui.loading) return@pointerInput
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val upRevision = ui.touchUpRevision
                    val tracker = VelocityTracker()
                    tracker.addPosition(first.uptimeMillis, first.position)
                    var drag = false
                    var scaled = false
                    var total = Offset.Zero
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull()
                        if (change != null) tracker.addPosition(change.uptimeMillis, change.position)
                        val movement = event.calculatePan()
                        total += movement
                        val boundary = !list.canScrollBackward || !list.canScrollForward
                        if (scaleDetector.isInProgress) {
                            scaled = true
                            event.changes.forEach { it.consume() }
                        } else if (!scaled && ui.scale > 1f &&
                            (drag || abs(total.x) > viewConfiguration.touchSlop || boundary && abs(total.y) > viewConfiguration.touchSlop)) {
                            drag = true
                            pan = bounded(pan + Offset(movement.x, if (boundary) movement.y else 0f), ui.scale)
                            event.changes.forEach { it.consume() }
                        } else if (scaled) {
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (drag && ui.scale > 1f && upRevision != ui.touchUpRevision) {
                        val velocity = tracker.calculateVelocity()
                        val boundary = !list.canScrollBackward || !list.canScrollForward
                        val target = bounded(pan + Offset(velocity.x, if (boundary) velocity.y else 0f) * 0.2f, ui.scale)
                        val start = pan
                        scope.launch {
                            Animatable(0f).animateTo(1f, tween(400)) {
                                pan = start + (target - start) * value
                            }
                        }
                    }
                }
            }
        val contentModifier = Modifier.fillMaxSize().then(gestures).layout { measurable, limits ->
            val h = if (ui.scale < 1f) (limits.maxHeight / ui.scale).roundToInt() else limits.maxHeight
            val placeable = measurable.measure(limits.copy(minHeight = h, maxHeight = h))
            layout(limits.maxWidth, limits.maxHeight) { placeable.place(0, 0) }
        }.graphicsLayer {
            scaleX = ui.scale; scaleY = ui.scale
            translationX = pan.x; translationY = pan.y
            transformOrigin = TransformOrigin(0.5f, 0f)
        }
        val content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            items(displayPages, key = ::mangaPageKey, contentType = { if (it is MangaPage) "image" else "edge" }) { page ->
                val modifier = if (ui.horizontal) Modifier.width(viewportWidth) else Modifier.fillMaxWidth()
                if (page is MangaPage) MangaImage(page, ui, viewportHeight,
                    if (ui.horizontal) modifier.height(viewportHeight / ui.scale.coerceAtMost(1f)) else modifier,
                    imageRatios = imageRatios)
                else if (page is NgMangaSpread) NgMangaSpreadImage(page, ui, viewportHeight, modifier.height(viewportHeight))
                else if (page is ReaderLoading) Box(modifier.height(if (page.isVolume) viewportHeight else 96.dp), Alignment.Center) {
                    Text(page.mMessage.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            // Rebase a visible tail onto newly appended chapter content instead of anchoring past it.
            item(key = mangaTailKey(displayPages), contentType = "tail") {
                Box(if (ui.horizontal) Modifier.width(viewportWidth).height(viewportHeight)
                    else Modifier.fillMaxWidth().height(48.dp), Alignment.Center) {
                    Row(Modifier.clickable(enabled = ui.tailRetry, onClick = onRetryTail), verticalAlignment = Alignment.CenterVertically) {
                        if (ui.tailLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Text(ui.tailMessage, Modifier.padding(8.dp), color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (ui.horizontal) LazyRow(contentModifier, state = list, reverseLayout = ui.rtl,
                flingBehavior = normalFling,
                userScrollEnabled = !ui.loading, verticalAlignment = Alignment.Top, content = content)
            else LazyColumn(contentModifier, state = list, userScrollEnabled = !ui.loading, content = content)
        }
    }
}

/** Compose synthesizes cancellation without a MotionEvent; observe pointer lifetime, never consume it. */
internal fun Modifier.mangaTouchObserver(ui: MangaReaderState): Modifier = pointerInput(ui) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        ui.touching = true
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally { ui.touching = false }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun MangaImage(page: MangaPage, ui: MangaReaderState, viewportHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier, paged: Boolean = false, imageRatios: MangaImageRatioCache? = null) {
    val context = LocalContext.current
    val book = remember(ui.bookUrl, ui.sourceOrigin, ui.translationRevision) {
        ReadManga.book?.takeIf { it.bookUrl == ui.bookUrl && it.origin == ui.sourceOrigin }?.copy()
    }
    val translation = rememberNgMangaTranslation(book, page.mImageUrl, page.chapterIndex.toString(), page.index,
        ui.translationEnabled, ui.active && (paged || ui.currentPage?.chapterIndex == page.chapterIndex), ui.translationRevision)
    var translatedImageLoading by remember(translation.file, translation.version, translation.revision, ui.translationRevision) {
        mutableStateOf(translation.file != null)
    }
    val pdfPreview = rememberNgPdfPreview(book, page.mImageUrl, ui.active)
    val background = colorResource(R.color.book_ant_10)
    var ratio by remember(page.mImageUrl, imageRatios) { mutableFloatStateOf(imageRatios?.get(page.mImageUrl) ?: 0f) }
    var loading by remember(page.mImageUrl) { mutableStateOf(true) }
    var hasDisplayedImage by remember(page.mImageUrl) { mutableStateOf(false) }
    var failed by remember(page.mImageUrl) { mutableStateOf(false) }
    var loadingIndicatorVisible by remember(page.mImageUrl) { mutableStateOf(false) }
    LaunchedEffect(loading, failed, hasDisplayedImage, translation.busy, translatedImageLoading) {
        loadingIndicatorVisible = false
        if (loading || translation.busy || translatedImageLoading) {
            // Cached images normally complete in the same frame; don't flash a loading cover.
            delay(200)
            loadingIndicatorVisible = true
        }
    }
    var progress by remember(page.mImageUrl) { mutableIntStateOf(-1) }
    var retry by remember(page.mImageUrl) { mutableIntStateOf(0) }
    var availableRevision by remember(ui.bookUrl, page.mImageUrl) { mutableIntStateOf(0) }
    var retriedRevision by remember(ui.bookUrl, page.mImageUrl) { mutableIntStateOf(0) }
    LaunchedEffect(ui.bookUrl, page.mImageUrl) {
        NgOriginalImageResources.imagesReady.collect { image ->
            if (image.bookId == ui.bookUrl && image.url == page.mImageUrl) availableRevision++
        }
    }
    LaunchedEffect(failed, availableRevision) {
        if (failed && !hasDisplayedImage && availableRevision > retriedRevision) {
            retriedRevision = availableRevision
            retry++
        }
    }
    val diagnosticInstance = remember(page.mImageUrl) { MangaScrollDiagnostics.nextInstance() }
    DisposableEffect(page.mImageUrl) {
        MangaScrollDiagnostics.event("IMAGE_CREATE") { "key=${mangaPageKey(page)} instance=$diagnosticInstance ratio=$ratio" }
        onDispose {
            MangaScrollDiagnostics.event("IMAGE_DISPOSE") { "key=${mangaPageKey(page)} instance=$diagnosticInstance ratio=$ratio" }
        }
    }
    val transform: Transformation<Bitmap>? = remember(ui.colorMode, ui.threshold) {
        when (ui.colorMode) {
            MangaColorMode.INK -> EpaperTransformation(ui.threshold)
            MangaColorMode.GRAY -> GrayscaleTransformation()
            else -> null
        }
    }
    val filter = remember(ui.filter) { ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        (255 - ui.filter.r) / 255f, 0f, 0f, 0f, 0f,
        0f, (255 - ui.filter.g) / 255f, 0f, 0f, 0f,
        0f, 0f, (255 - ui.filter.b) / 255f, 0f, 0f,
        0f, 0f, 0f, (255 - ui.filter.a) / 255f, 0f,
    ))) }
    val request: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable> = remember(context, page.mImageUrl, ui.sourceOrigin, retry, transform) {
        {
            BookCover.loadManga(context, page.mImageUrl, sourceOrigin = ui.sourceOrigin, transformation = transform)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        loading = false; failed = !hasDisplayedImage; return false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        MangaScrollDiagnostics.event("IMAGE_READY") {
                            "key=${mangaPageKey(page)} instance=$diagnosticInstance oldRatio=$ratio " +
                                "width=${resource.intrinsicWidth} height=${resource.intrinsicHeight} source=$dataSource"
                        }
                        if (resource.intrinsicWidth > 0 && resource.intrinsicHeight > 0) {
                            imageRatios?.put(page.mImageUrl, resource.intrinsicWidth, resource.intrinsicHeight)
                            ratio = resource.intrinsicWidth.toFloat() / resource.intrinsicHeight
                        }
                        hasDisplayedImage = true
                        loading = false; failed = false; return false
                    }
                })
        }
    }
    DisposableEffect(page.mImageUrl, retry, transform) {
        loading = !hasDisplayedImage; failed = false; progress = -1
        val subscription = ProgressManager.subscribe(page.mImageUrl) { _, percentage, _, _ -> progress = percentage }
        onDispose { subscription.close() }
    }
    val diagnosticModifier = if (MangaScrollDiagnostics.enabled) Modifier.onSizeChanged {
        MangaScrollDiagnostics.event("IMAGE_SIZE") {
            "key=${mangaPageKey(page)} instance=$diagnosticInstance width=${it.width} height=${it.height} ratio=$ratio"
        }
    } else Modifier
    BoxWithConstraints(modifier.then(diagnosticModifier).background(background), contentAlignment = Alignment.Center) {
        val imageHeight = if (ratio > 0) maxWidth / ratio else viewportHeight
        val verticalHeight = imageHeight
        val fit = paged || ui.horizontal
        val imageModifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(imageHeight)
        Box(if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(verticalHeight), Alignment.TopCenter) {
            pdfPreview?.takeIf { loading && !failed }?.let { preview ->
                GlideImage(preview, "PDF 预览 ${page.index + 1}", imageModifier,
                    contentScale = if (fit) ContentScale.Fit else ContentScale.FillBounds, colorFilter = filter,
                    requestBuilderTransform = { it.diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true) })
            }
            // Keep the original mounted while resolving/decoding a translation.
            key(page.mImageUrl, ui.sourceOrigin, retry, transform) {
                GlideImage(page.mImageUrl, "${page.mChapterName} ${page.index + 1}", imageModifier,
                    contentScale = if (fit) ContentScale.Fit else ContentScale.FillBounds,
                    colorFilter = filter,
                    requestBuilderTransform = request)
            }
            if (ui.translationEnabled) {
                translation.file?.let { translated ->
                    val translatedRequest: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable> =
                        remember(context, translated, translation.version, transform) {
                            { builder ->
                                builder.diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                    .skipMemoryCache(false)
                                    .signature(com.bumptech.glide.signature.ObjectKey(translation.version.orEmpty()))
                                    .priority(com.bumptech.glide.Priority.HIGH)
                                    .override(context.resources.displayMetrics.widthPixels, Target.SIZE_ORIGINAL)
                                    .apply { if (transform != null) transform(transform) }
                                    .listener(object : RequestListener<Drawable> {
                                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                            translatedImageLoading = false
                                            translation.displayFailed()
                                            return false
                                        }
                                        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                            if (resource.intrinsicWidth > 0 && resource.intrinsicHeight > 0) {
                                                imageRatios?.put(page.mImageUrl, resource.intrinsicWidth, resource.intrinsicHeight)
                                                ratio = resource.intrinsicWidth.toFloat() / resource.intrinsicHeight
                                            }
                                            translatedImageLoading = false
                                            return false
                                        }
                                    })
                            }
                        }
                    key(translated, translation.version, transform) {
                        GlideImage(translated, "译图 ${page.index + 1}", imageModifier,
                            contentScale = if (fit) ContentScale.Fit else ContentScale.FillBounds, colorFilter = filter,
                            requestBuilderTransform = translatedRequest)
                    }
                }
            }
            if (showMangaOriginalPlaceholder(loading, failed, hasDisplayedImage || (ui.translationEnabled && translation.file != null && !translatedImageLoading), loadingIndicatorVisible, pdfPreview != null)) Box(Modifier.fillMaxSize().background(background), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (failed) Text("重新加载", Modifier.clickable { retry++ }.padding(16.dp), color = Color.White, fontSize = 18.sp)
                    else {
                        CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
                        Text(if (progress < 0) "加载中" else "$progress%", Modifier.padding(top = 8.dp), color = Color.White)
                    }
                }
            }
            val translationStatus = if (!ui.translationEnabled) null else when {
                translation.busy || translatedImageLoading -> if (!loadingIndicatorVisible) null
                    else if (translation.busy) MangaTranslationStatus.TRANSLATING else MangaTranslationStatus.LOADING
                translation.failed -> MangaTranslationStatus.FAILED
                translation.status == TranslationPageStatus.PARTIAL -> MangaTranslationStatus.PARTIAL
                else -> null
            }
            // Keep the exiting chip's label stable while its fade finishes.
            var lastTranslationStatus by remember { mutableStateOf(MangaTranslationStatus.TRANSLATING) }
            SideEffect { translationStatus?.let { lastTranslationStatus = it } }
            AnimatedVisibility(
                visible = translationStatus != null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120)),
            ) {
                MangaTranslationStatusChip(
                    status = translationStatus ?: lastTranslationStatus,
                    onRetry = { translation.retry() },
                )
            }
        }
    }
}

/** 只替代 RecyclerViewPreloader 的视口消费端；下载/缓存仍走 BookCover。 */
internal class MangaImagePreloader(
    private val context: android.content.Context,
    private val imageRatios: MangaImageRatioCache? = null,
    private val scope: CoroutineScope? = null,
    private val sourceOrigin: String? = null,
) {
    private val metadataTargets = linkedMapOf<String, Target<File?>>()
    private val decodedTargets = linkedMapOf<String, Target<Drawable>>()
    private var decodedTransformationKey: String? = null
    fun update(
        pages: List<BaseMangaPage>,
        prefetchItems: List<DisplayItem?>,
        position: Int,
        count: Int,
        forward: Boolean,
        transformation: Transformation<Bitmap>? = null,
    ) {
        val plan = NgMangaReaderBridge.prefetchItems(prefetchItems, position, forward, if (ReadManga.book?.isPdf == true) 0 else count)
        // 只读取相邻已缓存文件的尺寸，不额外下载反方向图片。
        if (imageRatios != null) {
            val imageBook = ReadManga.book?.takeIf { sourceOrigin == null || it.origin == sourceOrigin }
            val nearby = if (imageBook == null) emptySet() else plan.metadata.map { it.resource }
                .filter { imageRatios.get(it) == 0f }
                .filter { NgOriginalImageResources.hasCachedImage(imageBook, it) }
                .toSet()
            val metadataIterator = metadataTargets.iterator()
            while (metadataIterator.hasNext()) {
                val entry = metadataIterator.next()
                if (entry.key !in nearby) { Glide.with(context).clear(entry.value); metadataIterator.remove() }
            }
            for (url in nearby) if (url !in metadataTargets) {
                metadataTargets[url] = request(url).set(NgOriginalImageResources.cacheOnlyOption, true).preload()
            }
        }
        // Network downloads are owned by the sequential chapter queue.
        // The reader only decodes files that are already cached for immediate display.
        val transformKey = transformation?.key()
        if (decodedTransformationKey != transformKey) {
            decodedTargets.values.forEach { Glide.with(context).clear(it) }
            decodedTargets.clear()
            decodedTransformationKey = transformKey
        }
        val imageBook = ReadManga.book?.takeIf { sourceOrigin == null || it.origin == sourceOrigin }
        val decodeUrls = if (imageBook == null || imageBook.isPdf) emptySet()
            else plan.network.take(DECODE_AHEAD_COUNT).map { it.resource }
                .filter { NgOriginalImageResources.hasCachedImage(imageBook, it) }.toSet()
        val retainedDecodeUrls = decodeUrls + pages.getOrNull(position)
            ?.let(NgMangaLayout::realPages).orEmpty().map { it.mImageUrl }
        val decodedIterator = decodedTargets.iterator()
        while (decodedIterator.hasNext()) {
            val entry = decodedIterator.next()
            if (entry.key !in retainedDecodeUrls) {
                Glide.with(context).clear(entry.value)
                decodedIterator.remove()
            }
        }
        for (url in decodeUrls) {
            if (url !in decodedTargets) {
                decodedTargets[url] = BookCover.loadManga(
                    context, url, sourceOrigin = sourceOrigin ?: ReadManga.book?.origin,
                    transformation = transformation,
                ).set(NgOriginalImageResources.cacheOnlyOption, true)
                    .priority(com.bumptech.glide.Priority.LOW).preload()
            }
        }
    }
    private fun request(url: String): RequestBuilder<File?> {
        var request = BookCover.preloadManga(context, url, sourceOrigin = sourceOrigin ?: ReadManga.book?.origin)
        if (imageRatios == null || scope == null) return request
        val snapshot = ReadManga.book?.takeIf { sourceOrigin == null || it.origin == sourceOrigin }?.let { NgOriginalImageResources.request(it, sourceOrigin, url) }
        return request.addListener(object : RequestListener<File?> {
            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<File?>, isFirstResource: Boolean) = false
            override fun onResourceReady(resource: File?, model: Any, target: Target<File?>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                if (resource != null && imageRatios.get(url) == 0f) scope.launch(Dispatchers.IO) {
                    if (imageRatios.get(url) != 0f) return@launch
                    if (snapshot == null) return@launch
                    try {
                        NgOriginalImageResources.resolve(snapshot, ResourcePriority.PREFETCH, allowNetwork = false).use { lease ->
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(lease.file.absolutePath, options)
                            imageRatios.put(url, options.outWidth, options.outHeight)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Cache eviction/version change: visible loading will retry. */ }
                }
                return false
            }
        })
    }
    fun clear() {
        (metadataTargets.values + decodedTargets.values).forEach { Glide.with(context).clear(it) }
        metadataTargets.clear(); decodedTargets.clear()
        decodedTransformationKey = null
    }

    private fun Transformation<Bitmap>.key(): String = "${javaClass.name}:${hashCode()}"

    companion object {
        const val IMAGE_LOOKAHEAD_COUNT = 3
        const val DECODE_AHEAD_COUNT = 3
    }
}
