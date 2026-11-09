package io.legado.features.adapter

import io.legado.features.api.rule.DefaultRuleCompatibilityPolicy
import io.legado.features.api.rule.RuleCompatibilityPolicy
import io.legado.features.api.rule.RuleFetchPort
import io.legado.features.api.source.ImportResult
import io.legado.features.yiciyuan.YiciyuanImportAdapter
import io.legado.features.yiciyuan.YiciyuanRulePolicy
import io.legado.features.yiciyuan.YiciyuanSourceConverter
import io.legado.features.yiciyuan.YiciyuanScriptSupport
import io.legado.features.api.tts.ImportedVoice
import io.legado.features.api.tts.JsonResponseValidation
import io.legado.features.api.tts.ScriptEngineDraft
import io.legado.features.multitts.MultiTtsImporter
import io.legado.features.multitts.JsonResponseValidator
import java.io.InputStream

/** Stateless modules initialize lazily; no settings screen or Application side effect is required. */
object FeatureAssembly {
    fun importMultiTts(input: InputStream, namespace: String = "default", checkCancelled: () -> Unit = {}) =
        MultiTtsImporter.parseBackup(input, namespace, checkCancelled)
    fun mergeMultiTts(draft: ScriptEngineDraft, existing: List<ImportedVoice>) = MultiTtsImporter.merge(draft, existing)
    fun independentMultiTts(draft: ScriptEngineDraft, namespace: String) = MultiTtsImporter.independent(draft, namespace)
    fun validateTtsResponse(body: String, rule: JsonResponseValidation) = JsonResponseValidator.validate(body, rule)
    fun importSource(text: String): ImportResult = YiciyuanImportAdapter.importSource(text)
    fun decompressSource(text: String): String = YiciyuanSourceConverter.decompressSource(text)
    fun isYiciyuan(comment: String?): Boolean = YiciyuanSourceConverter.isAdapterComment(comment)
    fun rulePolicy(comment: String?): RuleCompatibilityPolicy =
        if (isYiciyuan(comment)) YiciyuanRulePolicy else DefaultRuleCompatibilityPolicy

    fun normalizeChapterUrlRule(rule: String?, sourceUrl: String): String? =
        YiciyuanSourceConverter.normalizeChapterUrlRule(rule, sourceUrl)
    fun normalizeJsonWildcardRule(rule: String): String = YiciyuanSourceConverter.normalizeJsonWildcardRule(rule)
    fun isInvalidChapterUrl(url: String?): Boolean = YiciyuanSourceConverter.isInvalidChapterUrl(url)
    fun isInvalidBookUrl(url: String?, baseUrl: String): Boolean = YiciyuanSourceConverter.isInvalidBookUrl(url, baseUrl)
    fun isExplicitEmptyListResponse(body: String): Boolean = YiciyuanSourceConverter.isExplicitEmptyListResponse(body)
    fun fallbackBookUrl(item: Any?, baseUrl: String): String? = YiciyuanSourceConverter.fallbackBookUrlFromJsonItem(item, baseUrl)
    fun repairBookUrl(url: String?, item: Any?, baseUrl: String): String? = YiciyuanSourceConverter.repairBookUrlFromJsonItem(url, item, baseUrl)
    fun resolveBookUrl(url: String, item: Any?, baseUrl: String): String =
        repairBookUrl(url, item, baseUrl)
            ?: url.takeUnless { isInvalidBookUrl(it, baseUrl) }
            ?: fallbackBookUrl(item, baseUrl).orEmpty()

    // The return type exposed to scripts remains module-owned, never an NG runtime entity.
    fun fetch(url: Any, options: Any?, port: RuleFetchPort): Any = YiciyuanSourceConverter.fetch(url, options, port)
    fun match(text: Any?, regex: Any?): Array<String> = YiciyuanScriptSupport.match(text, regex)
    fun get(value: Any?, key: String): String? = YiciyuanScriptSupport.get(value, key)
    fun decryptBytes(mode: String, data: Any?, key: String, iv: String): ByteArray? =
        YiciyuanSourceConverter.decryptAESBytes(mode, null, data, key, iv)
    fun decryptText(mode: String, data: Any?, key: String, iv: String): String? =
        YiciyuanSourceConverter.decryptAES(mode, null, data, key, iv)
    fun prepareImageRule(rule: String, sourceUrl: String): String =
        YiciyuanRulePolicy.prepareRule(rule, sourceUrl).trim().let { value ->
            val script = when {
                value.startsWith("@js:", true) -> value.substring(4)
                value.startsWith("<js>", true) -> value.substring(4)
                else -> value
            }.trim()
            if (script.endsWith("</js>", true)) script.dropLast(5).trim() else script
        }
}
