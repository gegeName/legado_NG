package io.legado.features.api.rule

/** Implementations accept host runtime values but expose no Rhino or NG classes. */
interface RuleCompatibilityPolicy {
    fun unwrapContent(content: Any): Any = content
    fun prepareRule(rule: String, sourceUrl: String): String = rule
    fun normalizeResult(result: Any?): List<String> = listOfNotNull(result?.toString())
    fun formatResult(result: Any?): String = result?.toString().orEmpty()
    fun formatImageContent(content: String): String = content
    fun elements(result: Any?): List<Any> = (result as? List<*>)?.filterNotNull().orEmpty()
}

object DefaultRuleCompatibilityPolicy : RuleCompatibilityPolicy

/** The host owns execution, the source scope, cancellation and credentials. */
interface RuleExecutionPort {
    fun evaluate(script: String, bindings: Map<String, Any?>): Any?
}

data class FetchResult(val url: String, val body: String, val statusCode: Int)

fun interface RuleFetchPort {
    fun fetch(request: String): FetchResult
}
