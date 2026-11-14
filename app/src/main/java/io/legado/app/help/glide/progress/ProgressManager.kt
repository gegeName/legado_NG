package io.legado.app.help.glide.progress

import android.os.Handler
import android.os.Looper
import io.legado.app.model.analyzeRule.AnalyzeUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * 进度监听器管理类
 * 加入图片加载进度监听，加入Https支持
 */
object ProgressManager {
    private val listenersMap = ConcurrentHashMap<String, ConcurrentHashMap<OnProgressListener, Unit>>()
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    val LISTENER = object : ProgressResponseBody.InternalProgressListener {
        override fun onProgress(url: String, bytesRead: Long, totalBytes: Long) {
            getProgressListener(url)?.let {
                val isComplete = totalBytes >= 0 && bytesRead >= totalBytes
                val percentage = when {
                    isComplete -> 100
                    totalBytes > 0 -> (bytesRead.toDouble() / totalBytes * 100).toInt().coerceIn(0, 100)
                    else -> -1
                }
                it.invoke(isComplete, percentage, bytesRead, totalBytes)
            }
        }
    }

    fun addListener(url: String, listener: OnProgressListener) {
        removeListener(url)
        subscribe(url, listener)
    }

    fun subscribe(url: String, listener: OnProgressListener): AutoCloseable {
        if (url.isNotEmpty()) {
            val key = getUrlNoOption(url)
            listenersMap.compute(key) { _, listeners ->
                (listeners ?: ConcurrentHashMap()).apply { put(listener, Unit) }
            }
            listener.invoke(false, -1, 0, -1)
            return AutoCloseable {
                listenersMap.computeIfPresent(key) { _, listeners ->
                    listeners.remove(listener)
                    listeners.takeIf { it.isNotEmpty() }
                }
            }
        }
        return AutoCloseable { }
    }

    fun report(url: String, bytesRead: Long, totalBytes: Long) {
        mainThreadHandler.post { LISTENER.onProgress(url, bytesRead, totalBytes) }
    }

    fun removeListener(url: String) {
        if (url.isNotEmpty()) {
            val url = getUrlNoOption(url)
            listenersMap.remove(url)
        }
    }

    fun getProgressListener(url: String): OnProgressListener? {
        return if (url.isEmpty() || listenersMap.isEmpty()) {
            null
        } else {
            listenersMap[getUrlNoOption(url)]?.let { listeners ->
                { complete, percentage, bytes, total ->
                    listeners.keys.forEach { it(complete, percentage, bytes, total) }
                }
            }
        }
    }

    private fun getUrlNoOption(url: String): String {
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(url)
        return if (urlMatcher.find()) {
            url.take(urlMatcher.start())
        } else {
            url
        }
    }

}
