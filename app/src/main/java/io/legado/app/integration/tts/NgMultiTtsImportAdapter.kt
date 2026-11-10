package io.legado.app.integration.tts

import android.content.Context
import android.net.Uri
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.help.tts.TtsEngineType
import io.legado.app.help.tts.TtsEngineImportConflictAction
import io.legado.app.help.tts.TtsVoice
import io.legado.app.integration.FeatureBootstrap
import io.legado.app.utils.GSON
import io.legado.features.api.tts.ImportedVoice
import io.legado.features.api.tts.MultiTtsImportResult
import io.legado.features.api.tts.ScriptEngineDraft
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.PushbackInputStream
import kotlin.coroutines.CoroutineContext

/** Only NG mapping and host storage lookup. Parsing, merging and templates stay in lib. */
object NgMultiTtsImportAdapter {
    sealed interface FileImport {
        data class Text(val content: String) : FileImport
        data class Backup(val preview: MultiTtsImportResult) : FileImport
    }

    fun readUri(uri: Uri, host: Context, context: CoroutineContext): FileImport {
        val input = if (uri.scheme == "content") host.contentResolver.openInputStream(uri)
        else io.legado.app.utils.RealPathUtil.getPath(host, uri)?.let { java.io.FileInputStream(it) }
        return (input ?: error("打开文件失败")).use { read(it, context) }
    }

    fun read(input: InputStream, context: CoroutineContext, namespace: String = "default"): FileImport {
        val stream = PushbackInputStream(input, 4)
        val prefix = ByteArray(4)
        var size = 0
        while (size < prefix.size) {
            context.ensureActive()
            val n = stream.read(prefix, size, prefix.size - size)
            if (n < 0) break
            size += n
        }
        stream.unread(prefix, 0, size)
        if (size >= 2 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()) {
            return FileImport.Backup(FeatureBootstrap.features.importMultiTts(stream, namespace) { context.ensureActive() })
        }
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            context.ensureActive()
            val n = stream.read(buffer)
            if (n < 0) break
            require(out.size() + n <= 8 * 1024 * 1024) { "朗读引擎配置超过 8 MiB" }
            out.write(buffer, 0, n)
        }
        return FileImport.Text(out.toString("UTF-8").removePrefix("\uFEFF"))
    }

    data class ImportPayload(val content: String, val conflictAction: TtsEngineImportConflictAction)

    fun importPayload(preview: MultiTtsImportResult, mergeExisting: Boolean): ImportPayload {
        val independentNamespace = java.util.UUID.randomUUID().toString()
        val content = GSON.toJson(preview.engines.map { original ->
            val draft = if (mergeExisting) original else FeatureBootstrap.features.independentMultiTts(original, independentNamespace)
            val oldVoices = if (mergeExisting) TtsEngineStore.engine(draft.id)?.effectiveVoices().orEmpty() else emptyList()
            toSetting(FeatureBootstrap.features.mergeMultiTts(draft, oldVoices.map(::toImportedVoice)))
        })
        return ImportPayload(
            content = content,
            conflictAction = if (mergeExisting) TtsEngineImportConflictAction.OVERWRITE else TtsEngineImportConflictAction.ASK,
        )
    }

    fun projectCatalog(engines: List<TtsEngineSetting>) {
        engines.filter { it.id.startsWith("multitts:") && it.voices.isNotEmpty() }.forEach {
            TtsEngineStore.upsertVoiceList(it.id, it.voices, restartReadAloud = false)
        }
    }

    internal fun toSetting(draft: ScriptEngineDraft) = TtsEngineSetting(
        id = draft.id,
        name = draft.name,
        type = TtsEngineType.SCRIPT,
        script = draft.script,
        contentType = draft.contentType,
        voices = draft.voices.map { voice -> TtsVoice(voice.id, voice.name, voice.language, voice.gender, extra = GSON.toJsonTree(voice.extra).asJsonObject) },
    )

    private fun toImportedVoice(voice: TtsVoice): ImportedVoice = ImportedVoice(
        voice.id, voice.name, voice.language, voice.gender,
        voice.extra?.entrySet()?.associate { it.key to GSON.fromJson(it.value, Any::class.java) }.orEmpty(),
    )
}
