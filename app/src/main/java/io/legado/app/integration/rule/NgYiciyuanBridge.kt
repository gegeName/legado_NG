package io.legado.app.integration.rule

import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.integration.FeatureBootstrap
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.constant.AppLog
import io.legado.features.api.rule.FetchResult
import io.legado.features.api.rule.RuleCompatibilityPolicy
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

/** NG types and runtime execution only. Compatibility algorithms live in lib. */
object NgYiciyuanBridge {
    private val features get() = FeatureBootstrap.features
    fun isAdapterSource(source: BaseSource?): Boolean = features.isYiciyuan((source as? BookSource)?.bookSourceComment)
    fun policy(source: BaseSource?): RuleCompatibilityPolicy = features.rulePolicy((source as? BookSource)?.bookSourceComment)

    fun normalizeChapterUrlRule(source: BookSource, rule: String?): String? =
        if (isAdapterSource(source)) features.normalizeChapterUrlRule(rule, source.bookSourceUrl) else rule

    fun bookUrl(source: BookSource, url: String, item: Any?, baseUrl: String): String =
        if (isAdapterSource(source)) features.resolveBookUrl(url, item, baseUrl) else url

    fun isEmptyListResponse(source: BookSource, body: String): Boolean =
        isAdapterSource(source) && features.isExplicitEmptyListResponse(body)

    fun rejectBookUrl(source: BookSource, url: String, baseUrl: String): Boolean =
        isAdapterSource(source) && features.isInvalidBookUrl(url, baseUrl)

    fun chapterUrl(source: BookSource, url: String): String =
        if (isAdapterSource(source) && features.isInvalidChapterUrl(url)) "" else url

    fun fetch(url: Any, options: Any?, source: BaseSource?, context: CoroutineContext): Any =
        features.fetch(url, options) { request ->
            val response = AnalyzeUrl(request, source = source, coroutineContext = context).getStrResponse()
            FetchResult(response.url, response.body.orEmpty(), response.code())
        }

    fun decodeImage(source: BookSource, script: String, input: Any, src: String, book: Book?): Any? {
        return try {
            val clean = features.prepareImageRule(script, source.bookSourceUrl)
            val raw = source.evalJS(clean) {
                put("book", book)
                put("result", input)
                put("src", src)
            }
            RhinoScriptEngine.unwrapReturnValue(raw)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.putDebug("${src}解密错误", error)
            null
        }
    }

    fun decodeImageBytes(source: BookSource, script: String, input: ByteArray, src: String, book: Book?): ByteArray? =
        when (val result = decodeImage(source, script, input, src, book)) {
            is ByteArray -> result
            is String -> result.toByteArray()
            else -> null
        }

    fun decodeImageStream(source: BookSource, script: String, input: InputStream, src: String, book: Book?): InputStream? =
        when (val result = decodeImage(source, script, input, src, book)) {
            is InputStream -> result
            is ByteArray -> ByteArrayInputStream(result)
            is String -> ByteArrayInputStream(result.toByteArray())
            else -> null
        }
}
