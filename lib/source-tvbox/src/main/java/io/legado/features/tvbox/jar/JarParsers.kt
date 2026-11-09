package io.legado.features.tvbox.jar

import io.legado.features.tvbox.config.TvBoxParse

/** Parser lookup is tied to the owning Spider loader, never a global 'recent JAR'. */
internal object JarParsers {
    fun invoke(loader: ClassLoader, parser: TvBoxParse, all: List<TvBoxParse>, flag: String, url: String): String {
        require(parser.type in 2..3)
        val prefix = if (parser.type == 2) "Json" else "Mix"
        val type = loader.loadClass("com.github.catvod.parser.$prefix${parser.url}")
        val result = if (parser.type == 2) {
            val options = linkedMapOf<String, String>()
            all.filter { it.type == 1 }.forEach { options[it.name] = it.extendedUrl() }
            type.getMethod("parse", LinkedHashMap::class.java, String::class.java).invoke(null, options, url)
        } else {
            val options = linkedMapOf<String, Map<String, String>>()
            all.forEach { options[it.name] = linkedMapOf("type" to it.type.toString(), "ext" to it.ext, "url" to it.url) }
            type.getMethod("parse", LinkedHashMap::class.java, String::class.java, String::class.java, String::class.java).invoke(null, options, parser.name, flag, url)
        }
        return checkNotNull(result) { "JAR 聚合解析返回为空" }.toString()
    }
}
