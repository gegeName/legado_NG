package io.legado.features.translation

import io.legado.features.api.manga.*
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.text.LineBreaker
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.get
import kotlin.time.Duration.Companion.milliseconds
internal class TranslationPipeline(
    private val host: TranslationPlatform,
    private val settings: TranslationSettings,
    private val publicTranslator: MangaPublicTranslator,
    private val publish: suspend (File, File) -> Unit,
) {
    private val ALGORITHM_VERSION = "ng-manga-translation-v1"
    private val RENDER_VERSION = "bubble-mask-v4"
    private val AI_TIMEOUT_MS = 60_000L
    private val ai = host.ai
    private val aiIdentity = ai?.identity.orEmpty()
    private var noText = false
    private val aiTextGate = Mutex()
    private val aiUnavailableUntil = ConcurrentHashMap<String, Long>()
    private val imageVersions = object {
        fun getBoolean(key: String, default: Boolean) = host.config.get("manga-translation-cache", key)?.toBooleanStrictOrNull() ?: default
    }
    private fun recordPageState(identity: String, cacheKey: String, file: File) {
        host.config.put("manga-translation-cache", "partial:$identity", MangaTranslationPageCache.isPartial(file).toString())
        host.config.put("manga-translation-cache", "partial-cache:$cacheKey", MangaTranslationPageCache.isPartial(file).toString())
    }
    suspend fun translate(request: TranslationRequest, original: File, force: Boolean): TranslationPageResult {
        val path = translateImage(host.context, request, force, taskKey(request).take(12), original)
        val file = File(path)
        return TranslationPageResult(file, if (noText) TranslationPageStatus.NO_TEXT else if (MangaTranslationPageCache.isPartial(file)) TranslationPageStatus.PARTIAL else TranslationPageStatus.COMPLETE)
    }
    private fun digest(value: String) = sha256(value)
    private suspend fun translateImage(
        context: Context,
        request: TranslationRequest,
        force: Boolean,
        trace: String,
        original: File
    ): String {
        log(trace, "IMAGE_SNAPSHOT_READY")
        log(trace, "IMAGE_LOAD_OK bytes=${original.length()}")
        val sourceDigest = sha256(original)
        val cacheKey = sha256(
            listOf(
                ALGORITHM_VERSION,
                RENDER_VERSION,
                request.image.bookId,
                request.chapterId,
                request.pageIndex,
                request.image.source,
                request.image.configurationFingerprint,
                settings.providers,
                settings.maxPixels,
                sourceDigest,
                settings.sourceLanguage,
                settings.recognitionEngine,
                settings.targetLanguage,
                settings.textEngine,
                aiIdentity
            ).joinToString("|")
        )
        val directory = File(host.filesDirectory, "manga-translation/pages")
        check(directory.isDirectory || directory.mkdirs()) { "译图缓存目录不可用" }
        val identity = taskKey(request)
        val preferPartial = imageVersions.getBoolean("partial-cache:$cacheKey", false)
        MangaTranslationPageCache.find(directory, cacheKey, force, preferPartial)?.takeIf { cached ->
            val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cached.absolutePath, check)
            check.outWidth > 0 && check.outHeight > 0 && check.outWidth.toLong() * check.outHeight <= settings.maxPixels
        }?.let { cached ->
            noText = imageVersions.getBoolean("noText:$cacheKey", false)
            recordPageState(identity, cacheKey, cached)
            log(trace, "PAGE_CACHE_HIT")
            return cached.absolutePath
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "原图无法解码" }
        var sample = 1
        while (((bounds.outWidth.toLong() + sample - 1) / sample) * ((bounds.outHeight.toLong() + sample - 1) / sample) > settings.maxPixels) {
            check(sample <= Int.MAX_VALUE / 2) { "原图尺寸过大" }
            sample *= 2
        }
        val memory = io.legado.features.api.host.ImageMemoryBudget.process.acquire(settings.maxPixels * 12 + 12_000_000L)
        val bitmap = try { BitmapFactory.decodeFile(original.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("原图无法解码") }
            catch (e: Throwable) { memory.close(); throw e }
        return try {
            log(trace, "OCR_START engine=${settings.recognitionEngine} language=${settings.sourceLanguage} size=${bitmap.width}x${bitmap.height}")
            val recognized = try {
                recognize(context, original, bitmap, trace)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                log(trace, "OCR_FAILED", error)
                throw error
            }
            val regions = MangaTextEraser.mergeDuplicates(recognized.map { region ->
                MangaTextEraser.Box(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom)
            }).mapIndexed { index, group ->
                recognized[group.index].copy(id = "r$index", bounds = Rect(
                    group.bounds.left, group.bounds.top, group.bounds.right, group.bounds.bottom))
            }
            check(regions.size <= 512) { "单页文字区域过多" }
            noText = regions.isEmpty()
            log(trace, "OCR_DEDUP input=${recognized.size} output=${regions.size}")
            log(trace, "OCR_OK regions=${regions.size} duplicateIds=${recognized.size - recognized.distinctBy { it.id }.size}")
            regions.forEach { log(trace, "OCR_BLOCK id=${it.id} bounds=${it.bounds} text=${preview(it.text)}") }
            var complete = true
            val output = if (regions.isEmpty()) {
                log(trace, "NO_TEXT original_image")
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                val translations = translateText(regions, trace, taskKey(request))
                log(trace, "COMPOSE_START translated=${translations.size}/${regions.size}")
                val composition = compose(bitmap, regions, translations, trace)
                val state = MangaTranslationPageCache.state(regions.mapTo(mutableSetOf()) { it.id },
                    translations, composition.completedIds)
                complete = state.complete
                log(trace, "COMPOSE_DONE missing=${state.missingIds} retained=${state.retainedIds}")
                composition.bitmap
            }
            try {
                val target = MangaTranslationPageCache.target(directory, cacheKey, complete)
                val temporary = File.createTempFile(".$cacheKey-", ".tmp", directory)
                log(trace, "PUBLISH_START complete=$complete")
                try { withContext(Dispatchers.IO) {
                    FileOutputStream(temporary).use { stream ->
                        check(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "译图写入失败" }
                        stream.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    publish(temporary, target)
                } } finally { temporary.delete() }
                recordPageState(identity, cacheKey, target)
                host.config.put("manga-translation-cache", "noText:$cacheKey", noText.toString())
                log(trace, "PUBLISH_OK bytes=${target.length()} complete=$complete")
                target.absolutePath
            } finally {
                output.recycle()
            }
        } finally {
            bitmap.recycle()
            memory.close()
        }
    }

    private suspend fun recognize(context: Context, file: File, bitmap: Bitmap, trace: String): List<MangaTextRegion> {
        if (settings.recognitionEngine == "ai") {
            try {
                return withTimeoutOrNull(AI_TIMEOUT_MS.milliseconds) { recognizeWithAi(file, bitmap.width, bitmap.height, trace) }
                    ?: error("AI 识图超过 ${AI_TIMEOUT_MS / 1000} 秒")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                log(trace, "OCR_AI_FALLBACK_MLKIT", error)
            }
        }
        require(settings.recognitionEngine in setOf("mlkit", "ai")) { "不支持的文字识别方式" }
        val language = when (val configured = settings.sourceLanguage.lowercase()) {
            "zh", "zh-cn", "zh-tw", "chinese" -> "auto"
            else -> configured
        }
        if (language in setOf("auto", "ja", "ja-jp", "japanese")) {
            if (language != "auto") return recognizeWithMlKit(bitmap, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()))
            val results = mutableListOf<MangaTextRegion>()
            var succeeded = false
            val recognizers = listOf(
                "ja" to { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) },
                "latin" to { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            )
            recognizers.forEach { (name, factory) ->
                try {
                    val blocks = recognizeWithMlKit(bitmap, factory())
                    results.addAll(blocks)
                    succeeded = true
                    log(trace, "OCR_PASS_OK language=$name blocks=${blocks.size}")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    log(trace, "OCR_PASS_FAILED language=$name", error)
                }
            }
            check(succeeded) { "所有本地 OCR 识别器均失败" }
            return results.distinctBy { region ->
                "${region.text}|${region.bounds.left / 12}|${region.bounds.top / 12}"
            }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        }
        val recognizer = when (language) {
            "ko", "ko-kr", "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "en", "en-us", "latin" -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            else -> error("ML Kit 不支持该源语言")
        }
        return recognizeWithMlKit(bitmap, recognizer)
    }

    private suspend fun recognizeWithMlKit(bitmap: Bitmap, recognizer: TextRecognizer): List<MangaTextRegion> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            // Native OCR is cooperative: keep bitmap ownership until its actual completion.
            val result = withContext(NonCancellable) { recognizer.process(image).awaitResult() }
            currentCoroutineContext().ensureActive()
            result.textBlocks.mapIndexedNotNull { index, block ->
                val box = block.boundingBox ?: return@mapIndexedNotNull null
                block.text.trim().takeIf { it.isNotBlank() }?.let { text ->
                    MangaTextRegion("r$index", text, box)
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private suspend fun recognizeWithAi(file: File, width: Int, height: Int, trace: String): List<MangaTextRegion> {
        log(trace, "AI_OCR_START model=$aiIdentity timeoutMs=$AI_TIMEOUT_MS")
        val prompt = "识别漫画中的文字区域，只返回 JSON，不翻译，不解释。坐标使用整张图片的 0 到 1000 归一化像素范围，textBox 必须紧包围文字墨迹。格式：{\"regions\":[{\"id\":\"r0\",\"original\":\"原文\",\"textBox\":{\"left\":0,\"top\":0,\"right\":1000,\"bottom\":1000}}],\"noText\":false}"
        val response = checkNotNull(ai).also { check(it.supportsImages) { "宿主 AI 不支持图片识别" } }.structured(prompt, "识别漫画图片中的文字", file)
        log(trace, "AI_OCR_RESPONSE chars=${response.length} body=${preview(response, 3000)}")
        val root = JSONObject(cleanJson(response))
        if (root.optBoolean("noText", false)) return emptyList()
        val items = root.optJSONArray("regions") ?: error("AI 识图返回格式错误，缺少 regions")
        val regions = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val text = item.optString("original").trim()
                val box = item.optJSONObject("textBox") ?: continue
                if (text.isBlank()) continue
                val left = box.optInt("left", 0).coerceIn(0, 1000) * width / 1000
                val top = box.optInt("top", 0).coerceIn(0, 1000) * height / 1000
                val right = box.optInt("right", 1000).coerceIn(left * 1000 / max(1, width), 1000) * width / 1000
                val bottom = box.optInt("bottom", 1000).coerceIn(top * 1000 / max(1, height), 1000) * height / 1000
                if (right > left && bottom > top) {
                    add(MangaTextRegion(item.optString("id").ifBlank { "r$index" }, text, Rect(left, top, right, bottom)))
                }
            }
        }
        check(regions.isNotEmpty()) { "AI 识图未返回有效文字区域" }
        return regions
    }

    private suspend fun translateText(regions: List<MangaTextRegion>, trace: String, identity: String): Map<String, String> {
        require(settings.textEngine in setOf("ai", "google", "bing", "auto", "deepl", "baidu", "libre")) { "不支持的文字翻译引擎" }
        log(trace, "TEXT_START engine=${settings.textEngine} target=${settings.targetLanguage} blocks=${regions.size}")
        val translated = MangaTranslationFallback.translate(
            blocks = regions.associate { it.id to it.text },
            primary = { missing ->
                if (settings.textEngine != "ai") emptyMap()
                else translateWithAiFallback(regions.filter { it.id in missing }, trace)
            },
            fallback = { text ->
                publicTranslator.translate(
                    text,
                    settings.targetLanguage,
                    settings.textEngine,
                    identity,
                    log = { log(trace, it) },
                    config = settings.providers
                )
            },
            onFailure = { stage, error -> log(trace, "TEXT_FAILED stage=$stage", error) },
            initial = readTextCache(regions, trace),
            onTranslated = { translations -> writeTextCache(regions, translations, trace) }
        )
        val missing = regions.filter { it.id !in translated }.map { it.id }
        log(trace, "TEXT_DONE translated=${translated.size}/${regions.size} missing=$missing")
        translated.forEach { (id, text) -> log(trace, "TRANSLATION id=$id text=${preview(text)}") }
        return translated
    }

    private fun textCacheFile(text: String): File {
        val key = sha256(listOf(
            ALGORITHM_VERSION,
            settings.sourceLanguage,
            settings.targetLanguage,
            settings.textEngine,
            settings.providers,
            aiIdentity,
            text
        ).joinToString("|"))
        return File(host.filesDirectory, "manga-translation/text/$key.txt")
    }

    private fun readTextCache(regions: List<MangaTextRegion>, trace: String): Map<String, String> = buildMap {
        regions.forEach { region ->
            try {
                val file = textCacheFile(region.text)
                if (file.isFile) {
                    val text = file.readText()
                    if (text.isNotBlank()) put(region.id, text)
                }
            } catch (error: Exception) {
                log(trace, "BLOCK_CACHE_READ_FAILED id=${region.id}", error)
            }
        }
        log(trace, "BLOCK_CACHE_HIT blocks=$size/${regions.size}")
    }

    private suspend fun writeTextCache(regions: List<MangaTextRegion>, translations: Map<String, String>, trace: String) {
        regions.filter { it.id in translations }.forEach { region ->
            try {
                val file = textCacheFile(region.text)
                check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "文字缓存目录不可用" }
                val temporary = File.createTempFile(".text-", ".tmp", file.parentFile)
                try {
                    temporary.outputStream().use { it.write(translations.getValue(region.id).toByteArray()); it.fd.sync() }
                    currentCoroutineContext().ensureActive()
                    publish(temporary, file)
                } finally { temporary.delete() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log(trace, "BLOCK_CACHE_WRITE_FAILED id=${region.id}", error)
            }
        }
    }

    private suspend fun translateWithAi(regions: List<MangaTextRegion>, trace: String): Map<String, String> {
        log(trace, "AI_START model=$aiIdentity timeoutMs=$AI_TIMEOUT_MS")
        val blocks = JSONArray().apply {
            regions.forEach { region ->
                put(JSONObject().apply {
                    put("id", region.id)
                    put("text", region.text)
                })
            }
        }
        val systemPrompt = "你是漫画文字翻译器。只翻译给定文字块，保留每个 id，不添加解释，不遗漏文字块。目标语言是 ${settings.targetLanguage}。只返回 JSON：{\"translations\":[{\"id\":\"r0\",\"translation\":\"译文\"}]}"
        val response = checkNotNull(ai) { "未配置 AI 提供商" }.structured(systemPrompt, JSONObject().put("blocks", blocks).toString())
        log(trace, "AI_RESPONSE chars=${response.length} body=${preview(response, 3000)}")
        val root = JSONObject(cleanJson(response))
        val items = root.optJSONArray("translations") ?: error("AI 翻译返回格式错误")
        val translated = buildMap {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val text = item.optString("translation").trim()
                if (id.isNotBlank() && text.isNotBlank()) put(id, text)
            }
        }
        log(trace, "AI_PARSE_OK returned=${translated.size} missing=${regions.filter { translated[it.id].isNullOrBlank() }.map { it.id }}")
        return translated
    }

    private suspend fun translateWithAiFallback(regions: List<MangaTextRegion>, trace: String): Map<String, String> = aiTextGate.withLock {
        val identity = listOf(aiIdentity, aiIdentity,
            aiIdentity).joinToString("|")
        val remaining = (aiUnavailableUntil[identity] ?: 0L) - SystemClock.elapsedRealtime()
        if (remaining > 0) {
            log(trace, "AI_SKIP")
            return@withLock emptyMap()
        }
        try {
            val translated = withTimeoutOrNull(AI_TIMEOUT_MS.milliseconds) { translateWithAi(regions, trace) }
                ?: error("AI 翻译超过 ${AI_TIMEOUT_MS / 1000} 秒，切换保底翻译")
            check(translated.isNotEmpty()) { "AI 未返回有效译文，切换保底翻译" }
            aiUnavailableUntil.remove(identity)
            translated
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            aiUnavailableUntil[identity] = SystemClock.elapsedRealtime() + 60_000L
            log(trace, "AI_COOLDOWN")
            throw error
        }
    }

    private fun compose(
        source: Bitmap,
        regions: List<MangaTextRegion>,
        translations: Map<String, String>,
        trace: String
    ): MangaComposition {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
        val canvas = Canvas(output)
        val candidates = regions.mapNotNull { region ->
            val translation = translations[region.id]?.takeIf { it.isNotBlank() } ?: run {
                log(trace, "COMPOSE_KEEP_ORIGINAL id=${region.id}")
                return@mapNotNull null
            }
            val bounds = Rect(
                region.bounds.left.coerceIn(0, output.width),
                region.bounds.top.coerceIn(0, output.height),
                region.bounds.right.coerceIn(0, output.width),
                region.bounds.bottom.coerceIn(0, output.height)
            )
            if (bounds.width() < 2 || bounds.height() < 2) return@mapNotNull null
            MangaTextReplacement(region.id, translation, bounds)
        }
        val selected = mutableListOf<MangaTextReplacement>()
        val representedIds = mutableMapOf<String, MutableSet<String>>()
        val eraseRegions = mutableMapOf<String, MutableList<Rect>>()
        candidates.sortedByDescending { it.bounds.width().toLong() * it.bounds.height() }.forEach { candidate ->
            val existing = selected.firstOrNull { overlapsTextRegion(it.bounds, candidate.bounds) }
            if (existing == null) {
                selected.add(candidate)
                representedIds[candidate.id] = mutableSetOf(candidate.id)
                eraseRegions[candidate.id] = mutableListOf(candidate.bounds)
            } else {
                representedIds.getValue(existing.id).add(candidate.id)
                eraseRegions.getValue(existing.id).add(candidate.bounds)
                log(trace, "COMPOSE_MERGE_OVERLAP id=${candidate.id} into=${existing.id}")
            }
        }
        var prepared = selected.mapNotNull { replacement ->
            val background = surroundingColor(source, replacement.bounds)
            val eraseBounds = Rect(replacement.bounds)
            eraseRegions.getValue(replacement.id).forEach { eraseBounds.union(it) }
            val erasure = prepareErasure(source, eraseBounds, background, trace, replacement.id)
            val layout = erasure?.let {
                fittedLayout(replacement.text, it.bounds, MangaTranslationBackground.textColor(background))
            }
            if (layout == null) {
                log(trace, "COMPOSE_KEEP_ORIGINAL id=${replacement.id} reason=text_does_not_fit")
                null
            } else {
                MangaPreparedText(replacement, checkNotNull(erasure), layout)
            }
        }
        while (true) {
            val availableIds = prepared.flatMap { representedIds.getValue(it.replacement.id) }.toSet()
            val protectedRegions = regions.filter { it.id !in availableIds }
            val safe = prepared.filter { item ->
                val blocked = protectedRegions.any {
                    Rect.intersects(it.bounds, item.replacement.bounds) || Rect.intersects(it.bounds, item.erasure.bounds)
                }
                if (blocked) log(trace, "COMPOSE_KEEP_ORIGINAL id=${item.replacement.id} reason=overlapping_untranslated_region")
                !blocked
            }
            if (safe.size == prepared.size) break
            prepared = safe
        }
        val completedIds = mutableSetOf<String>()
        val replacementIds = prepared.flatMap { representedIds.getValue(it.replacement.id) }.toSet()
        val compositionClip = canvas.save()
        try {
            regions.filter { it.id !in replacementIds }.forEach { region ->
                if (Build.VERSION.SDK_INT >= 26) canvas.clipOutRect(region.bounds)
                else { @Suppress("DEPRECATION") canvas.clipRect(region.bounds, android.graphics.Region.Op.DIFFERENCE) }
            }
            prepared.forEach { item ->
                val erasure = item.erasure
                val patch = erasure.patch
                val paint = Paint().apply { color = erasure.background or 0xff000000.toInt() }
                for (index in erasure.runs.indices step 2) {
                    val start = erasure.runs[index]
                    val left = patch.left + start % patch.width()
                    val top = patch.top + start / patch.width()
                    canvas.drawRect(left.toFloat(), top.toFloat(),
                        (left + erasure.runs[index + 1] - start).toFloat(), top + 1f, paint)
                }
            }
            prepared.forEach { item ->
                val bounds = item.erasure.bounds
                val saved = canvas.save()
                try {
                    canvas.clipRect(bounds)
                    canvas.translate(bounds.left.toFloat(), bounds.top + (bounds.height() - item.layout.height) / 2f)
                    item.layout.draw(canvas)
                } finally {
                    canvas.restoreToCount(saved)
                }
                completedIds.addAll(representedIds.getValue(item.replacement.id))
                log(trace, "COMPOSE_REPLACE id=${item.replacement.id} textSize=${item.layout.paint.textSize} lines=${item.layout.lineCount}")
            }
        } finally {
            canvas.restoreToCount(compositionClip)
        }
        return MangaComposition(output, completedIds)
        } catch (e: Throwable) { output.recycle(); throw e }
    }

    @SuppressLint("CheckResult")
    private fun prepareErasure(source: Bitmap, bounds: Rect, background: Int, trace: String, id: String): MangaTextErasure? {
        val padding = (min(bounds.width(), bounds.height()) * 0.12f).toInt().coerceIn(4, 12)
        val search = max(24, padding * 4)
        val patch = Rect(bounds)
        patch.inset(-search, -search)
        patch.intersect(0, 0, source.width, source.height)
        if (patch.width().toLong() * patch.height() > 1_000_000L) {
            log(trace, "COMPOSE_KEEP_ORIGINAL id=$id reason=region_too_large")
            return null
        }
        val pixels = IntArray(patch.width() * patch.height())
        source.getPixels(pixels, 0, patch.width(), patch.left, patch.top, patch.width(), patch.height())
        val text = MangaTextEraser.Box(bounds.left - patch.left, bounds.top - patch.top,
            bounds.right - patch.left, bounds.bottom - patch.top)
        val overlay = MangaTextEraser.overlay(pixels, patch.width(), patch.height(), text, background, padding)
        val erased = overlay.count { it ushr 24 > 0 }
        val safe = MangaTextEraser.layoutBounds(pixels, overlay, patch.width(), patch.height(), text, background, padding)
            ?: return null
        val layoutBounds = Rect(safe.left + patch.left, safe.top + patch.top,
            safe.right + patch.left, safe.bottom + patch.top)
        log(trace, "COMPOSE_ERASE id=$id bounds=$bounds layout=$layoutBounds search=$search pixels=$erased mode=bubble_mask")
        return MangaTextErasure(patch, MangaTextEraser.runs(overlay, patch.width()), layoutBounds, background)
    }

    private fun overlapsTextRegion(first: Rect, second: Rect): Boolean {
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return false
        val smaller = min(first.width().toLong() * first.height(), second.width().toLong() * second.height())
        return intersection.width().toLong() * intersection.height() >= smaller * 0.85
    }

    private fun fittedLayout(text: String, bounds: Rect, color: Int): StaticLayout? {
        fun create(size: Float): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            if (Build.VERSION.SDK_INT < 23) {
                @Suppress("DEPRECATION")
                return StaticLayout(text, paint, bounds.width(), Layout.Alignment.ALIGN_CENTER, 1f, 0f, true)
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, bounds.width())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(true)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .build()
        }
        fun fits(layout: StaticLayout) = layout.height <= bounds.height() &&
            (0 until layout.lineCount).all { layout.getLineWidth(it) <= bounds.width() + 0.5f }
        var low = 1f
        var high = min(bounds.width(), bounds.height()).toFloat().coerceAtLeast(low)
        var best = create(low)
        if (!fits(best)) return null
        repeat(14) {
            val size = (low + high) / 2
            val layout = create(size)
            if (fits(layout)) {
                best = layout
                low = size
            } else {
                high = size
            }
        }
        return best
    }

    private fun surroundingColor(bitmap: Bitmap, bounds: Rect): Int {
        val samples = ArrayList<Int>()
        val stepX = max(1, bounds.width() / 64)
        val stepY = max(1, bounds.height() / 64)
        for (distance in listOf(2, 4, 6)) {
            for (x in bounds.left until bounds.right step stepX) {
                if (bounds.top >= distance) samples.add(bitmap[x, bounds.top - distance])
                if (bounds.bottom - 1 + distance < bitmap.height) samples.add(bitmap[x, bounds.bottom - 1 + distance])
            }
            for (y in bounds.top until bounds.bottom step stepY) {
                if (bounds.left >= distance) samples.add(bitmap[bounds.left - distance, y])
                if (bounds.right - 1 + distance < bitmap.width) samples.add(bitmap[bounds.right - 1 + distance, y])
            }
        }
        if (samples.isEmpty()) {
            for (y in bounds.top until bounds.bottom step stepY) {
                for (x in bounds.left until bounds.right step stepX) samples.add(bitmap[x, y])
            }
        }
        return MangaTranslationBackground.estimate(samples.toIntArray())
    }

    private fun taskKey(request: TranslationRequest): String = digest(request.toString() + settings.toString() + aiIdentity)

    private fun cleanJson(raw: String): String {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    private fun log(trace: String, event: String, error: Throwable? = null) {
        if (event == "FAILED" && error != null) {
            host.log.log("manga-translation", "[$trace] $event", error)
        }
    }

    private fun preview(text: String, limit: Int = 500): String = text
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .let { if (it.length > limit) it.take(limit) + "…" else it }

    private fun sha256(file: File): String = file.inputStream().use { input -> val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(DEFAULT_BUFFER_SIZE); while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }; hash.digest().joinToString("") { "%02x".format(it) } }

    private fun sha256(value: String): String = sha256(value.toByteArray())

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class MangaTextRegion(val id: String, val text: String, val bounds: Rect)

    private data class MangaTextReplacement(val id: String, val text: String, val bounds: Rect)

    private data class MangaTextErasure(val patch: Rect, val runs: IntArray, val bounds: Rect, val background: Int)

    private data class MangaPreparedText(val replacement: MangaTextReplacement, val erasure: MangaTextErasure, val layout: StaticLayout)

    private data class MangaComposition(val bitmap: Bitmap, val completedIds: Set<String>)

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel(CancellationException("任务已取消")) }
        continuation.invokeOnCancellation { }
    }
}
