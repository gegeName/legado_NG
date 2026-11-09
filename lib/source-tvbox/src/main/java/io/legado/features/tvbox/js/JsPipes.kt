package io.legado.features.tvbox.js

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream

internal object JsPipes {
    const val LIMIT = 12 * 1024 * 1024
    fun write(text: String): ParcelFileDescriptor {
        require(text.toByteArray().size <= LIMIT) { "JS 管道输出过大" }
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).bufferedWriter(Charsets.UTF_8).use { it.write(text) } }
            catch (_: java.io.IOException) { runCatching { pipe[1].close() } }
        }, "tvbox-js-pipe").apply { isDaemon = true; start() }
        return pipe[0]
    }
    fun read(descriptor: ParcelFileDescriptor): String = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            require(bytes.size() + count <= LIMIT) { "JS 管道输入过大" }
            bytes.write(buffer, 0, count)
        }
        bytes.toString("UTF-8")
    }
}
