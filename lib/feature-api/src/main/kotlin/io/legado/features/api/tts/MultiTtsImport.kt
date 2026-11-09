package io.legado.features.api.tts

data class ImportedVoice(
    val id: String,
    val name: String,
    val language: String?,
    val gender: String?,
    val extra: Map<String, Any?>,
)

data class ImportDiagnostic(val location: String, val message: String)

/** A draft can be previewed and exported without starting a synthesizer or changing casting. */
data class ScriptEngineDraft(
    val id: String,
    val name: String,
    val provider: String,
    val script: String,
    val contentType: String,
    val voices: List<ImportedVoice>,
    val originalEngine: Map<String, Any?>,
    val diagnostics: List<ImportDiagnostic> = emptyList(),
)

data class MultiTtsImportResult(
    val engines: List<ScriptEngineDraft>,
    val diagnostics: List<ImportDiagnostic>,
)

/** Optional fixed field lookup, never an expression or script. Missing status is permitted. */
data class JsonResponseValidation(
    val statusPath: String,
    val successValue: String = "0",
    val messagePath: String? = null,
)
