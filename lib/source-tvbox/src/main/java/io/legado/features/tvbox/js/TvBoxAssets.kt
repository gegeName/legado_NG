package io.legado.features.tvbox.js

import android.content.Context
import com.github.catvod.utils.Crypto
import io.legado.features.tvbox.TvBoxHttp
import java.io.File

/** Only the upstream adapter's pinned built-in dependencies may use a remote asset fallback. */
internal class TvBoxAssets(private val context: Context, private val http: TvBoxHttp) {
    fun read(path: String): String {
        require(path.isNotBlank() && path.split('/').none { it == ".." } && !path.startsWith('/'))
        try { return context.assets.open(path).bufferedReader().use { it.readText() } }
        catch (_: java.io.IOException) { }
        val digest = checkNotNull(DIGESTS[path]) { "未打包且不在固定依赖清单的 JS 资源：$path" }
        val directory = File(context.filesDir, "feature_tvbox/builtin-js").apply { mkdirs() }
        val file = File(directory, "$digest.js")
        if (file.isFile && file.length() <= 4 * 1024 * 1024 && Crypto.md5(file) == digest) return file.readText()
        val source = http.text("https://raw.githubusercontent.com/FongMi/TV/c616c0aa3613e87529791587a9f71b78c278c991/quickjs/src/main/assets/$path", limit = 4 * 1024 * 1024)
        require(Crypto.md5(source) == digest) { "内置 JS 资源校验失败：$path" }
        val temporary = File.createTempFile(".js-", ".tmp", directory)
        try { temporary.writeText(source); check(temporary.renameTo(file) || file.delete() && temporary.renameTo(file)) }
        finally { temporary.delete() }
        return source
    }
    private companion object {
        val DIGESTS = mapOf(
            "js/lib/cat.js" to "ce5c0ecf92f7507c3b65c1ecbc167f95",
            "js/lib/cheerio.min.js" to "f4f72962fb5d6e15d4e32b39c02056f0",
            "js/lib/crypto-js.js" to "0290d675a485e70ff76879e17ecec46a",
            "js/lib/gbk.js" to "d5a05799eeeceb81cadd77e80f2807c8",
            "js/lib/http.js" to "b129d2ae9828694104b7b575588903e5",
            "js/lib/similarity.js" to "e5f3fe2ba5423aa0598508e2f6968dd4",
            "js/lib/spider.js" to "36ac57a8f42dea81bb3428b850e3d2da")
    }
}
