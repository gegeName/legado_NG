package io.legado.features.translation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.features.api.manga.TranslationProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal class MangaPublicTranslator(
    private val client: OkHttpClient,
    private val bingBaseUrl: String = "https://cn.bing.com/",
    private val googleBaseUrl: String = "https://translate.googleapis.com/",
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class BingSession(val key: String, val token: String, val ig: String, val iid: String, val expiresAt: Long)
    private class HttpFailure(val status: Int, service: String) : IOException(
        if (status == 429) "$service 请求被限流（HTTP 429），请稍后重试并检查服务商额度或网络出口限制"
        else "$service HTTP $status"
    )
    private val bingLock = Mutex()
    private val requestGate = MangaTranslationQueue(3, backgroundLimit = 2, fairByKey = true)
    private val unavailableUntil = ConcurrentHashMap<String, Long>()
    private var bingSession: BingSession? = null
    private val deeplHosts = setOf("api-free.deepl.com", "api.deepl.com", "api-us.deepl.com", "api-jp.deepl.com")

    fun prioritize(identity: String) = requestGate.prioritize(identity)

    suspend fun translate(
        text: String,
        target: String,
        preferred: String = "bing",
        identity: String? = null,
        log: (String) -> Unit = {},
        config: TranslationProviderConfig = TranslationProviderConfig()
    ): String {
        val configuredServices = buildList {
            if (config.deeplKey.isNotBlank() && isDeepLEndpoint(config.deeplEndpoint)) add("deepl")
            if (config.baiduAppId.isNotBlank() && config.baiduSecret.isNotBlank()) add("baidu")
            if (config.libreEndpoint.toHttpUrlOrNull()?.isHttps == true) add("libre")
        }
        val services = when (preferred) {
            "google" -> listOf("google")
            "bing" -> listOf("bing")
            "deepl" -> listOf("deepl")
            "baidu" -> listOf("baidu")
            "libre" -> listOf("libre")
            "ai" -> listOf("bing", "google") + configuredServices
            else -> listOf("bing", "google") + configuredServices
        }
        validateConfig(preferred, config)
        val failures = mutableListOf<String>()
        for (service in services) {
            currentCoroutineContext().ensureActive()
            try {
                val cooldown = (unavailableUntil[service] ?: 0L) - now()
                if (cooldown > 0) {
                    log("PUBLIC_SKIP service=$service queued=false")
                    throw IOException("${serviceName(service)}暂时不可用，稍后自动重试")
                }
                return requestGate.withPermit(identity.orEmpty(), foreground = identity == null) {
                    log("PUBLIC_QUEUE_START service=$service")
                    val remaining = (unavailableUntil[service] ?: 0L) - now()
                    if (remaining > 0) {
                        log("PUBLIC_SKIP service=$service")
                        throw IOException("${serviceName(service)}暂时不可用，稍后自动重试")
                    }
                    log("PUBLIC_START service=$service chars=${text.length} target=$target")
                    try {
                        val result = splitText(text).map { part ->
                            currentCoroutineContext().ensureActive()
                            when (service) {
                                "bing" -> translateBing(part, target, log)
                                "google" -> translateGoogle(part, target, log)
                                "deepl" -> translateDeepL(part, target, config, log)
                                "baidu" -> translateBaidu(part, target, config, log)
                                else -> translateLibre(part, target, config, log)
                            }
                        }.joinToString("\n")
                        check(result.isNotBlank()) { "${serviceName(service)}返回空译文" }
                        log("PUBLIC_OK service=$service chars=${result.length}")
                        result
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (error !is HttpFailure || error.status !in setOf(400, 422)) {
                            unavailableUntil[service] = now() + 60_000L
                        }
                        log("PUBLIC_FAILED service=$service type=${error.javaClass.simpleName}" +
                            if (error is HttpFailure) " http=${error.status}" else "")
                        throw error
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                failures.add("${serviceName(service)}：${error.message.orEmpty()}")
            }
        }
        throw IOException("联网翻译均不可用。${failures.joinToString("；")}")
    }

    private suspend fun translateBing(text: String, target: String, log: (String) -> Unit): String {
        var session = bingLock.withLock {
            bingSession?.takeIf { it.expiresAt > now() } ?: loadBingSession(log)
        }
        for (attempt in 0..1) {
            val url = bingBaseUrl.toHttpUrl().newBuilder().addPathSegment("ttranslatev3")
                .addQueryParameter("isVertical", "1")
                .addQueryParameter("IG", session.ig)
                .addQueryParameter("IID", "${session.iid}.1")
                .build()
            val body = FormBody.Builder()
                .add("fromLang", "auto-detect")
                .add("to", bingLanguage(target))
                .add("text", text)
                .add("key", session.key)
                .add("token", session.token)
                .build()
            try {
                val response = request(Request.Builder().url(url)
                    .header("Referer", bingBaseUrl + "translator")
                    .post(body).build(), "必应翻译", log)
                val root = JsonParser.parseString(response)
                if (root.isJsonObject) {
                    val status = root.asJsonObject.get("statusCode")?.asInt
                    if (status != null) throw HttpFailure(status, "必应翻译")
                    error("必应翻译未返回译文")
                }
                val translated = root.asJsonArray.firstOrNull()?.asJsonObject
                    ?.getAsJsonArray("translations")?.firstOrNull()?.asJsonObject?.get("text")?.asString.orEmpty()
                check(translated.isNotBlank()) { "必应翻译返回空译文" }
                return translated
            } catch (error: HttpFailure) {
                if (attempt != 0 || error.status !in setOf(401, 403)) throw error
                log("BING_SESSION_REFRESH")
                session = bingLock.withLock {
                    bingSession?.takeIf { it !== session && it.expiresAt > now() } ?: loadBingSession(log)
                }
            }
        }
        error("必应翻译会话不可用")
    }

    private suspend fun loadBingSession(log: (String) -> Unit): BingSession {
        val page = request(Request.Builder().url(bingBaseUrl + "translator").build(), "必应翻译会话", log)
        val helper = Regex("params_AbusePreventionHelper\\s*=\\s*(\\[[^\\]]+\\])").find(page)?.groupValues?.get(1)
            ?: error("必应翻译会话暂不可用，请稍后重试")
        val values = JsonParser.parseString(helper).asJsonArray
        val ig = Regex("IG:\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: error("必应翻译会话缺少请求标识")
        val iid = Regex("data-iid=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: "translator.5028"
        val duration = if (values.size() > 2) values[2].asLong.coerceIn(1_000L, 600_000L) else 300_000L
        return BingSession(values[0].asString, values[1].asString, ig, iid, now() + duration).also {
            check(it.key.isNotBlank() && it.token.isNotBlank()) { "必应翻译会话参数为空" }
            bingSession = it
            log("BING_SESSION_READY")
        }
    }

    private suspend fun translateGoogle(text: String, target: String, log: (String) -> Unit): String {
        val url = googleBaseUrl.toHttpUrl().newBuilder().addPathSegments("translate_a/single")
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .addQueryParameter("dj", "1")
            .addQueryParameter("dt", "t")
            .addQueryParameter("ie", "UTF-8")
            .addQueryParameter("q", text).build()
        val response = request(Request.Builder().url(url).build(), "Google 翻译", log)
        return JsonParser.parseString(response).asJsonObject.getAsJsonArray("sentences")
            ?.mapNotNull { it.asJsonObject.get("trans")?.takeUnless { value -> value.isJsonNull }?.asString }
            ?.joinToString("").orEmpty().also {
                check(it.isNotBlank()) { "Google 翻译返回空译文" }
            }
    }

    private suspend fun translateDeepL(
        text: String,
        target: String,
        config: TranslationProviderConfig,
        log: (String) -> Unit
    ): String {
        val body = JsonObject().apply {
            add("text", JsonArray().apply { add(text) })
            addProperty("target_lang", deepLLanguage(target))
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(config.deeplEndpoint.toHttpUrl())
            .header("Authorization", "DeepL-Auth-Key ${config.deeplKey}")
            .post(body)
            .build()
        val response = request(request, "DeepL", log)
        return JsonParser.parseString(response).asJsonObject.getAsJsonArray("translations")
            ?.firstOrNull()?.asJsonObject?.get("text")?.asString.orEmpty()
            .also { check(it.isNotBlank()) { "DeepL 返回空译文" } }
    }

    private suspend fun translateBaidu(
        text: String,
        target: String,
        config: TranslationProviderConfig,
        log: (String) -> Unit
    ): String {
        val salt = SecureRandom().nextInt(Int.MAX_VALUE).toString()
        val sign = md5(config.baiduAppId + text + salt + config.baiduSecret)
        val body = FormBody.Builder()
            .add("q", text)
            .add("from", "auto")
            .add("to", baiduLanguage(target))
            .add("appid", config.baiduAppId)
            .add("salt", salt)
            .add("sign", sign)
            .build()
        val response = request(
            Request.Builder().url("https://fanyi-api.baidu.com/api/trans/vip/translate").post(body).build(),
            "百度翻译",
            log
        )
        val root = JsonParser.parseString(response).asJsonObject
        root.get("error_code")?.takeUnless { it.isJsonNull }?.asString?.let { code ->
            throw IOException("百度翻译错误 $code：${root.get("error_msg")?.asString.orEmpty()}")
        }
        return root.getAsJsonArray("trans_result")?.mapNotNull { item ->
            item.asJsonObject.get("dst")?.asString
        }?.joinToString("\n").orEmpty().also {
            check(it.isNotBlank()) { "百度翻译返回空译文" }
        }
    }

    private suspend fun translateLibre(
        text: String,
        target: String,
        config: TranslationProviderConfig,
        log: (String) -> Unit
    ): String {
        val body = JsonObject().apply {
            addProperty("q", text)
            addProperty("source", "auto")
            addProperty("target", libreLanguage(target))
            addProperty("format", "text")
            if (config.libreKey.isNotBlank()) addProperty("api_key", config.libreKey)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = request(
            Request.Builder().url(config.libreEndpoint.toHttpUrl()).post(body).build(),
            "LibreTranslate",
            log
        )
        return JsonParser.parseString(response).asJsonObject.get("translatedText")?.asString.orEmpty()
            .also { check(it.isNotBlank()) { "LibreTranslate 返回空译文" } }
    }

    private fun validateConfig(engine: String, config: TranslationProviderConfig) {
        when (engine) {
            "deepl" -> {
                require(config.deeplKey.isNotBlank()) { "请填写 DeepL API 密钥" }
                require(isDeepLEndpoint(config.deeplEndpoint)) { "DeepL API 地址必须使用 DeepL 官方 HTTPS 地址" }
            }
            "baidu" -> {
                require(config.baiduAppId.isNotBlank() && config.baiduSecret.isNotBlank()) { "请填写百度翻译 APP ID 和密钥" }
            }
            "libre" -> require(config.libreEndpoint.toHttpUrlOrNull()?.isHttps == true) { "LibreTranslate 接口地址必须使用 HTTPS" }
        }
    }

    private fun isDeepLEndpoint(endpoint: String): Boolean = endpoint.toHttpUrlOrNull()?.let {
        it.isHttps && it.host in deeplHosts && it.encodedPath == "/v2/translate"
    } == true

    private fun deepLLanguage(language: String): String = when (language.lowercase()) {
        "zh-cn", "zh-hans", "zh" -> "ZH-HANS"
        "zh-tw", "zh-hk", "zh-hant" -> "ZH-HANT"
        "en" -> "EN-US"
        "en-gb" -> "EN-GB"
        "pt" -> "PT-PT"
        "pt-br" -> "PT-BR"
        "pt-pt" -> "PT-PT"
        else -> when (language.substringBefore('-').lowercase()) {
            "ar" -> "AR"
            "bg" -> "BG"
            "cs" -> "CS"
            "da" -> "DA"
            "de" -> "DE"
            "el" -> "EL"
            "es" -> "ES"
            "et" -> "ET"
            "fi" -> "FI"
            "fr" -> "FR"
            "hu" -> "HU"
            "id" -> "ID"
            "it" -> "IT"
            "ja" -> "JA"
            "ko" -> "KO"
            "lt" -> "LT"
            "lv" -> "LV"
            "nb", "no" -> "NB"
            "nl" -> "NL"
            "pl" -> "PL"
            "ro" -> "RO"
            "ru" -> "RU"
            "sk" -> "SK"
            "sl" -> "SL"
            "sv" -> "SV"
            "tr" -> "TR"
            "uk" -> "UK"
            else -> throw IOException("DeepL 暂不支持目标语言：$language")
        }
    }

    private fun baiduLanguage(language: String): String = when (language.lowercase()) {
        "zh-cn", "zh-hans", "zh" -> "zh"
        "zh-tw", "zh-hk", "zh-hant" -> "cht"
        "ja" -> "jp"
        "ko" -> "kor"
        "fr" -> "fra"
        "es" -> "spa"
        "ar" -> "ara"
        else -> when (language.substringBefore('-').lowercase()) {
            "bg" -> "bul"
            "da" -> "dan"
            "de" -> "de"
            "el" -> "el"
            "et" -> "est"
            "fi" -> "fin"
            "hi" -> "hi"
            "hu" -> "hu"
            "id" -> "id"
            "it" -> "it"
            "nl" -> "nl"
            "pl" -> "pl"
            "pt" -> "pt"
            "ro" -> "rom"
            "ru" -> "ru"
            "sv" -> "swe"
            "th" -> "th"
            "tr" -> "tr"
            "uk" -> "ukr"
            "vi" -> "vie"
            else -> throw IOException("百度翻译暂不支持目标语言：$language")
        }
    }

    private fun libreLanguage(language: String): String = when (language.lowercase()) {
        "zh-cn", "zh-hans", "zh-tw", "zh-hk", "zh-hant" -> "zh"
        else -> language.substringBefore('-').lowercase()
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun request(request: Request, service: String, log: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        withTranslationCallCancellation(call) {
            call.execute().use { response ->
                log("PUBLIC_HTTP service=$service http=${response.code}")
                if (!response.isSuccessful) throw HttpFailure(response.code, service)
                val source = response.body.source()
                if (source.request(1_048_577)) throw IOException("翻译服务响应过大")
                source.readString(response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            }
        }
    }

    private fun bingLanguage(language: String): String = when (language.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hk", "zh-hant" -> "zh-Hant"
        else -> language
    }

    private fun splitText(text: String): List<String> = buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 1000, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            add(text.substring(start, end))
            start = end
        }
    }

    private fun serviceName(service: String): String = when (service) {
        "bing" -> "必应翻译"
        "google" -> "Google 翻译"
        "deepl" -> "DeepL"
        "baidu" -> "百度翻译"
        else -> "LibreTranslate"
    }
}
