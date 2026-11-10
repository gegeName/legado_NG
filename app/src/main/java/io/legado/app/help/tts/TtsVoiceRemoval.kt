package io.legado.app.help.tts

/** 同步过滤静态与运行时目录，尤其避免删除最后一个音色后回退到静态目录。 */
internal fun TtsEngineSetting.withoutVoices(ids: Set<String>): TtsEngineSetting {
    val remaining = effectiveVoices().filterNot { it.id in ids }
    return copy(
        voices = remaining,
        runtimeVoices = remaining,
        disabledVoiceIds = disabledVoiceIds.filterNot { it in ids },
        activeVoiceId = activeVoiceId?.takeUnless { it in ids }
            ?: remaining.firstOrNull { isVoiceEnabled(it) }?.id,
        voiceParams = voiceParams.filterKeys { it !in ids },
    )
}
