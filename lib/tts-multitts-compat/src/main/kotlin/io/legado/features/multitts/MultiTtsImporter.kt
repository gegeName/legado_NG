package io.legado.features.multitts

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.legado.features.api.tts.ImportDiagnostic
import io.legado.features.api.tts.ImportedVoice
import io.legado.features.api.tts.MultiTtsImportResult
import io.legado.features.api.tts.ScriptEngineDraft
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CancellationException

object MultiTtsImporter {
    internal val gson = Gson()

    fun parseBackup(input: InputStream, namespace: String = "default", checkCancelled: () -> Unit = {}): MultiTtsImportResult {
        require(namespace.isNotBlank() && namespace.length <= 128) { "目录命名空间为空或过长" }
        val (engineDocument, catalog) = BackupReader.read(input, checkCancelled)
        val records = engineDocument["engines"] as? List<*> ?: error("engines.yaml 缺少 engines 列表")
        require(records.size <= 128) { "引擎数量超限" }
        val engines = records.map(BackupReader::objectMap)
        require(engines.map { it.text("code").lowercase() }.distinct().size == engines.size) { "重复的引擎 code" }
        val diagnostics = arrayListOf<ImportDiagnostic>()
        val drafts = arrayListOf<ScriptEngineDraft>()
        var count = 0
        for (engine in engines) {
            checkCancelled()
            val code = engine.text("code").also { require(it.isNotBlank()) { "引擎缺少 code" } }
            val provider = when {
                code.equals("xfpeiyin", true) -> "xfpeiyin"
                code.equals("huoshan", true) -> "huoshan"
                engine.text("url").isNotBlank() && engine.text("type").lowercase() in listOf("", "http", "online", "web") -> "directhttp"
                else -> null
            }
            if (provider == null) { diagnostics += ImportDiagnostic(code, "不支持的提供方或离线引擎"); continue }
            val local = arrayListOf<ImportDiagnostic>()
            try {
                when (provider) {
                    "directhttp" -> ProviderScripts.validateDirectTemplate(engine.text("url"))
                    "huoshan" -> validateEndpoint(engine.text("url").ifBlank { ProviderScripts.HUOSHAN_URL })
                }
            } catch (e: IllegalArgumentException) {
                diagnostics += ImportDiagnostic(code, e.message.orEmpty()); continue
            }
            val items = catalog[code] as? List<*>
            if (items == null) {
                diagnostics += ImportDiagnostic(code, "config.yaml 缺少音色列表"); continue
            }
            val seen = hashSetOf<String>()
            val voices = arrayListOf<ImportedVoice>()
            for (item in items) {
                checkCancelled()
                require(++count <= 20_000) { "音色数量超限" }
                val speaker = BackupReader.objectMap(item)
                val speakerCode = speaker.text("code")
                require(speakerCode.isNotBlank() && seen.add(speakerCode)) { "$code 存在空或重复的 speaker code: $speakerCode" }
                try {
                    voices += voice(provider, code, speaker)
                } catch (e: CancellationException) { throw e
                } catch (e: RuntimeException) {
                    local += ImportDiagnostic("$code/$speakerCode", e.message ?: "音色参数不支持")
                }
            }
            if (voices.isEmpty()) { diagnostics += local; diagnostics += ImportDiagnostic(code, "无可支持的音色"); continue }
            val duplicateNames = voices.groupBy { it.name }.count { it.value.size > 1 }
            val duplicateIds = voices.groupBy { it.extra["voiceCode"] }.count { it.value.size > 1 }
            if (duplicateNames > 0 || duplicateIds > 0) local += ImportDiagnostic(code, "保留 $duplicateNames 组同名、$duplicateIds 组相同默认编号；未按名称删除")
            local += ImportDiagnostic(code, "协议模板已生成；导入不会联网验证音色或自动切换朗读引擎")
            val id = "multitts:$provider:${encode(namespace)}:${encode(code)}"
            drafts += ProviderScripts.build(id, engine.text("name").ifBlank { code }, provider, voices, engine, local)
        }
        val knownCodes = engines.map { it.text("code") }.toSet()
        catalog.keys.filterNot { it in knownCodes }.forEach { diagnostics += ImportDiagnostic(it, "未找到对应引擎定义") }
        return MultiTtsImportResult(drafts, diagnostics)
    }

    /** Preserve existing identities on update; the entire merged catalog also enters voices() and export. */
    fun merge(draft: ScriptEngineDraft, existing: List<ImportedVoice>): ScriptEngineDraft {
        val merged = linkedMapOf<String, ImportedVoice>()
        existing.forEach { merged[it.id] = it }
        draft.voices.forEach { merged[it.id] = it }
        return ProviderScripts.build(draft.id, draft.name, draft.provider, merged.values.toList(), draft.originalEngine, draft.diagnostics)
    }

    fun independent(draft: ScriptEngineDraft, namespace: String): ScriptEngineDraft = ProviderScripts.build(
        "multitts:${draft.provider}:${encode(namespace)}:${encode(draft.originalEngine.text("code"))}",
        draft.name, draft.provider, draft.voices, draft.originalEngine, draft.diagnostics,
    )

    private fun voice(provider: String, engineCode: String, speaker: Map<String, Any?>): ImportedVoice {
        val sourceCode = speaker.text("code")
        val extra = linkedMapOf<String, Any?>("provider" to provider, "sourceCode" to sourceCode, "original" to speaker)
        for (key in listOf("speed", "pitch", "volume")) {
            val factor = speaker[key]?.toString()?.toDoubleOrNull() ?: 1.0
            require(factor.isFinite() && factor > 0 && factor <= 10) { "$key 倍数非法" }
            extra[key] = factor
        }
        var resolved = speaker.text("param").substringBefore(',').trim().ifBlank { sourceCode }
        val styles = arrayListOf<Map<String, String>>()
        if (provider != "directhttp" && resolved.startsWith('@')) {
            val reference = resolved.drop(1)
            val ui = runCatching { JsonParser.parseString(speaker.text("extendUI")).asJsonArray }.getOrNull()
                ?: throw IllegalArgumentException("extendUI 不是可识别的 JSON 数组")
            val field = ui.map { it.asJsonObject }.singleOrNull { it.get("code")?.asString == reference }
                ?: throw IllegalArgumentException("未解析的参数引用 @$reference")
            resolved = field.get("value")?.asString.orEmpty()
            if (provider == "xfpeiyin") field.getAsJsonObject("candidate")?.entrySet()?.forEach { (value, label) ->
                require(value.matches(Regex("[0-9]+"))) { "风格编号必须为数字" }
                styles += mapOf("id" to value, "name" to label.asString, "value" to value)
            }
        }
        if (provider == "xfpeiyin") require(resolved.matches(Regex("[0-9]+"))) { "讯飞音色编号未解析为数字" }
        require(resolved.isNotBlank()) { "音色编号为空" }
        extra["voiceCode"] = if (provider == "directhttp") sourceCode else resolved
        extra["styles"] = styles
        extra["originalEngineCode"] = engineCode
        val id = if (provider == "directhttp") "$provider:${encode(engineCode)}:${encode(sourceCode)}" else "$provider:${encode(sourceCode)}"
        return ImportedVoice(id, speaker.text("name").ifBlank { sourceCode }, speaker.text("locale").ifBlank { "zh-CN" },
            when (speaker.text("gender")) { "1" -> "male"; "0" -> "female"; else -> "unknown" }, extra)
    }

    internal fun Map<String, Any?>.text(key: String) = this[key]?.toString().orEmpty().trim()
    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    internal fun validateEndpoint(url: String) {
        val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("请求地址非法") }
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        require(uri.scheme?.lowercase() in listOf("http", "https") && host.isNotBlank() && uri.rawUserInfo == null) { "仅支持 HTTP(S) 地址" }
        require(host != "localhost" && !host.endsWith(".localhost") && !host.startsWith("127.") && host !in listOf("::1", "0.0.0.0", "::", "0:0:0:0:0:0:0:1", "2130706433", "127.1")) { "依赖本地转发服务，不能独立合成" }
    }
}
