package io.legado.features.multitts

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.features.api.tts.JsonResponseValidation

object JsonResponseValidator {
    fun validate(body: String, rule: JsonResponseValidation) {
        val root = try { JsonParser.parseString(body) } catch (_: RuntimeException) {
            throw IllegalArgumentException("TTS 响应不是有效 JSON")
        }
        require(root.isJsonObject) { "TTS 响应不是 JSON 对象" }
        val status = field(root, rule.statusPath) ?: return
        require(status.isJsonPrimitive) { "TTS 业务状态格式非法" }
        val value = status.asString
        val success = value == rule.successValue || value.toBigDecimalOrNull()?.let { actual ->
            rule.successValue.toBigDecimalOrNull()?.let { actual.compareTo(it) == 0 }
        } == true
        if (!success) {
            val message = rule.messagePath?.let { field(root, it) }?.takeIf { it.isJsonPrimitive }?.asString
            throw IllegalArgumentException("TTS 业务错误 $value: ${message.orEmpty().take(300)}")
        }
    }

    private fun field(root: JsonElement, path: String): JsonElement? {
        require(path.length <= 256 && path.matches(Regex("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*){0,7}"))) { "响应校验路径非法" }
        var current = root
        for (part in path.split('.')) current = current.takeIf { it.isJsonObject }?.asJsonObject?.get(part) ?: return null
        return current.takeUnless { it.isJsonNull }
    }
}
