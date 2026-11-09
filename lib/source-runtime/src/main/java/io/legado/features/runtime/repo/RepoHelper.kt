@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.legado.features.runtime.repo

import io.legado.features.runtime.ExtensionException
import io.legado.features.runtime.model.MangaExtension
import io.legado.features.runtime.model.MangaExtensionRepo
import io.legado.features.runtime.model.NetworkExtensionStore
import io.legado.features.runtime.model.NetworkLegacyExtension
import io.legado.features.runtime.model.NetworkRepoInfo
import io.legado.features.runtime.model.RepoKind
import io.legado.features.runtime.model.toAvailable
import io.legado.features.runtime.runtimeHttpClient
import io.legado.features.runtime.RuntimeFilesJsonStore
import io.legado.features.runtime.RuntimeJson as GSON
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.zip.GZIPInputStream
import io.legado.features.runtime.withRuntimeResponse
import io.legado.features.runtime.readBounded

internal object RepoHelper {

    val defaultRepo = MangaExtensionRepo(
        indexUrl = DEFAULT_MANGA_INDEX_URL,
        name = DEFAULT_MANGA_REPO_NAME,
        signingKeyFingerprint = DEFAULT_MANGA_FINGERPRINT,
        isDefault = true,
        kind = RepoKind.MANGA,
    )

    val defaultAnimeRepo = MangaExtensionRepo(
        indexUrl = DEFAULT_ANIME_INDEX_URL,
        name = DEFAULT_ANIME_REPO_NAME,
        signingKeyFingerprint = DEFAULT_ANIME_FINGERPRINT,
        isDefault = true,
        kind = RepoKind.ANIME,
    )

    private const val DEFAULT_MANGA_INDEX_URL =
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
    private const val DEFAULT_MANGA_REPO_NAME = "Keiyoushi"
    private const val DEFAULT_MANGA_FINGERPRINT =
        "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    private const val DEFAULT_ANIME_INDEX_URL =
        "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
    private const val DEFAULT_ANIME_REPO_NAME = "Yuzono"
    private const val DEFAULT_ANIME_FINGERPRINT =
        "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"

    private const val INDEX_FILE_NAME = "mangaExtensionIndex.json"
    private const val REPO_FILE_SUFFIX = "repo.json"

    private val INDEX_URL_REGEX = Regex("""^https://.*/index\.(min\.json|pb)$""")

    fun normalizeIndexUrl(raw: String): String {
        val input = raw.trim().ifEmpty { throw ExtensionException("仓库地址为空") }
        val url = input.toHttpUrlOrNull()?.toString()
            ?: throw ExtensionException("仓库地址无效: $input")
        if (!INDEX_URL_REGEX.matches(url)) {
            throw ExtensionException("仓库地址须完整指向 index.min.json 或 index.pb: $input")
        }
        return url
    }

    suspend fun fetchRepoMeta(indexUrl: String): Pair<String, String> {
        val repoJsonUrl = indexUrl.substringBeforeLast('/') + "/" + REPO_FILE_SUFFIX
        val body = httpGetBytes(repoJsonUrl)
        val info = runCatching { GSON.decodeFromString(NetworkRepoInfo.serializer(), body.decodeToString()) }
            .getOrElse { throw ExtensionException("仓库元信息解析失败: $repoJsonUrl") }
        val meta = info.meta ?: throw ExtensionException("仓库元信息缺失: $repoJsonUrl")
        return meta.name to meta.signingKeyFingerprint
    }

    suspend fun fetchIndex(repo: MangaExtensionRepo): List<MangaExtension.Available> {
        val bytes = decompressIfGzipped(httpGetBytes(repo.indexUrl))
        val items = when (bytes.firstOrNull()) {
            '['.code.toByte() -> parseLegacyIndex(repo, bytes)
            '{'.code.toByte() -> throw ExtensionException("不支持的新版 JSON 索引: ${repo.name}")
            else -> parseProtoIndex(repo, bytes)
        }
        if (items.isEmpty()) {
            throw ExtensionException("扩展索引为空: ${repo.name}")
        }
        return items
    }

    private fun parseLegacyIndex(
        repo: MangaExtensionRepo,
        bytes: ByteArray,
    ): List<MangaExtension.Available> {
        val items = runCatching {
            GSON.decodeFromString(ListSerializer(NetworkLegacyExtension.serializer()), bytes.decodeToString())
        }.getOrElse { throw ExtensionException("扩展索引解析失败: ${repo.name}") }
        return items.map { it.toAvailable(repo) }
    }

    private fun parseProtoIndex(
        repo: MangaExtensionRepo,
        bytes: ByteArray,
    ): List<MangaExtension.Available> {
        val store = runCatching {
            ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
        }.getOrElse { throw ExtensionException("扩展索引解析失败 (protobuf): ${repo.name}") }
        return store.extensionList?.toAvailable(repo).orEmpty()
    }

    private fun decompressIfGzipped(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        return GZIPInputStream(bytes.inputStream()).use { it.readBounded(MAX_INDEX_BYTES) }
    }

    fun loadCachedIndex(): List<MangaExtension.Available> {
        val json = RuntimeFilesJsonStore.readText(INDEX_FILE_NAME) ?: return emptyList()
        return runCatching {
            GSON.decodeFromString(ListSerializer(MangaExtension.Available.serializer()), json)
        }.getOrNull().orEmpty()
    }

    fun saveCachedIndex(list: List<MangaExtension.Available>) {
        RuntimeFilesJsonStore.writeText(
            INDEX_FILE_NAME,
            GSON.encodeToString(ListSerializer(MangaExtension.Available.serializer()), list),
        )
    }

    private const val MAX_INDEX_BYTES = 32 * 1024 * 1024

    private suspend fun httpGetBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        return withRuntimeResponse(request) { response ->
            response.body.byteStream().use { it.readBounded(MAX_INDEX_BYTES) }
        }
    }
}

