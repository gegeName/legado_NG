package io.legado.features.yiciyuan

import org.htmlunit.corejs.javascript.NativeObject
import org.htmlunit.corejs.javascript.Undefined
import org.htmlunit.corejs.javascript.Wrapper
import org.htmlunit.corejs.javascript.Context

object YiciyuanScriptSupport {
    fun match(text: Any?, regex: Any?): Array<String> {
        var pattern = regex?.toString() ?: return emptyArray()
        val value = text?.toString() ?: return emptyArray()
        val options = mutableSetOf<RegexOption>()
        if (pattern.startsWith('/')) {
            val end = pattern.lastIndexOf('/')
            if (end > 0) {
                val flags = pattern.substring(end + 1)
                pattern = pattern.substring(1, end)
                if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
                if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
                if ('m' in flags) options.add(RegexOption.MULTILINE)
            }
        }
        return runCatching { Regex(pattern, options).find(value)?.groupValues?.toTypedArray() }.getOrNull() ?: emptyArray()
    }

    fun get(value: Any?, key: String): String? {
        val raw = if (value is Wrapper) value.unwrap() else value
        return when (raw) {
            null, is Undefined -> null
            is NativeObject -> if (raw.containsKey(key)) stringify(raw.get(key, raw)) else null
            is Map<*, *> -> stringify(raw[key])
            else -> Regex("""(?:^|[,{]\s*)${Regex.escape(key)}\s*[:=]\s*([^,}]+)""")
                .find(raw.toString())?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
        }
    }

    private fun stringify(value: Any?): String? = when (value) {
        null, is Undefined -> null
        is Number -> Context.toString(value)
        else -> value.toString()
    }
}
