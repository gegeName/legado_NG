package io.legado.features.multitts

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Does not extract entries or instantiate arbitrary YAML classes. The caller owns [input]. */
internal object BackupReader {
    private const val YAML_LIMIT = 2 * 1024 * 1024
    private const val ENTRY_LIMIT = 8 * 1024 * 1024
    private const val ZIP_LIMIT = 32 * 1024 * 1024
    private const val TOTAL_LIMIT = 64 * 1024 * 1024

    fun read(input: InputStream, checkCancelled: () -> Unit): Pair<Map<String, Any?>, Map<String, Any?>> {
        val documents = linkedMapOf<String, Map<String, Any?>>()
        var compressed = 0L
        val bounded = object : FilterInputStream(input) {
            override fun read(): Int {
                checkCancelled()
                return super.read().also { if (it >= 0) { compressed++; require(compressed <= ZIP_LIMIT) { "ZIP 超过 32 MiB" } } }
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkCancelled()
                return `in`.read(bytes, offset, length).also {
                    if (it > 0) { compressed += it; require(compressed <= ZIP_LIMIT) { "ZIP 超过 32 MiB" } }
                }
            }
            override fun close() = Unit
        }
        ZipInputStream(bounded).use { zip ->
            var total = 0L
            var count = 0
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                require(++count <= 1024) { "ZIP 条目过多" }
                val path = entry.name.replace('\\', '/')
                require(!path.startsWith('/') && !path.contains(':') && path.split('/').none { it == ".." }) { "ZIP 路径非法" }
                val wanted = path == "config.yaml" || path == "engines.yaml"
                require(!wanted || path !in documents) { "ZIP 重复条目: $path" }
                val buffer = ByteArray(8192)
                val text = if (wanted) java.io.ByteArrayOutputStream() else null
                var size = 0
                while (true) {
                    checkCancelled()
                    val n = zip.read(buffer)
                    if (n < 0) break
                    size += n; total += n
                    require(size <= (if (wanted) YAML_LIMIT else ENTRY_LIMIT) && total <= TOTAL_LIMIT) { "ZIP 解压体积超限" }
                    text?.write(buffer, 0, n)
                }
                if (wanted) documents[path] = parse(text!!.toString("UTF-8"))
                zip.closeEntry()
            }
        }
        return (documents["engines.yaml"] ?: error("备份缺少 engines.yaml")) to
            (documents["config.yaml"] ?: error("备份缺少 config.yaml"))
    }

    private fun parse(text: String): Map<String, Any?> {
        val limits = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = 32
            codePointLimit = YAML_LIMIT
            tagInspector = org.yaml.snakeyaml.inspector.TagInspector { tag ->
                tag.value in setOf("tag:yaml.org,2002:org.nobody.multitts.tts.engine.EngineConfig",
                    "tag:yaml.org,2002:org.nobody.multitts.tts.speaker.Speaker")
            }
        }
        val constructor = object : SafeConstructor(limits) {
            init {
                listOf("org.nobody.multitts.tts.engine.EngineConfig", "org.nobody.multitts.tts.speaker.Speaker").forEach {
                    yamlConstructors[Tag("tag:yaml.org,2002:$it")] = ConstructYamlMap()
                }
            }
        }
        val parsed = Yaml(constructor).load<Any?>(text.removePrefix("\uFEFF"))
        return objectMap(parsed)
    }

    internal fun objectMap(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "应为字符串字段的 YAML 对象" }
        return value.entries.associate { it.key as String to it.value }
    }
}
