package io.legado.app.ui.config

import android.media.MediaExtractor
import android.media.MediaFormat
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsScriptEngineClient
import io.legado.app.help.tts.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** 使用实际合成请求检查音频轨道，不播放声音，也不接受仅 HTTP 成功的错误正文。 */
internal suspend fun probeTtsVoice(
    cacheDir: File,
    engine: TtsEngineSetting,
    voice: TtsVoice,
): String? = try {
    withTimeout(30_000L.milliseconds) {
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("voice_check_", ".audio", cacheDir)
            try {
                TtsScriptEngineClient.getSynthesisResponse(
                    engine = engine,
                    text = TtsScriptEngineClient.sampleText(engine, voice),
                    voiceId = voice.id,
                    coroutineContext = currentCoroutineContext(),
                ).use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val source = response.body.source()
                    source.timeout().timeout(10, TimeUnit.SECONDS)
                    source.timeout().deadline(15, TimeUnit.SECONDS)
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count == -1) break
                            total += count
                            check(total <= 8 * 1024 * 1024) { "音频响应过大" }
                            output.write(buffer, 0, count)
                        }
                        check(total > 0) { "未返回音频" }
                    }
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    }
                    check(track != null) { "返回内容不是可识别的音频" }
                    extractor.selectTrack(track)
                    check(extractor.readSampleData(java.nio.ByteBuffer.allocate(256 * 1024), 0) > 0) { "音频没有有效数据" }
                } finally {
                    extractor.release()
                }
            } finally {
                file.delete()
            }
        }
    }
    null
} catch (_: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    "检测超时"
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    error.localizedMessage?.take(160) ?: error.javaClass.simpleName
}
