package io.legado.features.tvbox.js

import android.app.Service
import android.content.Intent
import android.os.*
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.AsyncFunctionBinding
import kotlinx.coroutines.*
import org.json.JSONObject

/** Persistent runtimes live outside the host process; native loops are killed by a watchdog. */
class TvBoxJsService : Service() {
    private val lock = Any()
    private val runtimes = mutableMapOf<String, QuickJs>()
    private val main = Handler(Looper.getMainLooper())
    private fun watched(timeout: Long, action: () -> String): ParcelFileDescriptor {
        val kill = Runnable { Process.killProcess(Process.myPid()) }
        main.postDelayed(kill, timeout.coerceIn(1000, 120000))
        return try {
            val json = try { JSONObject().put("ok", true).put("value", action()).toString() }
                catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "JS Spider 执行失败").toString() }
            JsPipes.write(json)
        } finally { main.removeCallbacks(kill) }
    }
    private val binder = object : ITvBoxJsRuntime.Stub() {
        override fun open(session: String, program: ParcelFileDescriptor, host: ITvBoxJsHost, timeout: Long): ParcelFileDescriptor = synchronized(lock) {
            watched(timeout) {
                require(Build.VERSION.SDK_INT >= 23) { "QuickJS Spider 需要 Android 6.0" }
                require(session !in runtimes && runtimes.size < 16) { "JS Spider 会话数量超过限制" }
                val source = JsPipes.read(program)
                val runtime = QuickJs.create(Dispatchers.Default).apply { memoryLimit = 64L * 1024 * 1024; maxStackSize = 512L * 1024 }
                try {
                    runtime.defineBinding("__nativeDelay", object : AsyncFunctionBinding<String> {
                        override suspend fun invoke(args: Array<Any?>): String {
                            delay((args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0, 30000) ?: 0L)
                            return ""
                        }
                    })
                    runtime.defineBinding("__nativeHostCall", object : FunctionBinding<String> {
                        override fun invoke(args: Array<Any?>): String {
                            val method = args.getOrNull(0)?.toString().orEmpty()
                            val arguments = args.getOrNull(1)?.toString().orEmpty()
                            require(arguments.toByteArray().size <= 256 * 1024) { "JS 宿主参数过大" }
                            return JSONObject.quote(JsPipes.read(checkNotNull(host.call(method, arguments))))
                        }
                    })
                    val result = runBlocking { runtime.evaluate<String>(source, filename = "tvbox-bootstrap.js") }
                    runtimes[session] = runtime
                    result
                } catch (e: Throwable) { runtime.close(); throw e }
            }
        }
        override fun evaluate(session: String, expression: ParcelFileDescriptor, timeout: Long): ParcelFileDescriptor = synchronized(lock) {
            watched(timeout) {
                val runtime = checkNotNull(runtimes[session]) { "JS Spider 会话已失效" }
                val source = JsPipes.read(expression)
                runBlocking { runtime.evaluate<String>(source, filename = "tvbox-call.js") }
            }
        }
        override fun release(session: String) { synchronized(lock) { runtimes.remove(session)?.close() } }
        override fun abort() { Process.killProcess(Process.myPid()) }
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        synchronized(lock) { runtimes.values.forEach { runCatching { it.close() } }; runtimes.clear() }
        super.onDestroy()
    }
}
