package io.legado.features.multitts

import io.legado.features.api.tts.ImportDiagnostic
import io.legado.features.api.tts.ImportedVoice
import io.legado.features.api.tts.ScriptEngineDraft
import java.security.MessageDigest

internal object ProviderScripts {
    const val HUOSHAN_URL = "https://translate.volcengine.com/web/tts/v1"
    private val placeholders = Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL)
    private fun token(text: String) = text.replace(Regex("\\s+"), "")
    private val supported = setOf("voiceCode", "speakText", "java.encodeURI(speakText)", "java.encodeURIComponent(speakText)",
        "speakSpeed", "speakPitch", "speakVolume", "2-speakSpeed*0.015", "speakVolume*4")

    fun validateDirectTemplate(template: String) {
        val tokens = placeholders.findAll(template).map { token(it.groupValues[1]) }.toList()
        require(tokens.all { it in supported }) { "存在不支持的 HTTP 模板占位符" }
        val probe = placeholders.replace(template, "test")
        require(!probe.contains("{{") && !probe.contains("}}")) { "HTTP 模板占位符不完整" }
        MultiTtsImporter.validateEndpoint(probe)
        val uri = java.net.URI(probe)
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        require(!template.substringBefore('/', template).contains("{{") && !template.take(origin.length).contains("{{")) { "不支持请求域名中的占位符" }
    }

    fun build(id: String, name: String, provider: String, voices: List<ImportedVoice>, engine: Map<String, Any?>,
              diagnostics: List<ImportDiagnostic>): ScriptEngineDraft {
        val catalog = voices.map { mapOf("id" to it.id, "name" to it.name, "language" to it.language, "gender" to it.gender, "extra" to it.extra) }
        val template = engine["url"]?.toString().orEmpty()
        val capability = when (provider) {
            "xfpeiyin" -> listOf("synthesis_speed", "synthesis_volume", "style_tags")
            "directhttp" -> {
                val tokens = placeholders.findAll(template).map { token(it.groupValues[1]) }.toList()
                buildList {
                    if (tokens.any { it.contains("speakSpeed") }) add("synthesis_speed")
                    if (tokens.any { it.contains("speakVolume") }) add("synthesis_volume")
                    if (tokens.any { it.contains("speakPitch") }) add("synthesis_pitch")
                }
            }
            else -> emptyList()
        }
        val mime = if (provider == "xfpeiyin") "audio/(mpeg|wav|x-wav)" else if (provider == "huoshan") "audio/wav" else "audio/.*|application/octet-stream"
        val body = when (provider) {
            "xfpeiyin" -> resource("xfpeiyin.js")
            "huoshan" -> resource("huoshan.js").replace("__ENDPOINT__", MultiTtsImporter.gson.toJson(template.ifBlank { HUOSHAN_URL }))
            "directhttp" -> resource("directhttp.js").replace("__TEMPLATE__", MultiTtsImporter.gson.toJson(template))
            else -> error("Unknown provider")
        }
        val catalogJson = MultiTtsImporter.gson.toJson(catalog)
        val version = MessageDigest.getInstance("SHA-256").digest((catalogJson + body + MultiTtsImporter.gson.toJson(engine)).toByteArray())
            .joinToString("") { "%02x".format(it) }
        // Header values are not copied from user-controlled display names or YAML comments.
        val script = """
            // @name MultiTTS $provider
            // @schema 1
            // @uuid $id
            // @version 1.$version
            // @cookieJar false
            // @audioType $mime
            // @capabilities ${capability.joinToString(",")}
            // Catalog and protocol fingerprint: $version
            var MULTITTS_CATALOG = $catalogJson;
            var MULTITTS_IMPORT_METADATA = ${MultiTtsImporter.gson.toJson(engine)};
            function voices(options, ctx) { return MULTITTS_CATALOG; }
            function options() { return []; }
        """.trimIndent() + "\n" + body
        return ScriptEngineDraft(id, name, provider, script, mime, voices, engine, diagnostics)
    }

    private fun resource(name: String) = ProviderScripts::class.java.getResourceAsStream("/multitts/$name")
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Missing provider template: $name")
}
