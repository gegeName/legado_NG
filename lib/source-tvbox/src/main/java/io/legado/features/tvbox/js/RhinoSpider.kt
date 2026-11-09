package io.legado.features.tvbox.js

import com.google.gson.*
import io.legado.features.api.host.ConfigPort
import io.legado.features.tvbox.TvBoxHttp
import io.legado.features.tvbox.config.*
import io.legado.features.tvbox.provider.SpiderPort
import org.htmlunit.corejs.javascript.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.net.URI
import java.security.MessageDigest

/** A persistent, serial synchronous Spider runtime. Unsupported async APIs fail explicitly. */
class RhinoSpider(private val site: TvBoxSite, private val namespace: String, private val http: TvBoxHttp,
    private val config: ConfigPort, private val asset: (String) -> String, private val proxy: () -> String) : SpiderPort {
    private var deadline = 0L
    private var closed = false
    private val gson = Gson()
    private val host = SpiderHost(site, namespace, http, config, asset, proxy)
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            languageVersion = Context.VERSION_ES6; optimizationLevel = -1
            instructionObserverThreshold = 10000
            setClassShutter { false }
        }
        override fun observeInstructionCount(context: Context, count: Int) {
            TvBoxHttp.job.get()?.let { if (!it.isActive) throw java.util.concurrent.CancellationException("Spider 已取消") }
            check(System.nanoTime() < deadline) { "JS Spider 执行超时" }
        }
    }
    private val scope: TopLevel = run {
        deadline = System.nanoTime() + site.timeoutSeconds * 1_000_000_000L
        factory.call { cx ->
            val scope = cx.initSafeStandardObjects()
            val host = cx.newObject(scope)
            ScriptableObject.putProperty(host, "call", object : BaseFunction() {
                override fun call(cx: Context, scope: VarScope, thisObj: Scriptable, args: Array<out Any?>): Any =
                    this@RhinoSpider.host.call(Context.toString(args.getOrNull(0)), Context.toString(args.getOrNull(1)))
            })
            ScriptableObject.putProperty(scope, "__hostBridge__", host)
            cx.evaluateString(scope, "var globalThis=this;", "bootstrap", 1, null)
            cx.evaluateString(scope, asset("tvbox/TvBoxJsModuleLoader.js"), "module-loader", 1, null)
            cx.evaluateString(scope, BOOTSTRAP, "host-api", 1, null)
            cx.evaluateString(scope, "__M.setHost(__hostBridge__);__M.setBase(${gson.toJson(site.api)});var __module=__M.require(${gson.toJson(site.api)});" +
                "var __spider=typeof __module.__jsEvalReturn==='function'?__module.__jsEvalReturn():(typeof __module.default==='function'?__module.default():__module.default||__module);", "spider", 1, null)
            scope
        }
    }
    init { call("init", runCatching { JsonParser.parseString(site.ext) }.getOrElse { JsonPrimitive(site.ext) }, optional = true) }
    private fun call(method: String, vararg args: Any?, optional: Boolean = false): String {
        check(!closed) { "JS Spider 已关闭" }
        deadline = System.nanoTime() + site.timeoutSeconds * 1_000_000_000L
        val expression = "(function(){var f=__spider[${gson.toJson(method)}];if(typeof f!=='function'){${if (optional) "return '';" else "throw new Error('Spider 缺少 $method');"}}" +
            "var r=f.apply(__spider,${gson.toJson(args)});if(r&&typeof r.then==='function')throw new Error('当前 JS Spider 仅支持同步结果，Promise/async 需要独立 QuickJS runtime');return typeof r==='string'?r:JSON.stringify(r==null?'':r);})()"
        return factory.call { cx -> Context.toString(cx.evaluateString(scope, expression, method, 1, null)) }
    }
    override fun home(filter: Boolean) = call("home", filter)
    override fun category(id: String, page: Int, extend: Map<String, String>) = call("category", id, page.toString(), true, extend)
    override fun search(query: String, page: Int) = call("search", query, false, page.toString())
    override fun detail(id: String) = call("detail", id)
    override fun play(flag: String, id: String, flags: List<String>) = call("play", flag, id, flags)
    override fun proxy(params: Map<String, String>): Array<Any?>? {
        val result = JsonParser.parseString(call("proxy", params, optional = true))
        if (!result.isJsonArray) return null
        return result.asJsonArray.map { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asInt else if (it.isJsonObject) TvBoxConfigs.headers(it) else it.asString }.toTypedArray()
    }
    override fun close() { if (!closed) try { call("destroy", optional = true) } finally { closed = true } }
    private companion object {
        const val BOOTSTRAP = """
        function req(u,o){return JSON.parse(__M.jsonCall('req',u,JSON.stringify(o||{})));}
        var http=req,_http=req;
        function joinUrl(a,b){return __M.jsonCall('joinUrl',a,b);}
        function md5X(a){return __M.jsonCall('md5',a);}
        function getProxy(){return __M.jsonCall('getProxy');}
        function getPort(){return Number(__M.jsonCall('getPort'));}
        function log(s){__M.jsonCall('log',String(s));}
        var local={get:function(n,k,d){return __M.jsonCall('localGet',n,k,d||'');},set:function(n,k,v){return __M.jsonCall('localSet',n,k,v);},delete:function(n,k){return __M.jsonCall('localDelete',n,k);}};
        function setTimeout(){throw new Error('JS Spider 延时任务需要异步 runtime');}
        function setInterval(){throw new Error('JS Spider 定时任务需要异步 runtime');}
        """
    }
}
