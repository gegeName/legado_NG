package io.legado.features.api.host

import java.io.Closeable
import java.io.File

interface ConfigPort {
    fun get(namespace: String, key: String): String?
    fun put(namespace: String, key: String, value: String?)
}

fun interface LogPort {
    fun log(module: String, message: String, error: Throwable?)
}

data class ImageResourceKey(
    val source: io.legado.features.api.source.SourceKey,
    val bookId: String,
    val resourceId: String,
    val configurationFingerprint: String,
    val contentVersion: String = "",
)

/** Each consumer owns a lease. Closing one lease must not cancel another consumer. */
interface FileLease : Closeable {
    val file: File
    val version: String
}

enum class ResourcePriority { VISIBLE, USER_REQUEST, NEAR_PREFETCH, PREFETCH }

interface ImageResourceResolver {
    suspend fun resolve(key: ImageResourceKey, priority: ResourcePriority): FileLease
}

/** Download/decrypt/validation use the host pipeline. Publication is a short synchronous rename. */
interface ImageCachePort {
    fun target(key: ImageResourceKey): File
    fun cached(key: ImageResourceKey): File?
    suspend fun download(key: ImageResourceKey): File
    fun publish(key: ImageResourceKey, temporary: File): File
    fun discard(temporary: File) { temporary.delete() }
}
