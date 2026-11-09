package io.legado.features.tvbox.js

import android.content.*
import android.os.IBinder
import com.google.gson.*
import io.legado.features.api.host.ConfigPort
import io.legado.features.tvbox.TvBoxHttp
import io.legado.features.tvbox.config.TvBoxSite
import io.legado.features.tvbox.provider.SpiderPort
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host network/config callbacks are narrow JSON calls; no NG objects cross the process. */
class QuickJsSpider(private val context: Context, private val site: TvBoxSite, namespace: String,
    http: TvBoxHttp, config: ConfigPort, private val asset: (String) -> String, proxy: () -> String) : SpiderPort {
    private val id = UUID.randomUUID().toString()
    private val host = SpiderHost(site, namespace, http, config, asset, proxy)
    private val ready = CountDownLatch(1)
    @Volatile private var service: ITvBoxJsRuntime? = null
    @Volatile private var owner: Job? = null
    private var bound = false
    private var closed = false
    private val gson = Gson()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = ITvBoxJsRuntime.Stub.asInterface(binder); ready.countDown() }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; ready.countDown() }
        override fun onNullBinding(name: ComponentName?) { ready.countDown() }
        override fun onBindingDied(name: ComponentName?) { service = null; ready.countDown() }
    }
    private val callback = object : ITvBoxJsHost.Stub() {
        override fun call(method: String, arguments: String): android.os.ParcelFileDescriptor {
            owner?.ensureActive()
            val previous = TvBoxHttp.job.get(); TvBoxHttp.job.set(owner)
            try { return JsPipes.write(host.call(method, arguments)) }
            finally { TvBoxHttp.job.set(previous) }
        }
    }
    init {
        try {
            bound = context.bindService(Intent(context, TvBoxJsService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound && ready.await(10, TimeUnit.SECONDS)) { "JS Spider 进程连接超时" }
            val program = "var __hostBridge__={call:function(m,a){return JSON.parse(__nativeHostCall(m,a));}};\n" +
                asset("tvbox/TvBoxJsModuleLoader.js") + "\n" + asset("tvbox/TvBoxJsApi.js") + "\n" + asset("tvbox/TvBoxDrpyParser.js") + "\n" + TIMERS + "\n" +
                "__M.setHost(__hostBridge__);__M.setBase(${gson.toJson(site.api)});var __module=__M.require(${gson.toJson(site.api)});" +
                "globalThis.__spider=typeof __module.__jsEvalReturn==='function'?__module.__jsEvalReturn():(typeof __module.default==='function'?__module.default():__module.default||__module);'ready';"
            remote { engine -> JsPipes.write(program).use { JsPipes.read(engine.open(id, it, callback, site.timeoutSeconds * 1000L)) } }
            call("init", if (site.ext.trim().startsWith('{')) JsonParser.parseString(site.ext) else site.ext, optional = true)
        } catch (e: Throwable) { close(); throw e }
    }
    @OptIn(InternalCoroutinesApi::class)
    private fun remote(action: (ITvBoxJsRuntime) -> String): String {
        check(!closed)
        val engine = checkNotNull(service?.takeIf { it.asBinder().isBinderAlive }) { "JS Spider 进程已退出，请重新加载站点" }
        val capturedOwner = TvBoxHttp.job.get()
        owner = capturedOwner
        val binding = capturedOwner?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (capturedOwner.isCancelled) runCatching { engine.abort() } }
        try {
            val result = JSONObject(action(engine))
            check(result.optBoolean("ok")) { result.optString("error", "JS Spider 执行失败") }
            owner?.ensureActive()
            return result.getString("value")
        } finally { binding?.dispose(); owner = null }
    }
    private fun call(method: String, vararg args: Any?, optional: Boolean = false): String {
        // Resolve JS promises via the runtime's microtask pump, then read a primitive JSON string.
        val expression = "globalThis.__callResult=undefined;globalThis.__callError=undefined;" +
            "Promise.resolve().then(function(){var f=__spider[${gson.toJson(method)}];if(typeof f!=='function'){${if (optional) "return '';" else "throw new Error('Spider 缺少 $method');"}}return f.apply(__spider,${gson.toJson(args)});})" +
            ".then(function(r){globalThis.__callResult=typeof r==='string'?r:JSON.stringify(r==null?'':r);},function(e){globalThis.__callError=String(e&&e.stack||e);});'scheduled';"
        remote { engine -> JsPipes.write(expression).use { JsPipes.read(engine.evaluate(id, it, site.timeoutSeconds * 1000L)) } }
        return remote { engine -> JsPipes.write("if(__callError!==undefined)throw new Error(__callError);if(__callResult===undefined)throw new Error('JS Spider Promise 未完成');JSON.stringify(__callResult);").use {
            val response = JsPipes.read(engine.evaluate(id, it, site.timeoutSeconds * 1000L))
            val root = JSONObject(response)
            if (root.optBoolean("ok")) root.put("value", JsonParser.parseString(root.getString("value")).asString).toString() else response
        } }
    }
    override fun home(filter: Boolean) = call("home", filter)
    override fun category(id: String, page: Int, extend: Map<String, String>) = call("category", id, page.toString(), true, extend)
    override fun search(query: String, page: Int) = call("search", query, false, page.toString())
    override fun detail(id: String) = call("detail", id)
    override fun play(flag: String, id: String, flags: List<String>) = call("play", flag, id, flags)
    override fun proxy(params: Map<String, String>): Array<Any?>? {
        val result = JsonParser.parseString(call("proxy", params, optional = true))
        if (!result.isJsonArray) return null
        return result.asJsonArray.map { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asInt else if (it.isJsonObject) io.legado.features.tvbox.config.TvBoxConfigs.headers(it) else it.asString }.toTypedArray()
    }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { service?.release(id) }; service = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }
    private companion object {
        // Promises wait on a host asynchronous delay instead of running timers immediately.
        const val TIMERS = """
        var __timerId=0;var __activeTimers={};
        globalThis.setTimeout=function(fn,ms){var id=++__timerId;var args=Array.prototype.slice.call(arguments,2);__activeTimers[id]=true;__nativeDelay(Number(ms)||0).then(function(){if(__activeTimers[id]){delete __activeTimers[id];fn.apply(null,args);}});return id;};
        globalThis.clearTimeout=function(id){delete __activeTimers[id];};
        globalThis.setInterval=function(){throw new Error('Spider 周期任务未启用');};
        """
    }
}
