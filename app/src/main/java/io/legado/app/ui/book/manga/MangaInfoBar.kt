package io.legado.app.ui.book.manga

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterContent
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.design.theme.NgMangaPanelTheme
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date

@Immutable
internal data class MangaFooterStatus(val time: String, val battery: Int?)

/** 正文与设置预览共享一份受生命周期约束的时间和电量。 */
@Composable
internal fun rememberMangaFooterStatus(): MangaFooterStatus {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember(context) {
        mutableStateOf(MangaFooterStatus(mangaCurrentTime(), null))
    }
    DisposableEffect(context, lifecycle) {
        fun updateBattery(intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            status = status.copy(battery = mangaBatteryPercent(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            ))
        }
        fun refresh() {
            status = status.copy(time = mangaCurrentTime())
            updateBattery(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                status = status.copy(time = mangaCurrentTime())
                updateBattery(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        updateBattery(ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        ))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return status
}

private fun mangaCurrentTime(): String =
    SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT).format(Date())

internal fun mangaBatteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else (level * 100L / scale).coerceIn(0, 100).toInt()

@Composable
internal fun MangaInfoBar(
    ui: MangaReaderState,
    modifier: Modifier = Modifier,
    status: MangaFooterStatus = rememberMangaFooterStatus(),
) {
    MangaFooterBar(ui.footer, ui.currentPage, ui.bookName, status.time, status.battery, modifier)
}

/** 空槽不占宽；两端贴边，非空中间项保持屏幕几何中心。 */
@Composable
internal fun MangaFooterBar(
    config: MangaFooterConfig,
    page: MangaPage?,
    bookName: String,
    time: String,
    battery: Int?,
    modifier: Modifier = Modifier,
) {
    if (config.hideFooter) return
    NgMangaPanelTheme {
        val texts = listOf(config.left, config.middle, config.right).map {
            mangaFooterText(it, page, bookName, time, battery)
        }
        Layout(
            modifier = modifier.fillMaxWidth().heightIn(min = 24.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            content = {
                texts.forEachIndexed { index, text ->
                    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    Text(
                        text,
                        modifier = Modifier.drawWithContent {
                            textLayout?.let {
                                drawText(it, color = Color.White,
                                    drawStyle = Stroke(width = 1.5.dp.toPx()))
                            }
                            drawContent()
                        },
                        color = Color.Black,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { textLayout = it },
                        textAlign = when (index) {
                            0 -> TextAlign.Left
                            1 -> TextAlign.Center
                            else -> TextAlign.Right
                        },
                    )
                }
            },
        ) { measurables, constraints ->
            val naturalWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
            val gap = 8.dp.roundToPx()
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else {
                constraints.constrainWidth(naturalWidths.sum() + gap * 2)
            }
            val slots = mangaFooterSlotBounds(width, naturalWidths[0], naturalWidths[1], naturalWidths[2], gap)
            val placeables = measurables.mapIndexed { index, measurable ->
                measurable.measure(Constraints(
                    minWidth = slots[index].width,
                    maxWidth = slots[index].width,
                    maxHeight = constraints.maxHeight,
                ))
            }
            val height = constraints.constrainHeight(placeables.maxOf { it.height })
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    // 页脚配置指向物理左/中/右，不随系统 RTL 改换位置。
                    placeable.place(slots[index].x, (height - placeable.height) / 2)
                }
            }
        }
    }
}

internal fun mangaFooterText(
    content: Int,
    page: MangaPage?,
    bookName: String,
    time: String,
    battery: Int?,
): String = when (content) {
    MangaFooterContent.BOOK_NAME -> bookName
    MangaFooterContent.CHAPTER_NAME -> page?.mChapterName.orEmpty()
    MangaFooterContent.IMAGE_NUMBER -> page?.let {
        val count = it.imageCount.coerceAtLeast(0)
        "图片 ${(it.index + 1).coerceIn(0, count)}/$count"
    }.orEmpty()
    MangaFooterContent.CHAPTER_NUMBER -> page?.let {
        val count = it.chapterSize.coerceAtLeast(0)
        "章节 ${(it.chapterIndex + 1).coerceIn(0, count)}/$count"
    }.orEmpty()
    MangaFooterContent.PROGRESS -> page?.let {
        val ratio = when {
            it.chapterSize <= 0 || it.imageCount <= 0 && it.chapterIndex == 0 -> 0.0
            it.imageCount <= 0 -> (it.chapterIndex + 1.0) / it.chapterSize
            else -> it.chapterIndex.toDouble() / it.chapterSize +
                (it.index + 1.0) / it.imageCount / it.chapterSize
        }
        val percent = DecimalFormat("0.0%").format(ratio)
        if (percent == "100.0%" && (it.chapterIndex + 1 != it.chapterSize ||
                it.index + 1 != it.imageCount)) "99.9%" else percent
    }.orEmpty()
    MangaFooterContent.TIME -> time
    MangaFooterContent.BATTERY -> "${battery?.coerceIn(0, 100) ?: "--"}%"
    MangaFooterContent.TIME_BATTERY ->
        listOf(time, "${battery?.coerceIn(0, 100) ?: "--"}%").filter { it.isNotBlank() }.joinToString(" ")
    else -> ""
}
