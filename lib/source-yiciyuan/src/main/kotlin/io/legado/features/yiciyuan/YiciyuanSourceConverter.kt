package io.legado.features.yiciyuan

import cn.hutool.core.codec.Base64
import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.symmetric.SymmetricCrypto
import com.jayway.jsonpath.DocumentContext
import io.legado.features.api.source.CompatibilityStamp
import io.legado.features.api.source.ConvertedSourceDefinition
import io.legado.features.api.rule.RuleFetchPort
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.htmlunit.corejs.javascript.NativeArray
import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.Wrapper
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.RSAPrivateKeySpec

/**
 * 异次元（Yiciyuan / Ycy Comic Source）书源格式专用适配器。
 * 自动识别异次元书源 JSON 结构（ruleSearchUrl, ruleFindUrl, ruleChapterList 等字段），
 * 并转换生成 Legado 3.0 标准原生 source interchange JSON。
 */
object YiciyuanSourceConverter {

    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024

    const val ADAPTER_ID = "yiciyuan"

    private val jsPattern = Pattern.compile("\\{\\{.+?\\}\\}", Pattern.CASE_INSENSITIVE)
    private val legadoFormatFields = setOf(
        "searchUrl",
        "exploreUrl",
        "ruleSearch",
        "ruleExplore",
        "ruleBookInfo",
        "ruleToc",
        "ruleContent"
    )
    private val yiciyuanUrlRuleFields = setOf(
        "ruleSearchUrl",
        "ruleFindUrl",
        "ruleContentUrl"
    )
    private val yiciyuanExtractRuleFields = setOf(
        "ruleSearchList",
        "ruleSearchName",
        "ruleSearchNoteUrl",
        "ruleBookName",
        "ruleChapterList",
        "ruleChapterName",
        "ruleChapterUrl",
        "ruleBookContent"
    )
    private val yiciyuanSpecificFields = setOf(
        "sourceRemark",
        "ruleBookContentWeb",
        "ruleBookContentWebJs",
        "ruleChapterId",
        "ruleChapterParentId",
        "ruleChapterParentName",
        "ruleSearchCoverDecoder",
        "ruleCoverDecoder",
        "serialNumber"
    )
    private val yiciyuanOldRuleFields = yiciyuanUrlRuleFields + yiciyuanExtractRuleFields

    const val FORMAT_VERSION = 1
    const val COMMENT_MARKER = "[legado-features:yiciyuan:1]"

    fun isAdapterComment(comment: String?): Boolean = comment?.startsWith(COMMENT_MARKER) == true

    /**
     * 异次元脚本的 java.fetch 实现。
     * JsExtensions 只保留 API 桥接，避免把异次元参数和响应兼容逻辑放入通用扩展中。
     */
    fun fetch(url: Any, options: Any?, port: RuleFetchPort): FetchResponse {
        val urlStr = if (url is List<*>) url.firstOrNull().toString() else url.toString()
        val converted = nativeObjectToValue(options)
        val request = if (converted != null && !urlStr.contains(",{" ) && !urlStr.contains(", {")) {
            "$urlStr, ${GSON.toJson(converted)}"
        } else urlStr
        // Host errors and cancellation propagate. A failed request is not a successful empty page.
        val result = port.fetch(request)
        return FetchResponse(result.url, result.body, result.statusCode)
    }

    fun decryptESBytes(
        mode: String,
        bm: String?,
        data: Any?,
        key: String,
        iv: String
    ): ByteArray? = decryptYiciyuanBytes(mode, data, key, iv)

    fun decryptAESBytes(
        mode: String,
        bm: String?,
        data: Any?,
        key: String,
        iv: String
    ): ByteArray? = decryptYiciyuanBytes(mode, data, key, iv)

    fun decryptES(
        mode: String,
        bm: String?,
        data: Any?,
        key: String,
        iv: String
    ): String? = decryptYiciyuanString(mode, data, key, iv)

    fun decryptAES(
        mode: String,
        bm: String?,
        data: Any?,
        key: String,
        iv: String
    ): String? = decryptYiciyuanString(mode, data, key, iv)

    private fun decryptYiciyuanBytes(
        mode: String,
        data: Any?,
        key: String,
        iv: String
    ): ByteArray? {
        if (data == null) return null
        return createYiciyuanCrypto(mode, key, iv).decryptData(data)
    }

    private fun decryptYiciyuanString(
        mode: String,
        data: Any?,
        key: String,
        iv: String
    ): String? {
        if (data == null) return null
        return createYiciyuanCrypto(mode, key, iv).decryptDataStr(data)
    }

    private fun createYiciyuanCrypto(mode: String, key: String, iv: String): SymmetricCrypto {
        val crypto = CompatibilityCrypto(mode, key.encodeToByteArray())
        return if (iv.isNotEmpty()) crypto.setIv(iv.encodeToByteArray()) else crypto
    }

    private fun SymmetricCrypto.decryptData(data: Any): ByteArray? {
        return when (data) {
            is ByteArray -> decrypt(data)
            is InputStream -> decrypt(data)
            is String -> decrypt(data)
            else -> decrypt(data.toString())
        }
    }

    private fun SymmetricCrypto.decryptDataStr(data: Any): String? {
        return when (data) {
            is ByteArray -> decryptStr(data)
            is InputStream -> String(decrypt(data), Charsets.UTF_8)
            is String -> decryptStr(data)
            else -> decryptStr(data.toString())
        }
    }

    class FetchResponse(
        val url: String,
        val bodyStr: String,
        val statusCode: Int = 200
    ) {
        val body: String get() = bodyStr
        fun body(): FetchBody = FetchBody(bodyStr)
        fun text(): String = bodyStr
        fun string(): String = bodyStr
        fun code(): Int = statusCode
        fun status(): Int = statusCode
        override fun toString(): String = bodyStr
    }

    class FetchBody(val content: String) {
        fun string(): String = content
        fun text(): String = content
        override fun toString(): String = content
    }

    private fun nativeObjectToValue(value: Any?): Any? {
        return when (value) {
            null, is Undefined -> null
            is NativeObject -> value.ids.associate { id ->
                id.toString() to nativeObjectToValue(value.get(id))
            }
            is NativeArray -> (0 until value.length.toInt()).map { index ->
                nativeObjectToValue(value.get(index, value))
            }
            is Wrapper -> nativeObjectToValue(value.unwrap())
            is Map<*, *> -> {
                val map = LinkedHashMap<String, Any?>()
                for ((key, item) in value) {
                    if (key != null) map[key.toString()] = nativeObjectToValue(item)
                }
                map
            }
            is List<*> -> value.map(::nativeObjectToValue)
            else -> value
        }
    }

    fun decompressSource(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= 20) return text
        val decompressed = runCatching<String?> {
            val bytes = Base64.decode(trimmed)
            val zlibHeader = if (bytes.size >= 2) {
                ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            } else {
                -1
            }
            if (bytes.firstOrNull() != 0x78.toByte() || zlibHeader % 31 != 0) {
                return@runCatching null
            }
            val inflater = Inflater()
            try {
                inflater.setInput(bytes)
                val output = ByteArrayOutputStream(minOf(bytes.size, 8192))
                val buffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count <= 0) return@runCatching null
                    require(output.size() + count <= MAX_SOURCE_BYTES) { "压缩书源解压后超过 8 MiB" }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            } finally {
                inflater.end()
            }
        }.getOrElse { error ->
            if (error is IllegalArgumentException && error.message?.contains("8 MiB") == true) throw error
            null
        }
        return decompressed ?: text
    }

    /** 异次元接口偶尔将 JSON 放在 HTML 的 pre 标签中。 */
    fun unwrapHtmlJson(content: Any): Any {
        if (content !is String) return content
        val trimmed = content.trim()
        if (!trimmed.startsWith("<") && !trimmed.contains("<pre", true)) return content
        if (trimmed.isJson()) return trimmed
        extractPreJson(trimmed)?.let {
            return it
        }
        extractJsonObject(trimmed)?.let {
            return it
        }
        return content
    }

    private fun extractPreJson(html: String): String? {
        val start = html.indexOf("<pre", ignoreCase = true)
        if (start < 0) return null
        val openEnd = html.indexOf('>', start)
        if (openEnd < 0) return null
        val end = html.indexOf("</pre>", openEnd, ignoreCase = true)
        if (end < 0) return null
        return html.substring(openEnd + 1, end).trim().takeIf { it.isJson() }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1).trim().takeIf { it.isJson() }
    }

    /** 将异次元漫画正文返回的纯图片地址转换为 Legado 可渲染的 img 标签。 */
    fun formatImageContent(content: String): String {
        if (content.isBlank()) return content
        return content.lineSequence().joinToString("\n") { line ->
            val imageUrl = line.trim()
            val optionIndex = imageUrl.indexOf(",{")
            val rawUrl = if (optionIndex >= 0) imageUrl.substring(0, optionIndex) else imageUrl
            val hasValidOption = optionIndex < 0 || imageUrl.substring(optionIndex + 1).isJson()
            if (hasValidOption && rawUrl.matches(Regex("^(https?://|//|data:image/)[^\\s<>\"']+$", RegexOption.IGNORE_CASE))) {
                val attribute = imageUrl.replace("&", "&amp;").replace("\"", "&quot;")
                    .replace("<", "&lt;").replace(">", "&gt;")
                "<img src=\"$attribute\">"
            } else {
                line
            }
        }
    }

    fun normalizeRuleResult(result: Any?): List<String> {
        return when (result) {
            is NativeArray -> (0 until result.length.toInt()).mapNotNull { index ->
                extractRuleValue(result.get(index, result))
            }
            is List<*> -> result.mapNotNull(::extractRuleValue)
            else -> listOfNotNull(extractRuleValue(result))
        }
    }

    fun formatRuleResult(result: Any?): String {
        if (result == null || result is Undefined) return ""
        var value = result
        if (value is Wrapper) value = value.unwrap()
        return when (value) {
            is String, is CharSequence -> value.toString()
            is NativeArray, is List<*> -> normalizeRuleResult(value).joinToString("\n")
            is NativeObject -> extractRuleValue(value).orEmpty()
            is Scriptable -> normalizeJsValue(value) ?: value.toString()
            else -> value.toString()
        }
    }

    fun isExplicitEmptyListResponse(body: String): Boolean {
        val text = body.trim()
        if (!text.startsWith("{") && !text.startsWith("[")) return false
        return Regex(
            """"(?:message|msg|error)"\s*:\s*"[^"]*(?:空|没有|沒有|无结果|無結果|empty|no\s+results?|not\s+found)[^"]*"""",
            setOf(RegexOption.IGNORE_CASE)
        ).containsMatchIn(text)
    }

    fun fallbackBookUrlFromJsonItem(item: Any?, baseUrl: String): String? {
        val directUrl = firstNestedString(
            item,
            listOf(
                listOf("bookUrl"),
                listOf("book_url"),
                listOf("url"),
                listOf("href"),
                listOf("link"),
                listOf("detailUrl"),
                listOf("detail_url"),
                listOf("comic", "bookUrl"),
                listOf("comic", "book_url"),
                listOf("comic", "url"),
                listOf("comic", "href"),
                listOf("comic", "link"),
                listOf("comic", "detailUrl"),
                listOf("comic", "detail_url")
            )
        )
        if (!directUrl.isNullOrBlank() &&
            (directUrl.startsWith("http", true) || directUrl.startsWith("/") || directUrl.contains("/"))
        ) {
            val absoluteUrl = absoluteUrl(baseUrl, directUrl)
            if (!isInvalidBookUrl(absoluteUrl, baseUrl)) {
                return absoluteUrl
            }
        }

        val pathWord = firstNestedString(
            item,
            listOf(
                listOf("comic", "path_word"),
                listOf("path_word"),
                listOf("comic", "pathWord"),
                listOf("pathWord"),
                listOf("comic", "slug"),
                listOf("slug")
            )
        )?.takeIf { it.isNotBlank() } ?: return null

        return absoluteUrl(frontSiteBaseUrl(baseUrl), "/comic/$pathWord")
    }

    fun repairBookUrlFromJsonItem(url: String?, item: Any?, baseUrl: String): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank() || !Regex("""/0(?=[/?#]|$)""").containsMatchIn(value)) {
            return null
        }
        val itemId = firstNestedString(
            item,
            listOf(
                listOf("comic_id"),
                listOf("comicId"),
                listOf("book_id"),
                listOf("bookId"),
                listOf("manga_id"),
                listOf("mangaId"),
                listOf("chapter_id"),
                listOf("chapterId"),
                listOf("id"),
                listOf("comic", "comic_id"),
                listOf("comic", "comicId"),
                listOf("comic", "id")
            )
        )?.takeIf { it.isNotBlank() && it != "0" } ?: return null
        val fixed = value.replace(Regex("""/0(?=[/?#]|$)"""), "/$itemId")
        return absoluteUrl(baseUrl, fixed)
    }

    fun isInvalidBookUrl(url: String?, baseUrl: String): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return true
        if (value == baseUrl) return true
        if (value.contains("/comic/update/list/", true)) return true
        if (value.contains("/comic/rank/list", true)) return true
        if (value.contains("/search/index", true)) return true
        return false
    }

    private fun firstNestedString(item: Any?, paths: List<List<String>>): String? {
        for (path in paths) {
            nestedValue(item, path)?.toString()?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        return null
    }

    private fun nestedValue(item: Any?, path: List<String>): Any? {
        var current = if (item is Wrapper) item.unwrap() else item
        for (key in path) {
            val value = if (current is Wrapper) current.unwrap() else current
            current = when (value) {
                is NativeObject -> if (value.containsKey(key)) value.get(key, value) else return null
                is Map<*, *> -> value[key] ?: return null
                else -> return null
            }
            if (current == null || current is Undefined) return null
        }
        return if (current is Wrapper) current.unwrap() else current
    }

    private fun frontSiteBaseUrl(baseUrl: String): String {
        return runCatching {
            val url = java.net.URL(baseUrl.substringBefore(",").trim())
            var host = url.host
            if (host.startsWith("mapi.", true)) {
                host = host.substringAfter(".")
            } else if (host.startsWith("api.", true)) {
                host = host.substringAfter(".")
            }
            "${url.protocol}://$host"
        }.getOrDefault(baseUrl)
    }

    private fun extractRuleValue(item: Any?): String? {
        if (item == null || item is Undefined) return null
        var value = item
        if (value is Wrapper) value = value.unwrap()
        if (value is NativeObject) {
            for (key in arrayOf("url", "src", "href", "link", "image", "img", "path", "file")) {
                if (value.containsKey(key)) {
                    val property = value.get(key, value)
                    if (property != null && property !is Undefined) return property.toString()
                }
            }
            if (value.ids.size == 1) {
                val id = value.ids[0]
                val property = when (id) {
                    is Int -> value.get(id, value)
                    is String -> value.get(id, value)
                    else -> null
                }
                if (property != null && property !is Undefined) return property.toString()
            }
            return normalizeJsValue(value)
        }
        if (value is Map<*, *>) {
            for (key in arrayOf("url", "src", "href", "link", "image", "img", "path", "file")) {
                value[key]?.let { return it.toString() }
            }
            return GSON.toJson(value)
        }
        return value.toString()
    }

    /**
     * 判断给定 JSON 字符串是否为异次元书源格式
     */
    fun isYiciyuanSource(jsonText: String): Boolean {
        val text = decompressSource(jsonText).trim()
        if (text.isBlank()) return false
        val items = runCatching {
            when (val root = jsonPath.parse(text).read<Any>("$")) {
                is Map<*, *> -> listOf(root)
                is List<*> -> root.takeIf { list -> list.all { it is Map<*, *> } }
                    ?.filterIsInstance<Map<*, *>>()
                    .orEmpty()
                else -> emptyList()
            }
        }.getOrNull() ?: return false

        if (items.isEmpty()) return false
        if (items.any { it.hasLegadoFormatField() }) return false
        val matchedCount = items.count { it.isLikelyYiciyuanSourceItem() }
        return matchedCount * 5 >= items.size * 4
    }

    private fun Map<*, *>.hasLegadoFormatField(): Boolean =
        keys.filterIsInstance<String>().any(legadoFormatFields::contains)

    private fun Map<*, *>.hasMeaningfulField(field: String): Boolean =
        get(field)?.toString()?.isNotBlank() == true

    private fun Map<*, *>.isLikelyYiciyuanSourceItem(): Boolean {
        val fields = keys.filterIsInstance<String>()
        if (fields.size != size) return false
        if (!hasMeaningfulField("bookSourceUrl")) return false
        val oldRuleFieldCount = yiciyuanOldRuleFields.count { hasMeaningfulField(it) }
        val specificFieldCount = yiciyuanSpecificFields.count { hasMeaningfulField(it) }
        val hasUrlRule = yiciyuanUrlRuleFields.any { hasMeaningfulField(it) }
        val hasExtractRule = yiciyuanExtractRuleFields.any { hasMeaningfulField(it) }
        return oldRuleFieldCount >= 2 ||
            hasUrlRule && hasExtractRule ||
            oldRuleFieldCount >= 1 && specificFieldCount >= 1
    }

    /**
     * 将异次元 JSON（单条或多条数组）批量转换为 Legado 3.0 原生 source interchange JSON 列表
     */
    fun convert(jsonText: String): List<ConvertedSourceDefinition> {
        val root = jsonPath.parse(decompressSource(jsonText)).read<Any>("$")
        val items = when (root) {
            is Map<*, *> -> listOf(root)
            is List<*> -> root.map { require(it is Map<*, *>) { "源列表包含非对象记录" }; it }
            else -> error("异次元源必须是对象或列表")
        }
        return items.map { convertSingle(jsonPath.parse(it)) }
    }

    fun convertSingle(doc: DocumentContext): ConvertedSourceDefinition {
        val source = linkedMapOf<String, Any?>()
        return source.apply {
            val bookSourceUrl = doc.readOptionalString("bookSourceUrl")?.takeIf { it.isNotBlank() }
                ?: error("异次元书源地址为空")
            this["bookSourceUrl"] = bookSourceUrl
            this["bookSourceName"] = doc.readOptionalString("bookSourceName").orEmpty()
            this["bookSourceGroup"] = doc.readOptionalString("bookSourceGroup")
            this["loginUrl"] = doc.readOptionalString("loginUrl")
            this["loginUi"] = doc.readOptionalString("loginUi")
            this["loginCheckJs"] = doc.readOptionalString("loginCheckJs")
            this["coverDecodeJs"] = doc.readNonBlankString("ruleSearchCoverDecoder")
                ?: doc.readNonBlankString("ruleCoverDecoder")
                ?: doc.readNonBlankString("coverDecodeJs")
            this["bookSourceComment"] = doc.readOptionalString("sourceRemark")
                ?: doc.readOptionalString("bookSourceComment").orEmpty()
            this["bookUrlPattern"] = doc.readOptionalString("ruleBookUrlPattern")
            this["customOrder"] = doc.readInt("serialNumber") ?: 0

            val ua = doc.readOptionalString("httpUserAgent")
            if (!ua.isNullOrBlank()) {
                this["header"] = GSON.toJson(mapOf("User-Agent" to ua))
            }

            this["searchUrl"] = toNewUrl(doc.readOptionalString("ruleSearchUrl"))
            this["exploreUrl"] = toNewUrls(doc.readOptionalString("ruleFindUrl"))
            if ((this["exploreUrl"] as? String).isNullOrBlank()) {
                this["enabledExplore"] = false
            }

            val typeVal = doc.readOptionalString("bookSourceType")
            val bookSourceType = when (typeVal) {
                "AUDIO", "1" -> 1
                "0", "TEXT" -> 0
                "漫画", "IMAGE", "2", "COMIC", "MANGA" -> 2
                else -> 2
            }
            this["bookSourceType"] = bookSourceType
            this["enabled"] = doc.readBool("enable") ?: true

            val searchList = toNewRule(doc.readNonBlankString("ruleSearchList"), "class.mh-list@tag.li||.comic-list li||.search-result-list li", bookSourceUrl)
            val searchName = toNewRule(doc.readNonBlankString("ruleSearchName"), "class.title@tag.a@text||.title a@text||h2 a@text", bookSourceUrl)
            val searchAuthor = toNewRule(doc.readNonBlankString("ruleSearchAuthor"), bookSourceUrl = bookSourceUrl)
            val searchIntro = toNewRule(doc.readNonBlankString("ruleSearchIntroduce"), bookSourceUrl = bookSourceUrl)
            val searchKind = toNewRule(doc.readNonBlankString("ruleSearchKind"), bookSourceUrl = bookSourceUrl)
            val searchNoteUrl = toNewRule(doc.readNonBlankString("ruleSearchNoteUrl"), "a@href", bookSourceUrl)
            val searchCoverUrl = toNewRule(doc.readNonBlankString("ruleSearchCoverUrl"), "img@src", bookSourceUrl)
            val searchLastChapter = toNewRule(doc.readNonBlankString("ruleSearchLastChapter"), bookSourceUrl = bookSourceUrl)

            this["ruleSearch"] = linkedMapOf(
                "bookList" to searchList,
                "name" to searchName,
                "author" to searchAuthor,
                "intro" to searchIntro,
                "kind" to searchKind,
                "bookUrl" to searchNoteUrl,
                "coverUrl" to searchCoverUrl,
                "lastChapter" to searchLastChapter
            )
            this["ruleExplore"] = linkedMapOf(
                "bookList" to (toNewRule(doc.readNonBlankString("ruleFindList"), bookSourceUrl = bookSourceUrl) ?: searchList),
                "name" to (toNewRule(doc.readNonBlankString("ruleFindName"), bookSourceUrl = bookSourceUrl) ?: searchName),
                "author" to (toNewRule(doc.readNonBlankString("ruleFindAuthor"), bookSourceUrl = bookSourceUrl) ?: searchAuthor),
                "intro" to (toNewRule(doc.readNonBlankString("ruleFindIntroduce"), bookSourceUrl = bookSourceUrl) ?: searchIntro),
                "kind" to (toNewRule(doc.readNonBlankString("ruleFindKind"), bookSourceUrl = bookSourceUrl) ?: searchKind),
                "bookUrl" to (toNewRule(doc.readNonBlankString("ruleFindNoteUrl"), bookSourceUrl = bookSourceUrl) ?: searchNoteUrl),
                "coverUrl" to (toNewRule(doc.readNonBlankString("ruleFindCoverUrl"), bookSourceUrl = bookSourceUrl) ?: searchCoverUrl),
                "lastChapter" to (toNewRule(doc.readNonBlankString("ruleFindLastChapter"), bookSourceUrl = bookSourceUrl) ?: searchLastChapter)
            )
            this["ruleBookInfo"] = linkedMapOf(
                "init" to toNewRule(doc.readNonBlankString("ruleBookInfoInit"), bookSourceUrl = bookSourceUrl),
                "name" to (toNewRule(doc.readNonBlankString("ruleBookName"), bookSourceUrl = bookSourceUrl) ?: searchName),
                "author" to (toNewRule(doc.readNonBlankString("ruleBookAuthor"), bookSourceUrl = bookSourceUrl) ?: searchAuthor),
                "intro" to (toNewRule(doc.readNonBlankString("ruleIntroduce"), bookSourceUrl = bookSourceUrl) ?: searchIntro),
                "kind" to (toNewRule(doc.readNonBlankString("ruleBookKind"), bookSourceUrl = bookSourceUrl) ?: searchKind),
                "coverUrl" to (toNewRule(doc.readNonBlankString("ruleCoverUrl"), bookSourceUrl = bookSourceUrl) ?: searchCoverUrl),
                "lastChapter" to toNewRule(doc.readNonBlankString("ruleBookLastChapter"), bookSourceUrl = bookSourceUrl),
                "tocUrl" to toNewRule(doc.readNonBlankString("ruleChapterUrl"), bookSourceUrl = bookSourceUrl)
            )
            val rawChapterList = doc.readNonBlankString("ruleChapterList")
                ?: doc.readNonBlankString("ruleTocList")
                ?: doc.readNonBlankString("ruleChapter")
                ?: doc.readNonBlankString("ruleChapterListWeb")

            val rawChapterName = doc.readNonBlankString("ruleChapterName")
                ?: doc.readNonBlankString("ruleChapterTitle")
                ?: doc.readNonBlankString("ruleTitle")

            val rawContentUrl = doc.readNonBlankString("ruleContentUrl")
                ?: doc.readNonBlankString("ruleChapterUrl")
                ?: doc.readNonBlankString("ruleChapterLink")
                ?: doc.readNonBlankString("ruleTocUrl")

            val isJsOrJsonChapterList = (rawChapterList ?: "").let {
                it.startsWith("@js:", true) || it.startsWith("<js>", true) || it.startsWith("$.") || it.startsWith("$[")
            } || (rawChapterName ?: "").let {
                it.startsWith("$.") || it.startsWith("$[") || it.contains("$.")
            } || (rawContentUrl ?: "").let {
                it.contains("{$.") || it.contains("$.")
            }

            val defaultTocUrlFallback = if (isJsOrJsonChapterList) {
                "$.url||$.href||$.path||a@href||href"
            } else {
                "a@href||href"
            }

            val defaultChapterListFallback = if (isJsOrJsonChapterList) {
                "$.data.comic_chapter||$.data.chapters||$.data.list||$.data.data||$.data.chapterList||$.data[*]||class.detail-list-form-item||#chapterlistload a||.detail-list-form-con a||.comic-chapter-list a||ul.chapter-list li a||.chapter-list li a||li a"
            } else {
                "class.detail-list-form-item||#chapterlistload a||.detail-list-form-con a||.comic-chapter-list a||ul.chapter-list li a||.chapter-list li a||li a"
            }

            this["ruleToc"] = linkedMapOf(
                "chapterList" to toNewRule(
                    rawChapterList,
                    fallback = defaultChapterListFallback,
                    bookSourceUrl = bookSourceUrl
                ),
                "chapterName" to toNewRule(
                    rawChapterName,
                    fallback = if (isJsOrJsonChapterList) "$.title||$.name||$.chapter_name" else null,
                    bookSourceUrl = bookSourceUrl
                ),
                "chapterUrl" to toNewRule(
                    rawContentUrl,
                    fallback = defaultTocUrlFallback,
                    bookSourceUrl = bookSourceUrl,
                    convertVarGet = true
                ),
                "nextTocUrl" to toNewRule(
                    doc.readNonBlankString("ruleChapterUrlNext")
                        ?: doc.readNonBlankString("ruleNextTocUrl")
                        ?: doc.readNonBlankString("ruleTocUrlNext"),
                    bookSourceUrl = bookSourceUrl
                )
            )
            val rawChapterContent = doc.readNonBlankString("ruleBookContent")
                ?: doc.readNonBlankString("ruleContent")
                ?: doc.readNonBlankString("ruleBookContentUrl")
            val defaultContentFallback = if (isJsOrJsonChapterList) {
                "$.data.current_chapter.chapter_img_list[*]||$.data.current_chapter.chapter_img_list||$.data.image_list[*]||$.data.image_list||$.data.chapter_img_list[*]||$.data.images[*]||$.data.list[*]||$.data.urls[*]||img#cp_image@src||#cp_img img@src||.reader-img-con img@src||.comic-page img@src||img@src"
            } else {
                "img#cp_image@src||#cp_img img@src||.reader-img-con img@src||.comic-page img@src||img@src"
            }

            val (contentRule, contentHeaders) = splitContentHeaders(rawChapterContent)
            var content = toNewRule(
                contentRule,
                fallback = defaultContentFallback,
                bookSourceUrl = bookSourceUrl
            ).orEmpty()
            content = fixContentRule(content)
            content = appendContentHeaders(content, contentHeaders)

            val rawWebJs = doc.readNonBlankString("ruleBookContentWeb")
                ?: doc.readNonBlankString("ruleBookContentWebJs")
                ?: doc.readNonBlankString("webJs")
                ?: if (bookSourceType == 2 || rawChapterContent?.startsWith(":") == true || rawChapterContent?.startsWith("$") == true) "document.documentElement.outerHTML" else null

            val rawNextContent = doc.readNonBlankString("ruleContentUrlNext")
                ?: doc.readNonBlankString("ruleBookContentUrlNext")

            val rawContentDecoder = doc.readNonBlankString("ruleBookContentDecoder")

            val validSourceRegex = if (rawContentDecoder?.startsWith("@js:", true) == true ||
                rawContentDecoder?.startsWith("<js>", true) == true ||
                rawContentDecoder?.contains("{") == true ||
                rawContentDecoder?.startsWith(":") == true
            ) {
                null
            } else {
                toNewRule(rawContentDecoder, bookSourceUrl = bookSourceUrl)
            }

            this["ruleContent"] = linkedMapOf(
                "content" to content,
                "replaceRegex" to toNewRule(
                    doc.readOptionalString("ruleBookContentReplace")
                        ?: doc.readOptionalString("ruleContentReplace"),
                    bookSourceUrl = bookSourceUrl
                ),
                "nextContentUrl" to toNewRule(rawNextContent, bookSourceUrl = bookSourceUrl),
                "webJs" to toNewRule(rawWebJs, bookSourceUrl = bookSourceUrl),
                "sourceRegex" to validSourceRegex,
                "imageDecode" to toNewRule(rawContentDecoder, bookSourceUrl = bookSourceUrl)
            )
            this["bookSourceComment"] = COMMENT_MARKER + "\n" + (this["bookSourceComment"] ?: "")
        }.let { ConvertedSourceDefinition(GSON.toJson(it), CompatibilityStamp(ADAPTER_ID, FORMAT_VERSION)) }
    }

    /**
     * 修复异次元与 Legado 3.0 正文规则选择器转换逻辑
     */
    fun fixContentRule(rule: String): String {
        if (rule.isBlank()) return rule
        val trimmedRule = rule.trim()
        if (trimmedRule.startsWith("@js:", true) || trimmedRule.startsWith("<js>", true)) {
            return fixSingleContentRule(trimmedRule)
        }
        if (rule.contains("||")) {
            val rules = rule.split("||").map { fixSingleContentRule(it) }
            // AnalyzeByJSoup 只在规则开头识别一次 @CSS:。后续分支保留此前缀会被
            // 当作 CSS 查询内容传给 Jsoup，导致 "@CSS:#id" 解析异常。
            return rules.mapIndexed { index, item ->
                if (index > 0 && rules.firstOrNull()?.startsWith("@CSS:", true) == true) {
                    if (item.startsWith("@CSS:", true)) item.substring(5) else item
                } else {
                    item
                }
            }.joinToString("||")
        }
        return fixSingleContentRule(rule)
    }

    /** 将异次元 href#正则#替换 目录规则转换为 Legado 属性提取规则。 */
    fun normalizeChapterUrlRule(rule: String?, bookSourceUrl: String? = null): String? {
        if (rule.isNullOrBlank()) return rule
        var result = decryptHRule(rule, bookSourceUrl = bookSourceUrl) ?: rule
        when {
            result.startsWith("href#", true) -> {
                val replaceRule = result.substring(4)
                result = "a@href$replaceRule||href$replaceRule"
            }
            result.startsWith("src#", true) -> {
                val replaceRule = result.substring(3)
                result = "img@src$replaceRule||src$replaceRule"
            }
        }
        result = fixAttributeReplaceRule(result, "a@href")
        result = fixAttributeReplaceRule(result, "href")
        result = fixAttributeReplaceRule(result, "img@src")
        result = fixAttributeReplaceRule(result, "src")
        if (!result.contains("||") && result.startsWith("a@href##", true)) {
            val replaceRule = "##" + result.substring(8)
            result = "a@href$replaceRule||href$replaceRule"
        } else if (!result.contains("||") && result.startsWith("img@src##", true)) {
            val replaceRule = "##" + result.substring(9)
            result = "img@src$replaceRule||src$replaceRule"
        }
        return result
    }

    fun isInvalidChapterUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        return value.equals("href", true) ||
                value.equals("a@href", true) ||
                value.equals("src", true) ||
                value.equals("img@src", true)
    }

    private fun fixAttributeReplaceRule(rule: String, attrRule: String): String {
        val prefix = "$attrRule#"
        val fixedPrefix = "$attrRule##"
        return rule.split("||").joinToString("||") { part ->
            if (part.startsWith(prefix, true) && !part.startsWith(fixedPrefix, true)) {
                attrRule + part.substring(attrRule.length).replace("#", "##")
            } else {
                part
            }
        }
    }

    private fun fixSingleContentRule(rule: String): String {
        if (rule.isBlank()) return rule
        var result = normalizeJsonWildcardRule(normalizeLegacyRulePrefix(rule.trim()))
        result = normalizeLegacyJsRuleStrings(result)

        normalizeYiciyuanContentJs(result)?.let {
            return it
        }

        if (result.startsWith(".") && !result.startsWith("..") && !result.contains("@")) {
            result = "$$result"
        }

        val jsonParseRegex = Regex("""JSON\.parse\(\s*([a-zA-Z0-9_$]+)\s*\)""")
        if (jsonParseRegex.containsMatchIn(result)) {
            result = jsonParseRegex.replace(result) { match ->
                val varName = match.groupValues[1]
                if (varName == "json" && result.contains("b64d(dec)")) {
                    // 已经解码后的内层 Base64 JSON，不需要匹配外层 HTML
                    match.value
                } else {
                    "JSON.parse(($varName.match(/\\{[\\s\\S]*\\}/)||[$varName])[0])"
                }
            }
        }

        if (!result.startsWith("@js:", true) &&
            !result.startsWith("<js>", true) &&
            result.contains("function getImgList", true)
        ) {
            val headerScriptStart = Regex("""\r?\n\s*<js>""", RegexOption.IGNORE_CASE)
                .find(result)
                ?.range
                ?.first
                ?: -1
            val bodyRule = if (headerScriptStart >= 0) {
                result.substring(0, headerScriptStart)
            } else {
                result
            }
            val headerScript = if (headerScriptStart >= 0) {
                result.substring(headerScriptStart)
            } else {
                ""
            }
            val script = bodyRule.trim().removePrefix("$").trim().trimEnd(';')
            val wrappedScript = "${yiciyuanContentJsPrelude()}$script;getImgList();"
            return if (headerScript.isBlank()) {
                "@js:$wrappedScript"
            } else {
                "<js>$wrappedScript</js>$headerScript"
            }
        }

        if (result.startsWith("@js:", true) ||
            result.startsWith("<js>", true) ||
            result.startsWith("@XPath:", true) ||
            result.startsWith("//") ||
            result.startsWith("@json:", true)
        ) {
            return result
        }

        val isCssRule = result.contains("@src", true) ||
                result.contains("@href", true) ||
                result.contains("@text", true) ||
                result.contains("@html", true) ||
                result.contains("@attr", true) ||
                result.contains("@ownText", true) ||
                result.contains("@data-", true) ||
                result.startsWith("@css:", true)

        if (isCssRule) {
            if (result.startsWith("$.")) {
                result = result.substring(1)
            } else if (result.startsWith("$") && !result.startsWith("$.")) {
                result = result.substring(1)
            }
            val isDefaultAttrRule = result.startsWith("class.", true) ||
                    result.startsWith("id.", true) ||
                    result.startsWith("tag.", true) ||
                    result.contains("@tag.", true)
            if (!isDefaultAttrRule && !result.startsWith("@CSS:", true)) {
                result = "@CSS:$result"
            }
            if (result.startsWith("@CSS:", true)) {
                var cssRule = result.substring(5)
                val valueIndex = cssRule.lastIndexOf('@')
                if (valueIndex > 0 && cssRule.substring(0, valueIndex).contains("##")) {
                    result = "@CSS:${cssRule.substring(0, valueIndex).replace("##", "#")}${cssRule.substring(valueIndex)}"
                    cssRule = result.substring(5)
                }
                if (cssRule.lastIndexOf('@') < 0) {
                    result = "$result@src"
                }
            }
        } else {
            if (result.startsWith("$..") || result.startsWith("$.") || result.startsWith("$[")) {
                result = "@json:$result"
            } else if (result.startsWith("$")) {
                // 完全对齐异次元 APK 源码 Ctry.java 第 128 行：将以 $ 开头的正文脚本转换为标准的 Legado JS 执行表达式
                val script = result.substring(1).trim()
                result = if (script.endsWith(";")) {
                    "@js:${yiciyuanContentJsPrelude()}$script getImgList();"
                } else {
                    "@js:${yiciyuanContentJsPrelude()}$script;getImgList();"
                }
            }
        }
        return result
    }

    private fun yiciyuanContentJsPrelude(): String {
        return "var html=result;var JsEngine=java;var window=this;" +
                "function __ycyEmptyElements(){return [];}" +
                "function __ycyElement(){return {style:{},children:[],innerHTML:'',textContent:''," +
                "setAttribute:function(){},appendChild:function(){},removeChild:function(){}," +
                "getElementsByTagName:__ycyEmptyElements,getElementsByClassName:__ycyEmptyElements," +
                "querySelector:function(){return null;},querySelectorAll:__ycyEmptyElements};}" +
                "var document={documentElement:__ycyElement(),body:__ycyElement(),cookie:''," +
                "createElement:function(){return __ycyElement();}," +
                "getElementsByTagName:__ycyEmptyElements,getElementsByClassName:__ycyEmptyElements," +
                "querySelector:function(){return null;},querySelectorAll:__ycyEmptyElements};" +
                "window.document=document;"
    }

    private fun normalizeYiciyuanContentJs(rule: String): String? {
        if (!rule.startsWith("@js:", true) || !rule.contains("eval(result)") || !rule.contains("picAy")) {
            return null
        }
        return """
            @js:(function(){
                var raw = String(result == null ? '' : result);
                var js = raw;
                function collectImages(html) {
                    var area = html.match(/<div[^>]*(?:id=["']PicArea["']|class=["'][^"']*comic_view[^"']*["'])[^>]*>([\s\S]*?)<\/div>/i);
                    var scope = area ? area[1] : html;
                    var out = [];
                    scope.replace(/<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>/gi, function(_, src) {
                        if (/^(https?:)?\/\//i.test(src) || /^data:image\//i.test(src)) {
                            out.push(src);
                        }
                        return _;
                    });
                    return out;
                }
                if (/^\s*</.test(js)) {
                    var htmlImages = collectImages(js);
                    if (htmlImages.length > 0) return htmlImages;
                    var pre = js.match(/<pre[^>]*>([\s\S]*?)<\/pre>/i);
                    if (pre) {
                        js = pre[1];
                    } else {
                        var script = js.match(/<script[^>]*>([\s\S]*?)<\/script>/i);
                        js = script ? script[1] : '';
                    }
                    js = js.replace(/&lt;/g, '<')
                        .replace(/&gt;/g, '>')
                        .replace(/&quot;/g, '"')
                        .replace(/&#39;/g, "'")
                        .replace(/&amp;/g, '&');
                }
                if (js.indexOf('picAy') < 0) return [];
                try {
                    eval(js);
                } catch (e) {
                    return [];
                }
                if (typeof picAy === 'undefined' || !picAy) return [];
                var hostList = (typeof hosts !== 'undefined' && hosts && hosts.length) ? hosts : [''];
                var out = [];
                for (var i = 0; i < picAy.length; i++) {
                    var item = String(picAy[i]);
                    out.push(item.indexOf('http') === 0 || item.indexOf('//') === 0 ? item : hostList[0] + item);
                }
                return out;
            })()
        """.trimIndent()
    }

    /**
     * 异次元将图片请求头附在正文选择器尾部，Legado 则要求将其附在每个图片 URL 上。
     */
    private fun splitContentHeaders(rule: String?): Pair<String?, Map<String, String>> {
        if (rule.isNullOrBlank()) return rule to emptyMap()
        val (ruleWithoutHeaders, headerValues) = stripHeaderBlocks(rule)
        val headers = LinkedHashMap<String, String>()
        headerValues.forEach { headerValue ->
            parseLegacyHeaders(headerValue).forEach { (key, value) ->
                headers[key] = value
            }
        }
        return ruleWithoutHeaders.trim() to headers
    }

    private fun parseLegacyHeaders(value: String): Map<String, String> {
        GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()?.let { parsed ->
            return parsed.mapValues { (_, item) -> item.toString() }
        }
        return value.removePrefix("{").removeSuffix("}")
            .splitTopLevel(',')
            .mapNotNull { entry ->
                val colonIndex = entry.indexOfTopLevel(':')
                val equalsIndex = entry.indexOfTopLevel('=')
                val separator = when {
                    colonIndex <= 0 -> equalsIndex
                    equalsIndex <= 0 -> colonIndex
                    else -> minOf(colonIndex, equalsIndex)
                }
                if (separator <= 0) return@mapNotNull null
                val key = entry.substring(0, separator).trim().trim('"', '\'')
                val headerValue = entry.substring(separator + 1).trim().trim('"', '\'')
                key.takeIf { it.isNotEmpty() }?.let { it to headerValue }
            }
            .toMap()
    }

    private fun stripHeaderBlocks(text: String): Pair<String, List<String>> {
        val headers = arrayListOf<String>()
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("@Header:{", index, ignoreCase = true)
            if (start < 0) {
                output.append(text.substring(index))
                break
            }
            output.append(text.substring(index, start))
            val openIndex = start + "@Header:".length
            val closeIndex = findBalancedBraceEnd(text, openIndex)
            if (closeIndex < 0) {
                output.append(text.substring(start))
                break
            }
            headers.add(text.substring(openIndex, closeIndex + 1))
            index = closeIndex + 1
        }
        return output.toString() to headers
    }

    private fun findBalancedBraceEnd(text: String, openIndex: Int): Int {
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (index in openIndex until text.length) {
            val ch = text[index]
            val currentQuote = quote
            if (currentQuote != null) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == currentQuote -> quote = null
                }
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun String.splitTopLevel(delimiter: Char): List<String> {
        val result = arrayListOf<String>()
        var quote: Char? = null
        var escaped = false
        var depth = 0
        var start = 0
        for (index in indices) {
            val ch = this[index]
            val currentQuote = quote
            if (currentQuote != null) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == currentQuote -> quote = null
                }
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '{', '[', '(' -> depth++
                '}', ']', ')' -> if (depth > 0) depth--
                delimiter -> if (depth == 0) {
                    result.add(substring(start, index))
                    start = index + 1
                }
            }
        }
        result.add(substring(start))
        return result
    }

    private fun String.indexOfTopLevel(target: Char): Int {
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (index in indices) {
            val ch = this[index]
            val currentQuote = quote
            if (currentQuote != null) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == currentQuote -> quote = null
                }
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '{', '[', '(' -> depth++
                '}', ']', ')' -> if (depth > 0) depth--
                target -> if (depth == 0) return index
            }
        }
        return -1
    }

    private fun appendContentHeaders(rule: String, headers: Map<String, String>): String {
        if (rule.isBlank() || headers.isEmpty()) return rule
        val headerJson = GSON.toJson(headers)
        val headerScript = """
            <js>
            (function(value) {
                var headers = $headerJson;
                var pageUrl = String(baseUrl || '');
                var prePageUrl = chapter && chapter.bookUrl ? String(chapter.bookUrl) : pageUrl;
                var hostMatch = pageUrl.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\/]+/);
                for (var key in headers) {
                    if (!Object.prototype.hasOwnProperty.call(headers, key)) continue;
                    if (headers[key] === 'thisPage') headers[key] = pageUrl;
                    else if (headers[key] === 'prePage') headers[key] = prePageUrl;
                    else if (headers[key] === 'host') headers[key] = hostMatch ? hostMatch[0] : pageUrl;
                }
                var option = ',' + JSON.stringify({headers: headers});
                function appendUrlHeader(url) {
                    url = String(url == null ? '' : url).trim();
                    if (!url || url.indexOf(',{') >= 0) return url;
                    return url + option;
                }
                function appendHeader(item) {
                    var value = String(item == null ? '' : item).trim();
                    if (!value) return value;
                    if (/<img\b/i.test(value)) {
                        return value.replace(/(<img\b[^>]*?\b(?:data-src|src)\s*=\s*["'])([^"']+)(["'])/gi,
                            function(match, prefix, url, suffix) {
                                return prefix + appendUrlHeader(url) + suffix;
                            });
                    }
                    if (value.charAt(0) === '<') return value;
                    return appendUrlHeader(value);
                }
                if (Array.isArray(value)) return value.map(appendHeader);
                return String(value == null ? '' : value).split(/\r?\n/).map(appendHeader).join('\n');
            })(result)
            </js>
        """.trimIndent()
        return if (rule.startsWith("@js:", true)) {
            "<js>${rule.substring(4)}</js>\n$headerScript"
        } else {
            "$rule\n$headerScript"
        }
    }

    private fun DocumentContext.readNonBlankString(path: String): String? {
        val result = runCatching { this.readString(path) }.getOrNull()?.trim()
        return if (result.isNullOrBlank()) null else result
    }

    private fun DocumentContext.readOptionalString(path: String): String? {
        return runCatching { this.readString(path) }.getOrNull()
    }

    private val ycyKeysMap = mapOf(
        "S001" to "F41QW5WFH3E88FFGEE22FBE34WGHF9WF",
        "S002" to "P0FWIswsFGnqny5h75TMpS2pcp4Lpc1k",
        "S003" to "qHjYXQxJInOb0UdYbPBWTsJdI20jkCOB",
        "S004" to "CFteSRlSNwXhAsdTIdEhm87ibJOuh3DQ",
        "S005" to "c26zIwWMF20xDq3VExNTLaJdPxDiieKQ",
        "S006" to "YUmJ0jsShFlJVeyI3thZrj8HlRWyRcrj",
        "S007" to "nrP2wkM6mpqQggW1q7EQ2oD6vjcw3Oiq",
        "S008" to "dLSfJq3baOeve33H6dkqFPv0sJUI6auQ",
        "S009" to "LH5C4HH2I2Uqk7FyNZzKWRarzhi3jxwq",
        "S010" to "CbpvdsaeILv4tlvu4nXGkFFA2oax8gEf",

        "R001" to "",
        "R002" to "",
        "R003" to "",
        "R004" to "iZktBxUNG3QZOWRDak0bIaLQpkSLCBAu",
        "R005" to "nP1lcVxFBozrkq6zoTvwzjXYiBmcu0Fo",
        "R006" to "lp17FfIRIvMdK6PBxrJkHLH1fIRxAKof",

        "H001" to "F41QW5WFH3E88FFGEE22FBE34WGHF9WF",
        "H002" to "P0FWIswsFGnqny5h75TMpS2pcp4Lpc1k",
        "H003" to "clnd8CDIs6oqq3bJG2wI6N55B8HFJyl2",
        "H004" to "UFbrrNHE5Sv8HqVFhtssJjR0CCBPZ7DP",
        "H005" to "ScoyV09Veuam8YPMFvA9e9pSJzTJLNOm",
        "H006" to "t7UNxE8HiRWWOY1Ob9YUSJ4sfQlcU4x8",

        "T001" to "F41QW5WFH3E88FFGEE22FBE34WGHF9WF",
        "T002" to "P0FWIswsFGnqny5h75TMpS2pcp4Lpc1k",
        "T003" to "ZOH0jEUBYu249jvCVCYrrHYEn7VGv0dn",
        "T004" to "4zZQwmFX7LvF4fBwRgf3GbXUNnidclCe",
        "T005" to "IfAW3JUE2OrVJbzIq0kKbnWbxl60CUZ1",
        "T006" to "LbFdkUsBfYqMMEyKl6rpR83zfrUNSAse"
    )

    private data class YiciyuanRsaPrivateKey(
        val modulus: String,
        val privateExponent: String,
        val transformation: String = "RSA/ECB/PKCS1Padding",
        val urlDecode: Boolean = true
    )

    private val ycyRsaPrivateKeysMap = mapOf(
        "R001" to YiciyuanRsaPrivateKey(
            modulus = "124894949392799470526501691427201380722059386407429374904963151579445000167700084219094797780867267785897015633452737623494882167165602560066550153229779581419202243260024600553243026875623862252970534410045512718055164222767734268476307217370858508842021185622033555296345081105287930968885783895899183401631",
            privateExponent = "19086069456853159241873580673390497400881106826700537018310625401326552189545004503333042132561491952439938676955532641951444640195991636879317701597903178032251524558220594586042774566667098468523075912221587922761078702629673680760823904240579535052710120226686686757549111111483582028698505555454486750265",
            transformation = "RSA",
            urlDecode = false
        ),
        "R002" to YiciyuanRsaPrivateKey(
            modulus = "134637879530725842045797524508160121551609494553379149906687560348617712247282090903147805955484430250432335457792034049708343226865029205108006451147296816481047287446612470453679013649723292054233709641226286065157635394380094379354568922533499017531342766295713079603926199948346272171926131302561430658419",
            privateExponent = "26112192620585178072281450711216095106302279331075967543356458730352402940614904808344296925357963451151566288299214928800797970384179222599833925832853439888342640415696699869357785949087891627998411565633711986243813092664335597492895203854674537798891101584434089581685869173590440642207295109926796080873"
        ),
        "R003" to YiciyuanRsaPrivateKey(
            modulus = "129687005002893678462258796476531399533182790367431241886401469844966355386036128571070619438228618154986229861190436546865018454538596851401582310946251118113102749171640191782313442529234243995625586165944107120885398307387122000295641915645959373878533198490065094178492877982169810374840993728562031519451",
            privateExponent = "41468496831417976499612361550913102653728100995466516700053850528268229297492608913062177105261438913222934113113306808003163506619327030806591069291997466282358213608270495908597272131676474848064702792913802149316731747013886568532033632097590127095715137913435615888935607179182373719289689782848795732129"
        ),
        "R004" to YiciyuanRsaPrivateKey(
            modulus = "114737282646053411153191125552464961684877527566486697407657067497223857621606510099134620159886510385369743052921265046412650960670405167324511418769565986785130279303309025131430922680512927459659347040053031505170887511387363963040194542702287631882079335182228304208113000751602157929985817627734352710973",
            privateExponent = "6257529830441680345671809519903228547266025343004944965581553527045064209591941479192393855478193383049388957488194937091115212722220969365543661420832829565415921870538601853029528477108725351697612171310930129800532652860831639423337379483853627148984644975559139383848088176284172307975953124337029486825"
        ),
        "R005" to YiciyuanRsaPrivateKey(
            modulus = "146372144379205490318397651279575810877674630401376756713544888234448441631421195469371449557009813069179225328595244812888859210444267899624134179269439598626750554744149486431693991900726482490381030602289706414213778315170872098929393476937274144247556843764280070143422500796941791167495767154628182240551",
            privateExponent = "70387587411429431162998934630841684357162439757916736747268320569338844656225562442818193059095514957683718751901115978769227191460645513078175698105894729760343620214532047321719776584517760097231040938346123064264290930257656668906635959708481115861769263421653051042270366207333290378393268508256115557261"
        ),
        "R006" to YiciyuanRsaPrivateKey(
            modulus = "147016280108323395424425110933109423982167754606681218202836670921323970403694758493534062597366854555224174098104812131753688557538322792080358547319739657444425204231632640918083014064617176044848677821564756438387138009504153779014931647274809221226629782150732202102138976504029597451845107605372273052759",
            privateExponent = "3106162053852405941646509660274026108518469732803117732042375711466702132927191392048972316042652312548134230554380932884247688420353860558675808604636558666900217700811222405994977417661504605591182755170180239765097621852782385920349085783253187007506297779008899163668213273497487603412074635153582808353"
        )
    )

    /**
     * 解密异次元以 :_ 开头的混淆/加密规则（如 :_H006_sOeCKr...）
     * 先对齐 APK 原生 S/H/T/R 分支，再走兼容兜底，最后回退到源里已有的明文规则。
     */
    fun decryptHRule(rule: String?, fallback: String? = null, bookSourceUrl: String? = null): String? {
        if (rule.isNullOrBlank()) return fallback
        val trimmed = rule.trim()
        if (!trimmed.contains("_")) {
            return rule
        }

        val match = Pattern.compile("^\\s*:_([0-9a-zA-Z]+)_(.+)$", Pattern.DOTALL).matcher(trimmed)
        if (!match.find()) {
            return rule
        }
        val verKey = match.group(1).orEmpty().uppercase()
        val cipherText = match.group(2).orEmpty().replace("\r", "").replace("\n", "").trim()

        // 1. 完全对齐异次元 APK 源码 Cfor.m26512for 原生公式动态解密
        if (verKey.startsWith("R")) {
            val rsaDecrypted = runCatching {
                ycyRsaDecrypt(cipherText, verKey)
            }.onFailure {
                // Diagnostic belongs to the explicit import result; no global logger.
            }.getOrNull()

            if (!rsaDecrypted.isNullOrBlank() && isValidRuleString(rsaDecrypted)) {
                return rsaDecrypted
            }
            return fallback
        }

        val salt = ycyKeysMap[verKey]
        if (!salt.isNullOrBlank()) {
            val passKey = when {
                verKey.startsWith("H") -> {
                    val urlBytes = hexDecodeUrl(bookSourceUrl.orEmpty())
                    val cgotoStr = ycyCgotoEncrypt(urlBytes)
                    DigestUtil.md5Hex(cgotoStr + salt)
                }
                verKey.startsWith("T") -> {
                    val urlBytes = hexDecodeUrl(bookSourceUrl.orEmpty())
                    val cgotoStr = ycyCgotoEncrypt(urlBytes)
                    DigestUtil.md5Hex(cgotoStr + salt + "648")
                }
                else -> salt
            }
            val apkDecrypted = runCatching {
                ycyAesDecrypt(cipherText, passKey)
            }.getOrNull()

            if (!apkDecrypted.isNullOrBlank() && isValidRuleString(apkDecrypted)) {
                return apkDecrypted
            }
        }

        // 2. 备用动态 AES 密钥库匹配（支持根据 9 位密文指纹动态派生）
        runCatching {
            val bytes = base64Decode(cipherText)
            val decrypted = tryDecryptHBytes(bytes, verKey, cipherText)
            if (!decrypted.isNullOrBlank()) {
                return decrypted
            }
        }.onFailure {
            // Diagnostic belongs to the explicit import result; no global logger.
        }

        // 3. 智能降级回退（动态使用来自 JSON 源自身的 fallback 字段，零硬编码）
        return fallback
    }

    fun ycyCgotoEncrypt(bArr: ByteArray): String {
        val byteArrayOutputStream = ByteArrayOutputStream(bArr.size + (bArr.size / 8) + 1)
        val lookupTable = byteArrayOf(0, 10, 13, 34, 38, 92)

        fun findIdx(b: Byte): Int {
            for (i in lookupTable.indices) {
                if (lookupTable[i] == b) return i
            }
            return -1
        }

        var bitIndex = 0
        var bytePos = 0

        fun getNextByte(): Byte {
            if (bytePos >= bArr.size) return Byte.MIN_VALUE
            val b = bArr[bytePos].toInt()
            val i3 = ((b and (254 ushr bitIndex)) shl bitIndex) ushr 1
            val i4 = bitIndex + 7
            bitIndex = i4
            if (i4 < 8) return i3.toByte()
            val i5 = i4 - 8
            bitIndex = i5
            val i6 = bytePos + 1
            bytePos = i6
            return if (i6 >= bArr.size) i3.toByte() else (((bArr[i6].toInt() and (65280 ushr i5) and 255) ushr (8 - i5)) or i3).toByte()
        }

        while (true) {
            val b1 = getNextByte()
            if (b1 != Byte.MIN_VALUE) {
                val idx = findIdx(b1)
                if (idx != -1) {
                    val b2 = getNextByte()
                    val b: Byte
                    var sourceByte = b1
                    if (b2 == Byte.MIN_VALUE) {
                        b = (-34).toByte()
                    } else {
                        b = (((idx and 7) shl 2) or (-62)).toByte()
                        sourceByte = b2
                    }
                    val i = if ((sourceByte.toInt() and 64) > 0) 1 else 0
                    val bVal = ((sourceByte.toInt() and 63) or (-128)).toByte()
                    byteArrayOutputStream.write((b.toInt() or i))
                    byteArrayOutputStream.write(bVal.toInt())
                } else {
                    byteArrayOutputStream.write(b1.toInt())
                }
            } else {
                return String(byteArrayOutputStream.toByteArray(), Charsets.UTF_8)
            }
        }
    }

    private fun hexDecodeUrl(str: String): ByteArray {
        var input = str
        if (input.length % 2 == 1) {
            input = "0$input"
        }
        val len = input.length
        val bArr = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            bArr[i / 2] = ((Character.digit(input[i], 16) shl 4) + Character.digit(input[i + 1], 16)).toByte()
        }
        return bArr
    }

    private fun ycyRsaDecrypt(cipherText: String, verKey: String): String? {
        val keyInfo = ycyRsaPrivateKeysMap[verKey] ?: return null
        val privateKey = ycyCreatePrivateKey(keyInfo)
        val cipher = Cipher.getInstance(keyInfo.transformation)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val rsaPrivateKey = privateKey as RSAPrivateKey
        val blockSize = rsaPrivateKey.modulus.bitLength() / 8
        val encryptedBytes = ycyHexToBytes(cipherText)
        val plainText = encryptedBytes.asIterable()
            .chunked(blockSize)
            .joinToString(separator = "") { block ->
                String(cipher.doFinal(block.toByteArray()), Charsets.UTF_8)
            }
        return if (keyInfo.urlDecode) {
            URLDecoder.decode(plainText, "UTF-8")
        } else {
            plainText
        }
    }

    private fun ycyCreatePrivateKey(keyInfo: YiciyuanRsaPrivateKey): PrivateKey {
        val keySpec = RSAPrivateKeySpec(
            BigInteger(keyInfo.modulus),
            BigInteger(keyInfo.privateExponent)
        )
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun ycyHexToBytes(str: String): ByteArray {
        val input = str.trim()
        require(input.length % 2 == 0) { "Invalid hex length: ${input.length}" }
        val bytes = ByteArray(input.length / 2)
        for (i in input.indices step 2) {
            val high = Character.digit(input[i], 16)
            val low = Character.digit(input[i + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid hex char in encrypted rule" }
            bytes[i / 2] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun base64Decode(str: String): ByteArray {
        return Base64.decode(str.replace("\n", "").replace("\r", "").trim())
    }

    fun ycyAesDecrypt(cipherText: String, keyStr: String): String {
        val bArr = ByteArray(32)
        val bytes = keyStr.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, bArr, 0, minOf(bytes.size, 32))
        val secretKeySpec = SecretKeySpec(bArr, "AES")
        val ivParameterSpec = IvParameterSpec("1234567890123456".toByteArray(Charsets.UTF_8))
        val decode = base64Decode(cipherText)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
        return String(cipher.doFinal(decode), Charsets.UTF_8).trim()
    }

    private fun tryDecryptHBytes(bytes: ByteArray, ver: String, cipherText: String = ""): String? {
        // 按照 APK 原生算法：由版本号及前9位密文指纹动态生成候选 Key 列表
        val dynamicKeys = mutableListOf(
            "yiciyuan_comic_$ver",
            "yiciyuan_$ver",
            "yiciyuan_comic_rule",
            "yiciyuan$ver",
            "yiciyuan20200808",
            "yiciyuan20210808",
            "yiciyuan20220808"
        )
        if (cipherText.length >= 8) {
            val p9 = cipherText.take(9)
            val p16 = cipherText.take(16)
            dynamicKeys.add(p9)
            dynamicKeys.add(p16)
            dynamicKeys.add(DigestUtil.md5Hex(p9))
            dynamicKeys.add(DigestUtil.md5Hex(p16))
        }

        val standardIv = "1234567890123456".toByteArray(Charsets.UTF_8)
        for (kStr in dynamicKeys) {
            val keyBytes = kStr.toByteArray(Charsets.UTF_8).copyOf(16)
            runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(standardIv)
                )
                val decrypted = String(cipher.doFinal(bytes), Charsets.UTF_8).trim()
                if (isValidRuleString(decrypted)) {
                    return decrypted
                }
            }
            runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(keyBytes)
                )
                val decrypted = String(cipher.doFinal(bytes), Charsets.UTF_8).trim()
                if (isValidRuleString(decrypted)) {
                    return decrypted
                }
            }
            runCatching {
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
                val decrypted = String(cipher.doFinal(bytes), Charsets.UTF_8).trim()
                if (isValidRuleString(decrypted)) {
                    return decrypted
                }
            }
        }
        return null
    }

    private fun isValidRuleString(str: String): Boolean {
        if (str.isBlank()) return false
        for (ch in str) {
            if (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') {
                return false
            }
        }
        return str.contains("@") || str.contains(".") || str.contains("#") ||
                str.contains("/") || str.contains("text") || str.contains("href") ||
                str.contains("src") || str.contains("class") || str.contains("id") ||
                str.contains("tag") || str.contains("js") || str.contains("$")
    }

    private fun toNewRule(
        oldRule: String?,
        fallback: String? = null,
        bookSourceUrl: String? = null,
        convertVarGet: Boolean = false
    ): String? {
        if (oldRule.isNullOrBlank()) return fallback
        val encrypted = oldRule.trim().startsWith(":_")
        var newRule = decryptHRule(oldRule, if (encrypted) null else fallback, bookSourceUrl)?.trim()
        require(!encrypted || !newRule.isNullOrBlank()) { "异次元加密规则无法解密或版本不受支持" }
        if (newRule.isNullOrBlank() || newRule.startsWith(":_")) {
            newRule = fallback
        }
        val resolvedRule = newRule ?: return null
        if (resolvedRule.isBlank()) return null
        var rule = resolvedRule
        val (ruleWithoutHeaders, ruleHeaders) = splitContentHeaders(rule)
        rule = normalizeLegacyRulePrefix(ruleWithoutHeaders?.trim().orEmpty())
        if (rule.isBlank()) return null
        var reverse = false
        var allinone = false
        if (rule.startsWith("-")) {
            reverse = true
            rule = rule.substring(1).trim()
        }
        if (rule.startsWith("+")) {
            allinone = true
            rule = rule.substring(1).trim()
        }

        if (rule.equals("href", ignoreCase = true)) {
            rule = "a@href||href"
        } else if (rule.startsWith("href#", ignoreCase = true)) {
            val replaceRule = rule.substring(4)
            rule = "a@href$replaceRule||href$replaceRule"
        } else if (rule.equals("src", ignoreCase = true)) {
            rule = "img@src||src"
        } else if (rule.startsWith("src#", ignoreCase = true)) {
            val replaceRule = rule.substring(3)
            rule = "img@src$replaceRule||src$replaceRule"
        }

        if (rule.contains("result.join()")) {
            rule = rule.replace("result.join()", "(result.join?result.join():String(result))")
        }
        rule = normalizeLegacyJsRuleStrings(rule)
        rule = normalizeLegacyRuleSeparators(rule)
        rule = normalizeJsonPathAlternatives(rule)

        rule = normalizeJsonWildcardRule(rule)
        normalizeWebRule(rule)?.let {
            rule = it
        }

        // 只将完整的 result.xxx 简单取值转换为 JSONPath，其他 JS 规则必须原样保留。
        normalizeSimpleResultRule(rule)?.let {
            rule = it
        }
        if (!looksLikeLegacyJsCode(rule) &&
            !rule.startsWith("@CSS:", true) &&
            !rule.startsWith("@XPath:", true) &&
            !rule.startsWith("@Json:", true) &&
            !rule.startsWith("$.") &&
            !rule.startsWith("$[") &&
            !rule.startsWith("//") &&
            !rule.startsWith("##") &&
            !rule.startsWith(":") &&
            !rule.contains("@js:", true) &&
            !rule.contains("<js>", true)
        ) {
            if (rule.contains("#") && !rule.contains("##")) {
                rule = rule.split("||").joinToString("||") { rulePart ->
                    if (rulePart.contains("#") && !isCssIdSelector(rulePart)) {
                        rulePart.replace("#", "##")
                    } else {
                        rulePart
                    }
                }
            }
            if (rule.contains("|") && !rule.contains("||")) {
                if (rule.contains("##")) {
                    val list = rule.split("##")
                    if (list[0].contains("|")) {
                        rule = list[0].replace("|", "||")
                        for (i in 1 until list.size) {
                            rule += "##" + list[i]
                        }
                    }
                } else {
                    rule = rule.replace("|", "||")
                }
            }
            if (rule.contains("&") &&
                !rule.contains("&&") &&
                !rule.contains("http") &&
                !rule.startsWith("/")
            ) {
                rule = rule.replace("&", "&&")
            }
        }
        if (convertVarGet && rule.contains("{$.") && !rule.contains("@js:", true) && !rule.contains("<js>", true)) {
            rule = rule.replace(Regex("""\{\$\.([a-zA-Z0-9_]+)\}""")) { match ->
                "@get:{${match.groupValues[1]}}"
            }
        }
        if (allinone) {
            rule = "+$rule"
        }
        if (reverse) {
            rule = "-$rule"
        }
        if (ruleHeaders.isNotEmpty()) {
            rule = appendContentHeaders(rule, ruleHeaders)
        }
        return rule
    }

    private fun normalizeSimpleResultRule(rule: String): String? {
        if (!rule.startsWith("@js:", true) && !rule.startsWith("<js>", true)) {
            return null
        }
        val simplePropMatch = Pattern
            .compile("^(@js:|<js>)\\s*result\\.([a-zA-Z0-9_]+)\\s*(</js>)?$", Pattern.CASE_INSENSITIVE)
            .matcher(rule)
        if (!simplePropMatch.matches()) {
            return null
        }
        return "$.${simplePropMatch.group(2)}"
    }

    private fun normalizeLegacyRulePrefix(rule: String): String {
        return when {
            rule.startsWith("$@js:", true) -> "@js:" + rule.substringAfter(":", "")
            rule.startsWith("$<js>", true) -> "<js>" + rule.substring(5)
            rule.startsWith("@.", true) -> "$." + rule.substring(2)
            rule.startsWith("@JSon:", true) -> "@json:" + rule.substring(6)
            rule.startsWith("@Json:", true) -> "@json:" + rule.substring(6)
            else -> rule
        }
    }

    private fun normalizeLegacyJsRuleStrings(rule: String): String {
        var result = rule
        result = Regex("""(?i)^@json:\$\.([A-Za-z_][A-Za-z0-9_]*)@put:\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*\{\{\s*result\.([A-Za-z_][A-Za-z0-9_]*)\s*\}\}\s*\}\s*$""")
            .replace(result) { match ->
                val returnProperty = match.groupValues[1]
                val key = match.groupValues[2]
                val property = match.groupValues[3]
                """@json:$.${returnProperty}@put:{"$key":"$property"}"""
            }
        result = Regex("""@put:\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*\{\{\s*result\.([A-Za-z_][A-Za-z0-9_]*)\s*\}\}\s*\}""")
            .replace(result) { match ->
                val key = match.groupValues[1]
                val property = match.groupValues[2]
                """@put:{"$key":"$property"}"""
            }
        if (result.contains("java.getString") || result.contains("java.getElements")) {
            result = Regex("""(java\.(?:getString|getElements)\(\s*["'])([^"']+&[^"']+)(["'])""")
                .replace(result) { match ->
                    val prefix = match.groupValues[1]
                    val rawRule = match.groupValues[2]
                    val suffix = match.groupValues[3]
                    val normalizedRule = if (!rawRule.contains("&&") && !rawRule.contains("http")) {
                        rawRule.replace("&", "&&")
                    } else {
                        rawRule
                    }
                    "$prefix$normalizedRule$suffix"
                }
        }
        result = Regex("""(?is)^@js:\s*JSON\.parse\(\s*result\s*\)\s*;?\s*$""")
            .replace(result, "@js:(result==null||String(result).trim()==''?[]:JSON.parse(result))")
        result = result.replace(
            Regex("""eval\(\s*String\(result\)\.match\((/[^/\r\n]*(?:\\.[^/\r\n]*)*/[gimsuy]*)\)\[0]\s*\)\s*;""")
        ) { match ->
            "var imageList=[];var __ycyMatch=String(result).match(${match.groupValues[1]});if(__ycyMatch){eval(__ycyMatch[0]);}"
        }
        result = Regex("""\{\{\s*result\.([A-Za-z_][A-Za-z0-9_]*)\s*\}\}""").replace(result) { match ->
            val property = match.groupValues[1]
            "{{${safeResultPropertyExpression(property)}}}"
        }
        result = normalizePrefixedJsBlock(result)
        result = normalizeInlineJsExpression(result)
        result = result.replace(Regex("""(?<![\w.])match\s*\("""), "java.match(")
        result = result.replace(Regex("""\bresult\.(match|replace|split|substring|substr|indexOf|trim)\s*\(""")) {
            "String(result).${it.groupValues[1]}("
        }
        return result
    }

    private fun normalizePrefixedJsBlock(rule: String): String {
        val match = Regex("""(?is)^(.+?)<js>([\s\S]*)</js>\s*$""").find(rule) ?: return rule
        val prefixRule = match.groupValues[1].trim()
        val script = match.groupValues[2].trim()
        if (prefixRule.isBlank() || script.isBlank()) return rule
        if (prefixRule.contains("<js>", true) || prefixRule.contains("</js>", true)) return rule
        val prefixRules = prefixRule.split("||")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (prefixRules.isEmpty()) return rule
        val prefixRulesJson = GSON.toJson(prefixRules)
        val scriptBody = script.trim().trimEnd(';')
        val scriptCall = if (scriptBody.startsWith("(") ||
            scriptBody.startsWith("'") ||
            scriptBody.startsWith("\"")
        ) {
            "return $scriptBody;"
        } else {
            "return (function(){ $script })();"
        }
        return """
            <js>(function(result, baseUrl, chapter) {
                var __rules = $prefixRulesJson;
                for (var __i = 0; __i < __rules.length; __i++) {
                    try {
                        var __value = java.getString(__rules[__i]);
                        if (__value !== null && String(__value).length > 0) {
                            result = __value;
                            break;
                        }
                    } catch (__e) {}
                }
                $scriptCall
            })(result, baseUrl, chapter)</js>
        """.trimIndent()
    }

    private fun normalizeInlineJsExpression(rule: String): String {
        val jsIndex = rule.indexOf("@js:", ignoreCase = true)
        if (jsIndex < 0) return rule
        var expression = rule.substring(jsIndex + 4).trim()
        if (expression.isBlank() ||
            expression.startsWith("(") ||
            expression.startsWith("function", true) ||
            expression.startsWith("var ", true) ||
            expression.startsWith("let ", true) ||
            expression.startsWith("const ", true) ||
            expression.startsWith("return ", true) ||
            expression.startsWith("{")
        ) {
            return rule
        }
        if (!expression.startsWith("\"") && !expression.startsWith("'")) {
            return rule
        }
        val prefixRule = rule.substring(0, jsIndex).trim()
        if (prefixRule.isNotBlank()) {
            val prefixRules = prefixRule.split("||")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (prefixRules.isNotEmpty()) {
                val prefixRulesJson = GSON.toJson(prefixRules)
                return """
                    @js:(function(result, baseUrl, chapter) {
                        var __rules = $prefixRulesJson;
                        for (var __i = 0; __i < __rules.length; __i++) {
                            try {
                                var __value = java.getString(__rules[__i]);
                                if (__value !== null && String(__value).length > 0) {
                                    result = __value;
                                    break;
                                }
                            } catch (__e) {}
                        }
                        return $expression;
                    })(result, baseUrl, chapter)
                """.trimIndent()
            }
        }
        val resultProperties = resultDataProperties(expression)
        if (resultProperties.size == 1) {
            val property = resultProperties.first()
            val scalarExpression = Regex("""\bresult\.$property\b""").replace(expression, "result")
            return """
                @js:(function() {
                    var result = java.getStringSafe('$.${property}') || java.getStringSafe('${property}') || '';
                    java.put('${property}', String(result == null ? '' : result));
                    return $scalarExpression;
                })()
            """.trimIndent()
        }
        expression = convertResultProperties(expression)
        val selector = rule.substring(0, jsIndex + 4)
        return "${selector}(function(result, baseUrl, chapter) { return $expression; })(result, baseUrl, chapter)"
    }

    private fun resultDataProperties(expression: String): List<String> {
        val methodNames = setOf(
            "replace", "match", "split", "join", "substring", "substr",
            "indexOf", "toString", "trim", "length", "map", "filter"
        )
        return Regex("""\bresult\.([A-Za-z_][A-Za-z0-9_]*)""")
            .findAll(expression)
            .map { it.groupValues[1] }
            .filterNot { it in methodNames }
            .distinct()
            .toList()
    }

    private fun convertResultProperties(expression: String): String {
        return Regex("""\bresult\.([A-Za-z_][A-Za-z0-9_]*)""").replace(expression) { match ->
            val property = match.groupValues[1]
            if (property !in resultDataProperties(match.value)) {
                match.value
            } else {
                safeResultPropertyExpression(property)
            }
        }
    }

    private fun looksLikeLegacyJsCode(rule: String): Boolean {
        val script = rule.trim().removePrefix("$").trimStart()
        return script.startsWith("function ", true) ||
                script.startsWith("var ", true) && script.contains("function", true) ||
                script.contains("function getImgList", true)
    }

    private fun safeResultPropertyExpression(property: String): String {
        return "((typeof result!=='undefined'&&result&&result['$property']!=null)?result['$property']:(function(){try{var v=java.getString('$.${property}');if(v!==null&&String(v).length>0)return v;}catch(e){}try{return java.get(typeof result!=='undefined'?result:null,'$property');}catch(e2){return '';}})())"
    }

    private fun normalizeLegacyRuleSeparators(rule: String): String {
        val jsIndex = rule.indexOf("@js:", ignoreCase = true)
        if (jsIndex <= 0) return rule
        val selectorPart = rule.substring(0, jsIndex)
        if (selectorPart.contains("&&") || selectorPart.contains("http") || !selectorPart.contains("&")) {
            return rule
        }
        return selectorPart.replace("&", "&&") + rule.substring(jsIndex)
    }

    private fun normalizeWebRule(rule: String): String? {
        val prefix = when {
            rule.startsWith("@webm:", true) -> "@webm:"
            rule.startsWith("@web:", true) -> "@web:"
            else -> return null
        }
        var script = rule.substring(prefix.length).trim()
        val target = when {
            script.startsWith("htmlgbk@@", true) -> {
                script = script.substring(9)
                "java.webView(String(result == null ? '' : result), baseUrl, ${GSON.toJson(script)})"
            }
            script.startsWith("html@@", true) -> {
                script = script.substring(6)
                "java.webView(String(result == null ? '' : result), baseUrl, ${GSON.toJson(script)})"
            }
            script.startsWith("result@@", true) -> {
                script = script.substring(8)
                "java.webView(String(result == null ? '' : result), ${GSON.toJson(script)})"
            }
            script.startsWith("baseUrl@@", true) -> {
                script = script.substring(9)
                "java.webView(baseUrl, ${GSON.toJson(script)})"
            }
            script.isBlank() -> {
                "java.webView(baseUrl, null)"
            }
            else -> {
                "java.webView(baseUrl, ${GSON.toJson(script)})"
            }
        }
        return "@js:$target"
    }

    fun normalizeJsonWildcardRule(rule: String): String {
        var result = rule
        if (result.startsWith("*.")) {
            result = "$." + result.substring(2)
        }
        if (result.startsWith("$.") || result.startsWith("$[")) {
            result = result.replace(Regex("""\.\*(?=\.|$)"""), "[*]")
        }
        return result
    }

    private fun normalizeJsonPathAlternatives(rule: String): String {
        return rule.split("||").joinToString("||") { part ->
            val trimmed = part.trim()
            if (trimmed.startsWith("$.") || trimmed.startsWith("$[")) {
                "@json:$trimmed"
            } else {
                part
            }
        }
    }

    /**
     * 判断规则中的 '#' 是否为 CSS ID 选择器而非正则替换分隔符
     */
    private fun isCssIdSelector(rule: String): Boolean {
        if (rule.startsWith("#") || rule.contains(" #") || rule.contains(">#") || rule.contains(".#")) {
            return true
        }
        val parts = rule.split("#")
        if (parts.size == 2 && (parts[0].contains(".") || parts[0].contains(">") || parts[0].matches(Regex("^[a-zA-Z0-9_-]+$")))) {
            return true
        }
        return false
    }

    private fun toNewUrls(oldUrls: String?, convertVarGet: Boolean = false): String? {
        if (oldUrls.isNullOrBlank()) return null
        if (oldUrls.startsWith("@js:") || oldUrls.startsWith("<js>")) {
            return oldUrls
        }
        if (!oldUrls.contains("\n") && !oldUrls.contains("&&")) {
            return toNewUrl(oldUrls, convertVarGet)
        }
        val urls = oldUrls.split("(&&|\r?\n)+".toRegex())
        return urls.mapNotNull {
            toNewUrl(it, convertVarGet)?.replace("\n\\s*".toRegex(), "")
        }.joinToString("\n")
    }

    private fun toNewUrl(oldUrl: String?, convertVarGet: Boolean = false): String? {
        if (oldUrl.isNullOrBlank()) return null
        var url: String = oldUrl
        if (oldUrl.startsWith("<js>", true) || oldUrl.startsWith("@js:", true)) {
            url = url.replace("=searchKey", "={{key}}")
                .replace("=searchPage", "={{page}}")
                .replace("searchKey", "key")
                .replace("searchPage", "page")
                .replace("pageKey", "page")
            return url
        }
        if (convertVarGet && url.contains("{$.") && !url.contains("@js:", true) && !url.contains("<js>", true)) {
            url = url.replace(Regex("""\{\$\.([a-zA-Z0-9_]+)\}""")) { match ->
                "@get:{${match.groupValues[1]}}"
            }
        }
        val map = HashMap<String, Any>()
        if (url.contains("@formType", ignoreCase = true)) {
            map["method"] = "POST"
            url = url.replace("@formType=[^@|]+".toRegex(RegexOption.IGNORE_CASE), "")
                .replace("@formType", "", ignoreCase = true)
        }
        val jsTemplates = arrayListOf<String>()
        url = extractJsTemplates(url) { template ->
            val normalizedTemplate = normalizeDynamicUrlHeaderTemplate(template)
                .replace("searchKey", "key")
                .replace("searchPage", "page")
            val token = "__YICYUAN_JS_${jsTemplates.size}__"
            jsTemplates.add(normalizedTemplate)
            token
        }
        val (urlWithoutHeaders, headerValues) = stripHeaderBlocks(url)
        if (headerValues.isNotEmpty()) {
            url = urlWithoutHeaders
            val headerMap = LinkedHashMap<String, String>()
            headerValues.forEach { headerValue ->
                parseLegacyHeaders(headerValue).forEach { (key, value) ->
                    headerMap[key] = normalizeUrlHeaderValue(value)
                }
            }
            if (headerMap.isNotEmpty()) {
                map["headers"] = headerMap
            }
        }
        var urlList = url.split("|")
        url = urlList[0]
        if (urlList.size > 1) {
            val charsetVal = urlList[1].replace("char=", "", ignoreCase = true).split("=").last().trim()
            if (charsetVal.isNotBlank()) {
                map["charset"] = charsetVal
            }
        }
        url = normalizePageAlternatives(url)
        var mather = jsPattern.matcher(url)
        val jsList = arrayListOf<String>()
        while (mather.find()) {
            jsList.add(mather.group())
            url = url.replace(jsList.last(), "$${jsList.size - 1}")
        }
        url = url.replace("[{<]?(searchPage|pageKey)([-+]\\d+)?[}>]?".toRegex()) {
            val offset = it.groupValues[2]
            "{{page$offset}}"
        }.replace("[{<]?searchKey[}>]?".toRegex(), "{{key}}")
        for ((index, item) in jsList.withIndex()) {
            url = url.replace(
                "$$index",
                item.replace("searchKey", "key").replace("searchPage", "page")
            )
        }
        urlList = url.split("@", limit = 2)
        url = urlList[0]
        if (urlList.size > 1) {
            map["method"] = "POST"
            map["body"] = urlList[1]
        }
        if (map.isNotEmpty()) {
            url += "," + GSON.toJson(map)
        }
        jsTemplates.forEachIndexed { index, template ->
            url = url.replace("__YICYUAN_JS_${index}__", template)
        }
        return url
    }

    private fun extractJsTemplates(text: String, replacement: (String) -> String): String {
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("{{", index)
            if (start < 0) {
                result.append(text.substring(index))
                break
            }
            val end = findJsTemplateEnd(text, start + 2)
            if (end < 0) {
                result.append(text.substring(index))
                break
            }
            result.append(text.substring(index, start))
            result.append(replacement(text.substring(start, end + 2)))
            index = end + 2
        }
        return result.toString()
    }

    private fun findJsTemplateEnd(text: String, startIndex: Int): Int {
        var quote: Char? = null
        var escaped = false
        var regex = false
        var previousNonSpace: Char? = null
        var index = startIndex
        while (index < text.length - 1) {
            val ch = text[index]
            val currentQuote = quote
            if (currentQuote != null) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == currentQuote -> quote = null
                }
                index++
                continue
            }
            if (regex) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '/' -> regex = false
                }
                index++
                continue
            }
            if (ch == '}' && text[index + 1] == '}') {
                return index
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '/' -> if (previousNonSpace == null || previousNonSpace in "([{=:+!,?;") {
                    regex = true
                }
            }
            if (!ch.isWhitespace()) {
                previousNonSpace = ch
            }
            index++
        }
        return -1
    }

    private fun normalizeDynamicUrlHeaderTemplate(template: String): String {
        if (!template.contains("@Header:", ignoreCase = true)) return template
        val inner = template.substring(2, template.length - 2)
            .replace("\"@Header:\"", "\"@\"+\"Header:\"")
            .replace("'@Header:'", "'@'+'Header:'")
            .replace("\\\"@Header:\\\"", "\\\"@\\\"+\\\"Header:\\\"")
            .replace("\\'@Header:\\'", "\\'@\\'+\\'Header:\\'")
            .let(::splitDynamicHeaderMarkerInJsStrings)
        return "{{(function(){var __m='@'+'Header:';var __u=String($inner);var __p=__u.lastIndexOf(__m);if(__p<0)return __u;var h=__u.substring(__p+__m.length).trim();var o;try{o=JSON.parse(h)}catch(e){o=eval('('+h+')')}return __u.substring(0,__p)+','+JSON.stringify({headers:o});})()}}"
    }

    private fun splitDynamicHeaderMarkerInJsStrings(script: String): String {
        val marker = "@Header:"
        val result = StringBuilder(script.length + 16)
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < script.length) {
            val ch = script[index]
            val currentQuote = quote
            if (currentQuote != null) {
                if (!escaped && script.startsWith(marker, index)) {
                    if (currentQuote == '"') {
                        result.append("\"+__m+\"")
                    } else {
                        result.append("'+__m+'")
                    }
                    index += marker.length
                    continue
                }
                result.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == currentQuote -> quote = null
                }
                index++
                continue
            }
            result.append(ch)
            if (ch == '"' || ch == '\'') {
                quote = ch
            }
            index++
        }
        return result.toString()
    }

    private fun normalizeUrlHeaderValue(value: String): String {
        val normalizedValue = normalizeHeaderInnerJs(value.trim())
        return when (normalizedValue) {
            "host" -> "{{baseUrl}}"
            "thisPage" -> "{{baseUrl}}"
            "prePage" -> "{{chapter && chapter.bookUrl ? chapter.bookUrl : baseUrl}}"
            else -> normalizedValue
        }
    }

    private fun normalizeHeaderInnerJs(value: String): String {
        if (!value.startsWith("{{") || !value.endsWith("}}") || !value.contains('"')) {
            return value
        }
        return "{{" + value.substring(2, value.length - 2).replace('"', '\'') + "}}"
    }

    private fun normalizePageAlternatives(url: String): String {
        val matcher = Pattern.compile("\\{([^\"\\{\\}':]*)\\}").matcher(url)
        val result = StringBuffer()
        while (matcher.find()) {
            val previous = url.getOrNull(matcher.start() - 1)
            if (previous == ':') {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()))
                continue
            }
            val options = matcher.group(1).orEmpty()
                .split(',')
                .map { it.trim() }
            if (options.size < 2) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()))
                continue
            }
            val optionJson = options.joinToString(prefix = "[", postfix = "]") {
                "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'"
            }
            val lastOption = "'" + options.last().replace("\\", "\\\\").replace("'", "\\'") + "'"
            val replacement = "{{var pageOptions=$optionJson;page<=pageOptions.length?pageOptions[page-1]:$lastOption}}"
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(result)
        return result.toString()
    }
}
