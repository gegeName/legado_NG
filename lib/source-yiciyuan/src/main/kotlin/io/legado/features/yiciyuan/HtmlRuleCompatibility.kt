package io.legado.features.yiciyuan

private val singleSelectorPipe = Regex("""(?<!\|)\|(?!\|)""")
private val selectorAlternation = Regex("""\|\|?""")
private val simpleHtmlExtraction = Regex(
    """[\p{L}\w.#-]+(?:@[\p{L}\w.#-]+)*@(?:text|textNodes|ownText|html|all|href|src)"""
)

/**
 * 在宿主程序隔离 HTML 提取规则后，处理旧式的单管道回退语法。
 * 每个分支都必须包含一个简单的选择器和提取后缀，因此 CSS 属性中的管道符和
 * 正则表达式将保持其原始含义。宿主程序对所有源类型都使用此方法。
 */
fun normalizeLegacyHtmlExtractionFallbacks(rule: String): String {
    if ('|' !in rule || !singleSelectorPipe.containsMatchIn(rule) || "##" in rule) return rule
    val branches = rule.split(selectorAlternation).map { it.trim() }
    if (branches.any { !simpleHtmlExtraction.matches(it) }) return rule
    return branches.joinToString("||")
}
