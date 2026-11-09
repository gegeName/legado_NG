package io.legado.features.translation

import android.content.Context
import io.legado.features.api.host.ConfigPort
import io.legado.features.api.host.LogPort
import io.legado.features.api.manga.AiTextTranslationPort
import okhttp3.OkHttpClient
import java.io.File

interface TranslationPlatform {
    val context: Context
    val filesDirectory: File
    val cacheDirectory: File
    val config: ConfigPort
    val client: OkHttpClient
    val ai: AiTextTranslationPort?
    val log: LogPort
}
