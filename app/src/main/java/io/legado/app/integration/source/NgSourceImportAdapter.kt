package io.legado.app.integration.source

import com.google.gson.JsonObject
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.integration.FeatureBootstrap
import io.legado.app.utils.GSON
import io.legado.features.api.source.ImportResult

object NgSourceImportAdapter {
    fun decompress(text: String): String = FeatureBootstrap.features.decompressSource(text)

    fun convertOrNull(text: String): List<BookSource>? = when (val result = FeatureBootstrap.features.importSource(text)) {
        ImportResult.NotHandled -> null
        is ImportResult.Failed -> throw NoStackTraceException(result.message)
        is ImportResult.Converted -> result.sources.map { GSON.fromJson(it.json, BookSource::class.java) }
    }

    /** Called per record, preserving NG's bounded-memory import/preview/store pipeline. */
    fun decode(root: JsonObject): BookSource =
        convertOrNull(root.toString())?.single() ?: GSON.fromJson(root, BookSource::class.java)
}
