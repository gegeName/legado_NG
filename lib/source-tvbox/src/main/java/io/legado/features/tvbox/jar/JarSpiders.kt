package io.legado.features.tvbox.jar

import android.content.Context
import android.util.Log
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
    private companion object { const val TAG = "TvBoxSpiderTrace" }

    private val loaders = mutableMapOf<String, ClassLoader>()
    private val instances = mutableListOf<Spider>()
    private val refreshNamespaces = mutableSetOf<String>()
    fun invalidate(namespace: String) { refreshNamespaces += namespace; loaders.keys.removeAll { it.startsWith("$namespace:") } }
    /** Content fingerprint, not URL/versionCode, owns the classloader identity. */
    fun open(config: TvBoxConfig, site: TvBoxSite, refresh: Boolean = false): SpiderPort {
        Init.set(context)
        Log.i(TAG, "open site=${site.key} namespace=${config.namespace} context=${describe(Init.context())}")
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
                Log.i(TAG, "loader created site=${site.key} loader=${identity(loader)} jarBytes=${versioned.length()}")
                val init = try { loader.loadClass("com.github.catvod.spider.Init") } catch (_: ClassNotFoundException) { null }
                if (init != null) {
                    val method = try { init.getMethod("init", Context::class.java) } catch (_: NoSuchMethodException) { null }
                    Log.i(TAG, "jarInit site=${site.key} classLoader=${identity(init.classLoader)} method=${method != null} context=${describe(Init.context())}")
                    method?.invoke(null, context)
                    Log.i(TAG, "jarInit returned site=${site.key} jarContext=${describeJarContext(loader)}")
                } else {
                    Log.i(TAG, "jarInit absent site=${site.key} loader=${identity(loader)}")
                }
            }
        }
        Log.i(TAG, "loader ready site=${site.key} loader=${identity(loader)} jarContext=${describeJarContext(loader)}")
        val name = site.api.removePrefix("csp_")
        val klass = try { loader.loadClass("com.github.catvod.spider.$name") } catch (_: ClassNotFoundException) { loader.loadClass(name) }
        val spider = klass.getDeclaredConstructor().newInstance() as? Spider ?: error("Spider ABI 不匹配")
        Log.i(TAG, "spider constructed site=${site.key} class=${klass.name} classLoader=${identity(klass.classLoader)}")
        spider.siteKey = site.key
        try {
            trace(site.key, "init") { spider.init(context, site.ext) }
        } catch (e: Throwable) { spider.destroy(); throw e }
        instances += spider
        return object : SpiderPort {
            override fun home(filter: Boolean) = trace(site.key, "home") { spider.homeContent(filter) }
            override fun category(id: String, page: Int, extend: Map<String, String>) = trace(site.key, "category") {
                spider.categoryContent(id, page.toString(), true, HashMap(extend))
            }
            override fun search(query: String, page: Int) = trace(site.key, "search") {
                spider.searchContent(query, false, page.toString()).ifBlank {
                    if (page == 1) spider.searchContent(query, false) else "{\"list\":[],\"pagecount\":1}"
                }
            }
            override fun detail(id: String) = trace(site.key, "detail") { spider.detailContent(listOf(id)) }
            override fun play(flag: String, id: String, flags: List<String>) = trace(site.key, "play") {
                spider.playerContent(flag, id, flags)
            }
            override fun parse(parser: TvBoxParse, all: List<TvBoxParse>, flag: String, url: String) = trace(site.key, "parse") {
                JarParsers.invoke(loader, parser, all, flag, url)
            }
            override fun proxy(params: Map<String, String>): Array<Any?>? {
                return trace(site.key, "proxy") {
                    spider.proxy(params)?.let { return@trace it }
                    val proxy = try { loader.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map::class.java) }
                        catch (_: ClassNotFoundException) { return@trace null } catch (_: NoSuchMethodException) { return@trace null }
                    @Suppress("UNCHECKED_CAST")
                    proxy.invoke(null, params) as? Array<Any?>
                }
            }
            override fun close() { if (instances.remove(spider)) spider.destroy() }
        }
    }

    private inline fun <T> trace(site: String, operation: String, action: () -> T): T {
        val started = System.nanoTime()
        Log.i(TAG, "begin site=$site op=$operation thread=${Thread.currentThread().name} hostContext=${describe(Init.context())}")
        return try {
            action().also { result ->
                Log.i(TAG, "end site=$site op=$operation elapsedMs=${(System.nanoTime() - started) / 1_000_000} result=${result?.javaClass?.simpleName ?: "null"}")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "failed site=$site op=$operation elapsedMs=${(System.nanoTime() - started) / 1_000_000}", error)
            throw error
        }
    }

    private fun describe(context: Context?): String = context?.let { "${it.javaClass.name}@${identity(it)}" } ?: "null"

    private fun describeJarContext(loader: ClassLoader): String = runCatching {
        val init = loader.loadClass("com.github.catvod.spider.Init")
        describe(init.getMethod("context").invoke(null) as? Context)
    }.getOrElse { "unavailable:${it.javaClass.simpleName}" }

    private fun identity(value: Any?): String = value?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "null"

    override fun close() { instances.toList().forEach { runCatching { it.destroy() } }; instances.clear(); loaders.clear(); refreshNamespaces.clear() }
}
