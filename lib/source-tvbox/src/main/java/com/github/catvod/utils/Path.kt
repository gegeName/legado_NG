package com.github.catvod.utils

import com.github.catvod.Init
import java.io.File
import java.io.InputStream

/** Compatibility storage is limited to this feature's private directory. */
object Path {
    private val namespace = ThreadLocal<String?>()
    fun <T> bound(value: String, action: () -> T): T {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val previous = namespace.get(); namespace.set(value)
        try { return action() } finally { namespace.set(previous) }
    }
    @JvmStatic fun root(): File {
        val base = File(checkNotNull(Init.context()).filesDir, "feature_tvbox")
        return namespace.get()?.let { File(base, it) }?.apply { mkdirs() } ?: base.apply { mkdirs() }
    }
    private fun inside(root: File, name: String): File {
        val path = File(name.removePrefix("file://"))
        val file = (if (path.isAbsolute) path else File(root, path.path)).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath())) { "TVBox 路径越界" }
        return file
    }
    @JvmStatic fun cache(): File = File(root(), "cache").apply { mkdirs() }
    @JvmStatic fun files() = root()
    @JvmStatic fun rootPath() = root().path
    @JvmStatic fun root(name: String) = inside(root(), name)
    @JvmStatic fun files(name: String) = inside(files(), name)
    @JvmStatic fun cache(name: String) = inside(cache(), name)
    @JvmStatic fun local(path: String) = inside(root(), path)
    @JvmStatic fun jar(): File = File(cache(), "jar").apply { mkdirs() }
    @JvmStatic fun jar(name: String) = File(jar(), Crypto.md5(name) + ".jar")
    @JvmStatic fun js(): File = File(cache(), "js").apply { mkdirs() }
    @JvmStatic fun js(name: String) = inside(js(), name)
    @JvmStatic fun exists(file: File?) = file?.isFile == true && file.length() > 0
    @JvmStatic fun exists(path: String?) = path != null && exists(local(path))
    @JvmStatic fun create(file: File): File = checked(file).apply { parentFile?.mkdirs(); if (!exists()) createNewFile() }
    private fun checked(file: File): File = file.canonicalFile.also { require(it.toPath().startsWith(root().canonicalFile.toPath())) { "TVBox 路径越界" } }
    @JvmStatic fun read(file: File?): String = file?.let { checked(it).readText() }.orEmpty()
    @JvmStatic fun read(stream: InputStream?): String = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    @JvmStatic fun readToByte(file: File?): ByteArray = file?.let { checked(it).readBytes() } ?: byteArrayOf()
    @JvmStatic fun write(file: File, data: ByteArray?): File = create(file).apply { writeBytes(data ?: byteArrayOf()) }
    @JvmStatic fun write(file: File, stream: InputStream?): File = create(file).apply { stream?.use { input -> outputStream().use { input.copyTo(it) } } }
    @JvmStatic fun clear(file: File?) { file?.let { checked(it).deleteRecursively() } }
    @JvmStatic fun list(file: File?): List<File> = file?.let { checked(it).listFiles()?.toList() }.orEmpty()
}
