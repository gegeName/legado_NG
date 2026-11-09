package com.github.catvod

object Proxy {
    @Volatile private var port = -1
    private val session = ThreadLocal<String?>()
    @JvmStatic fun set(port: Int) { this.port = port }
    @JvmStatic fun getPort() = port
    @JvmStatic fun getUrl(local: Boolean): String = checkNotNull(session.get()) { "TVBox 代理缺少当前站点会话" }
    fun <T> bound(url: String, action: () -> T): T {
        val previous = session.get(); session.set(url)
        try { return action() } finally { session.set(previous) }
    }
}
