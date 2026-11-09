package io.legado.features.api.source

/** Native IDs are opaque: a numeric ID can identify both a manga and a video source. */
data class SourceKey(val provider: String, val contentKind: ContentKind, val nativeId: String, val namespace: String = "") {
    init {
        require(provider.isNotBlank())
        require(nativeId.isNotBlank())
    }
}

enum class ContentKind { TEXT, MANGA, AUDIO, VIDEO }

data class CompatibilityStamp(val adapter: String, val version: Int)

/** Versioned interchange JSON, not a copy of the application's entity or database schema. */
data class ConvertedSourceDefinition(val json: String, val compatibility: CompatibilityStamp)

sealed interface ImportResult {
    data object NotHandled : ImportResult
    data class Converted(val sources: List<ConvertedSourceDefinition>) : ImportResult
    data class Failed(val message: String, val cause: Throwable? = null) : ImportResult
}

interface SourceImportAdapter {
    fun importSource(text: String): ImportResult
}

enum class FeatureErrorKind { NETWORK, AUTHENTICATION, EXTENSION_MISSING, UNSUPPORTED_RUNTIME, RULE, EMPTY_RESULT }

/** A selected provider failure must never be mistaken for NotHandled. */
sealed interface FeatureResult<out T> {
    data object NotHandled : FeatureResult<Nothing>
    data class Success<T>(val value: T) : FeatureResult<T>
    data class Failure(val kind: FeatureErrorKind, val message: String) : FeatureResult<Nothing>
}
