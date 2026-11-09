package io.legado.features.yiciyuan

import io.legado.features.api.rule.RuleCompatibilityPolicy
import io.legado.features.api.source.ImportResult
import io.legado.features.api.source.SourceImportAdapter
import java.util.concurrent.CancellationException
import org.htmlunit.corejs.javascript.NativeArray
import org.htmlunit.corejs.javascript.Scriptable
import org.htmlunit.corejs.javascript.Wrapper

object YiciyuanImportAdapter : SourceImportAdapter {
    override fun importSource(text: String): ImportResult {
        return try {
            if (!YiciyuanSourceConverter.isYiciyuanSource(text)) ImportResult.NotHandled
            else ImportResult.Converted(YiciyuanSourceConverter.convert(text))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImportResult.Failed("异次元源转换失败：${error.message}", error)
        }
    }
}

object YiciyuanRulePolicy : RuleCompatibilityPolicy {
    override fun unwrapContent(content: Any): Any = YiciyuanSourceConverter.unwrapHtmlJson(content)
    override fun prepareRule(rule: String, sourceUrl: String): String =
        YiciyuanSourceConverter.decryptHRule(rule, bookSourceUrl = sourceUrl)
            ?: error("异次元加密规则无法解密")
    override fun normalizeResult(result: Any?): List<String> = YiciyuanSourceConverter.normalizeRuleResult(result)
    override fun formatResult(result: Any?): String = YiciyuanSourceConverter.formatRuleResult(result)
    override fun formatImageContent(content: String): String = YiciyuanSourceConverter.formatImageContent(content)
    override fun elements(result: Any?): List<Any> {
        val value = if (result is Wrapper) result.unwrap() else result
        return when (value) {
            is NativeArray -> (0 until value.length.toInt()).map { value.get(it, value) }
                .filterNotNull().filterNot { it === Scriptable.NOT_FOUND }
            is List<*> -> value.filterNotNull()
            is Array<*> -> value.filterNotNull()
            is String -> if (value.trim().startsWith('[')) {
                GSON.fromJsonObject<List<Any>>(value).getOrThrow()
            } else emptyList()
            else -> emptyList()
        }
    }
}
