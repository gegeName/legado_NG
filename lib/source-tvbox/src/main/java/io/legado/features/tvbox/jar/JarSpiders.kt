package io.legado.features.tvbox.jar

import android.content.Context
import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import com.github.catvod.utils.Crypto
import dalvik.system.DexClassLoader
import io.legado.features.tvbox.TvBoxHttp
import io.legado.features.tvbox.config.*
import io.legado.features.tvbox.provider.SpiderPort
import java.io.File
import java.security.MessageDigest

class JarSpiders(private val context: Context, private val root: File, private val http: TvBoxHttp) : AutoCloseable {
    private val loaders = mutableMapOf<String, ClassLoader>()
    private val instances = mutableListOf<Spider>()
    private val refreshNamespaces = mutableSetOf<String>()
    fun invalidate(namespace: String) { refreshNamespaces += namespace; loaders.keys.removeAll { it.startsWith("$namespace:") } }
    /** Content fingerprint, not URL/versionCode, owns the classloader identity. */
    fun open(config: TvBoxConfig, site: TvBoxSite, refresh: Boolean = false): SpiderPort {
        Init.set(context)
        val spec = site.jar.ifBlank { config.spider }
        require(spec.isNotBlank()) { "TVBox 缺少 Spider JAR" }
        val uri = spec.substringBefore(";md5;")
        var md5 = spec.substringAfter(";md5;", "").trim()
        if (md5.startsWith("http")) md5 = http.text(md5, limit = 256).trim()
        require(md5.isBlank() || md5.matches(Regex("[a-fA-F0-9]{32}"))) { "Spider MD5 格式无效" }
        val directory = File(root, config.namespace).apply { mkdirs() }
        val target = File(directory, Crypto.md5(uri) + ".jar")
        if (refresh || config.namespace in refreshNamespaces || !target.isFile || md5.isNotBlank() && !Crypto.equals(target, md5)) {
            val bytes = if (uri.startsWith("file:")) {
                val local = File(java.net.URI(uri)).canonicalFile
                require(local.toPath().startsWith(root.parentFile!!.canonicalFile.toPath()) && local.isFile) { "本地 JAR 必须位于 TVBox 私有目录" }
                require(local.length() in 1..64L * 1024 * 1024) { "本地 JAR 大小无效" }
                local.readBytes()
            } else http.execute(okhttp3.Request.Builder().url(uri).build(), 64 * 1024 * 1024) { response, data ->
                check(response.isSuccessful) { "Spider JAR HTTP ${response.code}" }; data
            }
            val temporary = File.createTempFile(".jar-", ".tmp", directory)
            try {
                java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                require(md5.isBlank() || Crypto.equals(temporary, md5)) { "Spider JAR MD5 不匹配" }
                check(temporary.renameTo(target) || target.delete() && temporary.renameTo(target)) { "Spider JAR 发布失败" }
            } finally { temporary.delete() }
        }
        require(target.length() in 1..64L * 1024 * 1024) { "Spider JAR 大小无效" }
        val fingerprint = target.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(16384)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        val loader = loaders.getOrPut("${config.namespace}:$fingerprint") {
            val versioned = File(directory, "$fingerprint.jar")
            if (!versioned.exists()) target.copyTo(versioned)
            check(versioned.setReadOnly()) { "Spider JAR 无法设为只读" }
            refreshNamespaces.remove(config.namespace)
            DexClassLoader(versioned.path, context.codeCacheDir.path, null, Spider::class.java.classLoader).also { loader ->
                val init = try { loader.loadClass("com.github.catvod.spider.Init") } catch (_: ClassNotFoundException) { null }
                if (init != null) {
                    val method = try { init.getMethod("init", Context::class.java) } catch (_: NoSuchMethodException) { null }
                    method?.invoke(null, context)
                }
            }
        }
        val name = site.api.removePrefix("csp_")
        val klass = try { loader.loadClass("com.github.catvod.spider.$name") } catch (_: ClassNotFoundException) { loader.loadClass(name) }
        val spider = klass.getDeclaredConstructor().newInstance() as? Spider ?: error("Spider ABI 不匹配")
        spider.siteKey = site.key
        try { spider.init(context, site.ext) } catch (e: Throwable) { spider.destroy(); throw e }
        instances += spider
        return object : SpiderPort {
            override fun home(filter: Boolean) = spider.homeContent(filter)
            override fun category(id: String, page: Int, extend: Map<String, String>) = spider.categoryContent(id, page.toString(), true, HashMap(extend))
            override fun search(query: String, page: Int) = spider.searchContent(query, false, page.toString()).ifBlank {
                if (page == 1) spider.searchContent(query, false) else "{\"list\":[],\"pagecount\":1}"
            }
            override fun detail(id: String) = spider.detailContent(listOf(id))
            override fun play(flag: String, id: String, flags: List<String>) = spider.playerContent(flag, id, flags)
            override fun parse(parser: TvBoxParse, all: List<TvBoxParse>, flag: String, url: String) = JarParsers.invoke(loader, parser, all, flag, url)
            override fun proxy(params: Map<String, String>): Array<Any?>? {
                spider.proxy(params)?.let { return it }
                val proxy = try { loader.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map::class.java) }
                    catch (_: ClassNotFoundException) { return null } catch (_: NoSuchMethodException) { return null }
                @Suppress("UNCHECKED_CAST") return proxy.invoke(null, params) as? Array<Any?>
            }
            override fun close() { if (instances.remove(spider)) spider.destroy() }
        }
    }
    override fun close() { instances.toList().forEach { runCatching { it.destroy() } }; instances.clear(); loaders.clear(); refreshNamespaces.clear() }
}
