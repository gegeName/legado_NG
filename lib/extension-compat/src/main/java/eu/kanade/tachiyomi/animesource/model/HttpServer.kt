

package eu.kanade.tachiyomi.animesource.model

open class HttpServer {

    val url: String
        get() = PLACEHOLDER_URL

    @Volatile
    private var isRunning = false

    fun isRunning(): Boolean {
        return isRunning
    }

    fun start() {
        isRunning = true
    }

    fun stop() {
        isRunning = false
    }

    companion object {
        const val PLACEHOLDER_URL = "http://localhost:1"
    }
}

