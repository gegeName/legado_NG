package io.legado.app.ui.book.manga

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.integration.manga.NgMangaLayout
import io.legado.app.integration.manga.NgMangaSpread
import io.legado.app.integration.manga.NgMangaSpreadImage
import io.legado.app.ui.book.manga.config.MangaReadingStyle
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.EpaperTransformation
import io.legado.app.ui.book.manga.entities.GrayscaleTransformation
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading
import io.legado.app.ui.book.read.page.PageCoverRenderer
import io.legado.app.ui.book.read.page.PageCurlRenderer
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private class RecordedMangaPage { var key: String? = null; var turn: MangaPageTurn? = null }
private data class MangaPageFrames(val current: Bitmap, val adjacent: Bitmap)

/** A page surface only: ReadManga remains the sole chapter and persistent-progress owner. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun MangaPagedImages(ui: MangaReaderState, onPage: (BaseMangaPage) -> Unit,
    onMenu: () -> Unit, onRetryTail: () -> Unit) {
    val displayPages by rememberUpdatedState(remember(ui.pages, ui.horizontal, ui.doublePage, ui.rtl) {
        NgMangaLayout.display(ui.pages, ui.horizontal && ui.doublePage, ui.rtl)
    })
    val prefetchItems = remember(displayPages) { NgMangaLayout.prefetchItems(displayPages) }
    val displayJump = NgMangaLayout.slot(displayPages, ui.pages.getOrNull(ui.jumpIndex), ui.jumpIndex)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestPage by rememberUpdatedState(onPage)
    val latestMenu by rememberUpdatedState(onMenu)
    var position by remember { mutableIntStateOf(displayJump.coerceIn(0, displayPages.size)) }
    var anchor by remember { mutableStateOf(mangaItemKey(displayPages, position)) }
    var appliedJump by remember { mutableIntStateOf(-1) }
    var turn by remember { mutableStateOf<MangaPageTurn?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf(false) }
    var frames by remember { mutableStateOf<MangaPageFrames?>(null) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    var curlTop by remember { mutableStateOf(false) }
    val currentLayer = rememberGraphicsLayer()
    val targetLayer = rememberGraphicsLayer()
    val currentRecord = remember { RecordedMangaPage() }
    val targetRecord = remember { RecordedMangaPage() }
    val imageTransformation = remember(ui.colorMode, ui.threshold) {
        when (ui.colorMode) {
            MangaColorMode.INK -> EpaperTransformation(ui.threshold)
            MangaColorMode.GRAY -> GrayscaleTransformation()
            else -> null
        }
    }
    val preloader = remember(context, ui.bookUrl, ui.sourceOrigin) {
        MangaImagePreloader(context, sourceOrigin = ui.sourceOrigin)
    }
    DisposableEffect(preloader) { onDispose { preloader.clear() } }
    val background = colorResource(R.color.book_ant_10)

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(background)) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val viewportHeight = maxHeight
        val extent = if (ui.horizontal) width else height
        val renderer = remember(width, height, ui.horizontal, ui.rtl) {
            MangaTurnRenderer(width.roundToInt(), height.roundToInt(), ui.horizontal, ui.rtl)
        }
        val effect = ui.reading.effect
        val needsFrames = effect == MangaReadingStyle.COVER || effect == MangaReadingStyle.CURL
        val currentKey = mangaItemKey(displayPages, position)
        val targetPosition = turn?.let { mangaAnchorIndex(displayPages, it.to, position) }

        LaunchedEffect(displayPages, ui.jumpRevision, ui.active, ui.loading, effect, width, height) {
            zoomJob?.cancel()
            turn = null; frames = null; progress = 0f; settling = false
            position = if (appliedJump != ui.jumpRevision) displayJump.coerceIn(0, displayPages.size)
                else mangaAnchorIndex(displayPages, anchor, position)
            anchor = mangaItemKey(displayPages, position)
            appliedJump = ui.jumpRevision
        }
        LaunchedEffect(currentKey, displayPages, ui.loading, appliedJump) {
            if (!ui.loading && appliedJump == ui.jumpRevision) {
                val page = displayPages.getOrNull(position) ?: mangaTailPage(displayPages)
                page?.let { latestPage(NgMangaLayout.progress(it, ui.currentPage)) }
            }
        }
        LaunchedEffect(ui.active, displayPages, ui.preload, imageTransformation) {
            if (!ui.active) { preloader.clear(); return@LaunchedEffect }
            var previous = position
            snapshotFlow { position }.collect { index ->
                preloader.update(
                    displayPages, prefetchItems, index, ui.preload, index >= previous,
                    transformation = imageTransformation,
                )
                previous = index
            }
        }
        LaunchedEffect(ui.scaleEnabled, currentKey) {
            zoomJob?.cancel()
            if (!ui.scaleEnabled) ui.scale = 1f
            pan = Offset.Zero
        }
        LaunchedEffect(turn) {
            val pending = turn ?: return@LaunchedEffect
            if (!needsFrames) return@LaunchedEffect
            try {
                // Wait for the actual current/target draw records, including loading/error surfaces.
                while (currentRecord.turn !== pending || targetRecord.turn !== pending ||
                    currentRecord.key != pending.from || targetRecord.key != pending.to ||
                    currentLayer.size.width == 0 || targetLayer.size.width == 0) withFrameNanos { }
                val before = currentLayer.toImageBitmap().asAndroidBitmap()
                val after = targetLayer.toImageBitmap().asAndroidBitmap()
                if (turn === pending) frames = MangaPageFrames(before, after)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (turn === pending) {
                    turn = null; frames = null; settling = false; progress = 0f
                    context.toastOnUi("翻页画面生成失败，请重试")
                }
            }
        }

        fun begin(direction: Int, focus: Offset = Offset(width / 2, height / 2)): MangaPageTurn? {
            if (turn != null || settling || ui.loading || !ui.active || appliedJump != ui.jumpRevision) return null
            // pointerInput and the auto loop outlive recompositions: do not capture currentKey.
            val pending = mangaBeginTurn(displayPages, position, direction, ui.jumpRevision) ?: return null
            curlTop = (if (ui.horizontal) focus.y else focus.x) < (if (ui.horizontal) height else width) / 2
            turn = pending; progress = 0f; frames = null
            return pending
        }
        suspend fun finish(pending: MangaPageTurn, complete: Boolean) {
            if (turn !== pending || settling) return
            settling = true
            try {
                if (needsFrames) snapshotFlow { frames != null || turn !== pending }.first { it }
                if (turn !== pending) return
                if (effect != MangaReadingStyle.NONE) {
                    val endpoint = if (complete) 1f else 0f
                    Animatable(progress).animateTo(endpoint,
                        tween((220 * abs(endpoint - progress)).roundToInt().coerceAtLeast(80), easing = LinearEasing)) {
                        if (turn !== pending) throw CancellationException("Manga page turn invalidated")
                        progress = value
                    }
                }
                if (complete && mangaCanCommit(pending, anchor, displayPages, ui.jumpRevision)) {
                    position = mangaAnchorIndex(displayPages, pending.to, position)
                    anchor = pending.to
                    ui.scale = 1f; pan = Offset.Zero
                }
            } finally {
                if (turn === pending) { turn = null; frames = null; progress = 0f; settling = false }
            }
        }
        LaunchedEffect(ui.turnRevision) {
            val direction = ui.claimTurn() ?: return@LaunchedEffect
            scope.launch {
                zoomJob?.cancel()
                ui.scale = 1f; pan = Offset.Zero
                begin(direction)?.let { finish(it, true) }
            }
        }
        LaunchedEffect(ui.autoRunning, ui.autoMode, ui.autoInterval, effect, width, height) {
            if (!ui.autoRunning || ui.autoMode != MangaAutoMode.PAGE) return@LaunchedEffect
            while (isActive) {
                delay(ui.autoInterval * 1000L)
                if (!ui.autoRunning) break
                if (ui.scale == 1f) begin(1)?.let { finish(it, true) }
            }
        }

        fun bounded(offset: Offset, scale: Float): Offset = Offset(
            offset.x.coerceIn(-width * (scale - 1).coerceAtLeast(0f) / 2, width * (scale - 1).coerceAtLeast(0f) / 2),
            offset.y.coerceIn(-height * (scale - 1).coerceAtLeast(0f) / 2, height * (scale - 1).coerceAtLeast(0f) / 2))
        val scaleDetector = remember(context, width, height) {
            ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    zoomJob?.cancel()
                    return true
                }
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    ui.scale = (ui.scale * detector.scaleFactor).coerceIn(0.5f, 3f)
                    pan = bounded(pan, ui.scale)
                    return true
                }
            })
        }
        val gestures = Modifier.mangaTouchObserver(ui)
            .motionEventSpy {
                if (it.actionMasked == MotionEvent.ACTION_UP) ui.touchUpRevision++
                if (ui.scaleEnabled && !ui.loading) scaleDetector.onTouchEvent(it)
            }
            .pointerInput(ui.loading, ui.scaleEnabled) {
                if (ui.loading) return@pointerInput
                detectTapGestures(onTap = { point ->
                    when (mangaTapAction(point.x, point.y, width, height, ui.clickTurn, ui.horizontal && ui.rtl)) {
                        MangaTap.MENU -> latestMenu()
                        MangaTap.NEXT -> ui.turn(1)
                        MangaTap.PREVIOUS -> ui.turn(-1)
                        else -> Unit
                    }
                }, onDoubleTap = if (ui.scaleEnabled) { point ->
                    if (turn == null) {
                        zoomJob?.cancel()
                        zoomJob = scope.launch {
                            val from = ui.scale
                            val to = if (from == 1f) 2f else 1f
                            val fromPan = pan
                            val toPan = if (to == 1f) Offset.Zero else bounded(Offset(width / 2 - point.x, height / 2 - point.y), to)
                            Animatable(0f).animateTo(1f, tween(200)) {
                                ui.scale = from + (to - from) * value
                                pan = bounded(fromPan + (toPan - fromPan) * value, ui.scale)
                            }
                        }
                    }
                } else null)
            }
            .pointerInput(ui.loading, ui.active, ui.horizontal, ui.rtl, effect, width, height) {
                if (ui.loading || !ui.active) return@pointerInput
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val upRevision = ui.touchUpRevision
                    val tracker = VelocityTracker().apply { addPosition(first.uptimeMillis, first.position) }
                    var displacement = Offset.Zero
                    var pending: MangaPageTurn? = null
                    var multiTouch = false
                    var moved = false
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val primary = event.changes.firstOrNull { it.id == first.id }
                            if (primary != null) tracker.addPosition(primary.uptimeMillis, primary.position)
                            val movement = event.calculatePan()
                            displacement += movement
                            if (event.changes.count { it.pressed } > 1 || scaleDetector.isInProgress) multiTouch = true
                            if (multiTouch) {
                                event.changes.forEach { it.consume() }
                            } else if (ui.scale != 1f) {
                                if (moved || displacement.getDistance() > viewConfiguration.touchSlop) {
                                    moved = true; pan = bounded(pan + movement, ui.scale)
                                    event.changes.forEach { it.consume() }
                                }
                            } else {
                                val distance = if (ui.horizontal) displacement.x else displacement.y
                                if (pending == null && abs(distance) > viewConfiguration.touchSlop) {
                                    val direction = if (distance * mangaTurnSign(ui.horizontal, ui.rtl, 1) > 0) 1 else -1
                                    pending = begin(direction, first.position)
                                }
                                pending?.let {
                                    if (turn === it && !settling) progress =
                                        (distance * mangaTurnSign(ui.horizontal, ui.rtl, it.direction) / extent).coerceIn(0f, 1f)
                                    event.changes.forEach { change -> change.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        pending?.let {
                            val velocity = tracker.calculateVelocity()
                            val forward = (if (ui.horizontal) velocity.x else velocity.y) * mangaTurnSign(ui.horizontal, ui.rtl, it.direction)
                            val complete = !multiTouch && upRevision != ui.touchUpRevision &&
                                mangaFinishTurn(progress, forward, 600.dp.toPx())
                            released = true
                            scope.launch { finish(it, complete) }
                        }
                    } finally {
                        if (!released && pending != null && turn === pending && !settling) {
                            val interrupted = requireNotNull(pending)
                            scope.launch { finish(interrupted, false) }
                        }
                    }
                }
            }

        Box(Modifier.fillMaxSize().then(gestures)) {
            val pending = turn
            val sign = pending?.let { mangaTurnSign(ui.horizontal, ui.rtl, it.direction) } ?: -1
            // Keep the target's keyed composition when it becomes current: no second Glide load/flash.
            val surfaces = if (pending != null && targetPosition != null) listOf(targetPosition, position) else listOf(position)
            surfaces.forEach { index -> key(mangaItemKey(displayPages, index)) {
                val isCurrent = index == position
                MangaPagedSurface(ui, displayPages, index, viewportHeight, onRetryTail,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (isCurrent) ui.scale else 1f; scaleY = scaleX
                        translationX = if (isCurrent) pan.x else 0f
                        translationY = if (isCurrent) pan.y else 0f
                        if (pending != null && effect == MangaReadingStyle.SLIDE) {
                            val distance = progress - if (isCurrent) 0f else 1f
                            if (ui.horizontal) translationX += sign * width * distance
                            else translationY += sign * height * distance
                        }
                    }.recordMangaPage(if (isCurrent) currentLayer else targetLayer,
                        if (isCurrent) currentRecord else targetRecord, mangaItemKey(displayPages, index),
                        pending, needsFrames && pending != null))
            } }
            if (pending != null && frames != null) Canvas(Modifier.fillMaxSize()) {
                val captured = frames ?: return@Canvas
                drawIntoCanvas { canvas ->
                    renderer.draw(canvas.nativeCanvas, captured, pending.direction, progress, effect, curlTop)
                }
            }
        }
    }
}

private fun Modifier.recordMangaPage(layer: GraphicsLayer, record: RecordedMangaPage, key: String,
    turn: MangaPageTurn?, enabled: Boolean) =
    drawWithContent {
        if (enabled) {
            layer.record { this@drawWithContent.drawContent() }
            record.key = key
            record.turn = turn
            drawLayer(layer)
        } else drawContent()
    }

@Composable
private fun MangaPagedSurface(ui: MangaReaderState, pages: List<BaseMangaPage>, index: Int, height: androidx.compose.ui.unit.Dp,
    onRetryTail: () -> Unit, modifier: Modifier) {
    val page = pages.getOrNull(index)
    if (page is MangaPage) MangaImage(page, ui, height, modifier, paged = true)
    else if (page is NgMangaSpread) NgMangaSpreadImage(page, ui, height, modifier)
    else Box(modifier.background(colorResource(R.color.book_ant_10)), Alignment.Center) {
        if (page is ReaderLoading) Text(page.mMessage.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        else Row(Modifier.clickable(enabled = ui.tailRetry, onClick = onRetryTail), verticalAlignment = Alignment.CenterVertically) {
            if (ui.tailLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Text(ui.tailMessage, Modifier.padding(8.dp), color = Color.White, fontSize = 14.sp)
        }
    }
}

/** Map the shared leftward graphics kernel to RTL or vertical without mirroring image content. */
private class MangaTurnRenderer(private val width: Int, private val height: Int, horizontal: Boolean, rtl: Boolean) {
    private val virtualWidth = if (horizontal) width else height
    private val virtualHeight = if (horizontal) height else width
    private val orientation = Matrix().apply {
        if (!horizontal) setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        else if (rtl) setValues(floatArrayOf(-1f, 0f, width.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f))
    }
    private val cover = PageCoverRenderer().apply { setViewSize(virtualWidth, virtualHeight) }
    private val curl = PageCurlRenderer().apply { setViewSize(virtualWidth, virtualHeight) }

    fun draw(canvas: android.graphics.Canvas, frames: MangaPageFrames, direction: Int, progress: Float, effect: Int, top: Boolean) {
        if (progress <= 0f || progress >= 1f) {
            canvas.drawBitmap(if (progress >= 1f) frames.adjacent else frames.current, 0f, 0f, null)
            return
        }
        val pageDirection = if (direction > 0) PageDirection.NEXT else PageDirection.PREV
        canvas.save()
        canvas.concat(orientation)
        fun frame(bitmap: Bitmap, target: android.graphics.Canvas) {
            target.save(); target.concat(orientation); target.drawBitmap(bitmap, 0f, 0f, null); target.restore()
        }
        if (effect == MangaReadingStyle.COVER) {
            frame(frames.current, canvas)
            cover.draw(canvas, -direction * virtualWidth * progress,
                pageDirection, current = { frame(frames.current, it) }, adjacent = { frame(frames.adjacent, it) })
        } else {
            val y = if (top && direction > 0) 1f + virtualHeight * 0.16f * (1 - progress)
                else virtualHeight - 1f - virtualHeight * 0.16f * (1 - progress)
            curl.calcCornerXY(virtualWidth.toFloat(), if (top && direction > 0) 0f else virtualHeight.toFloat())
            curl.draw(canvas, frames.current, frames.adjacent, pageDirection,
                virtualWidth * if (direction > 0) (1f - 2 * progress) else (2 * progress - 1f), y, orientation)
        }
        canvas.restore()
    }
}
