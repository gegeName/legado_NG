package io.legado.app.integration

import android.app.Application
import com.google.gson.JsonParser
import io.legado.app.help.tts.TtsEngineSetting
import io.legado.app.help.tts.TtsEngineType
import io.legado.app.help.tts.TtsScriptEngineClient
import io.legado.app.help.tts.TtsScriptEngineClient.toAudioResponse
import io.legado.app.integration.tts.NgMultiTtsImportAdapter
import io.legado.app.utils.GSON
import io.legado.features.api.tts.JsonResponseValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MultiTtsTestApplication::class, manifest = Config.NONE)
class MultiTtsIntegrationTest {
    private fun backup(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in listOf("engines.yaml" to "engines: [{code: huoshan}]", "config.yaml" to "huoshan: [{code: voice, param: non_numeric, gender: 1}]")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val engine = TtsEngineSetting("fixture", "fixture", TtsEngineType.SCRIPT, script = "function options(){return [];}")

    @Test fun zipPreviewMapsToNgScriptAndExportedCatalogSurvivesRefresh() {
        val file = NgMultiTtsImportAdapter.read(backup().inputStream(), EmptyCoroutineContext) as NgMultiTtsImportAdapter.FileImport.Backup
        val setting = NgMultiTtsImportAdapter.toSetting(file.preview.engines.single())
        val restored = GSON.fromJson(GSON.toJson(setting), TtsEngineSetting::class.java)
        assertEquals(TtsEngineType.SCRIPT, restored.type)
        assertEquals("male", restored.voices.single().gender)
        val voices = restored.evalJS(restored.script + "\nJSON.stringify(voices());").toString()
        assertEquals(restored.voices.single().id, JsonParser.parseString(voices).asJsonArray[0].asJsonObject.get("id").asString)
        val request = restored.evalJS(restored.script + "\nJSON.stringify(synthesize('你好',voices()[0],{speed:50,volume:50,pitch:50}));").toString()
        val parsed = TtsScriptEngineClient.parseSynthesisRequest(request, restored)
        assertEquals("POST", parsed.method)
        assertEquals("non_numeric", JsonParser.parseString(parsed.body).asJsonObject.get("speaker").asString)
        assertEquals("base_resp.status_code", parsed.responseValidation!!.statusPath)
    }

    @Test fun ordinaryTextInputAndCancellationUseTheSameBoundedReader() {
        assertEquals("function voices(){}", (NgMultiTtsImportAdapter.read("function voices(){}".byteInputStream(), EmptyCoroutineContext) as NgMultiTtsImportAdapter.FileImport.Text).content)
        val cancelled = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) { NgMultiTtsImportAdapter.read(backup().inputStream(), cancelled) }
    }

    @Test fun explicitJobReachesNgJsHostAndCancelsBeforeOrDuringScriptPreparation() {
        val job = Job()
        val observed = engine.evalJS("probe.matchesJob();", job) { put("probe", JobProbe(job)) }
        assertEquals(true, observed)
        val failure = AtomicReference<Throwable>()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit {
                try { engine.evalJS("while(true) {}", job) } catch (e: Throwable) { failure.set(e) }
            }
            job.cancel()
            running.get(10, TimeUnit.SECONDS)
            assertNotNull(failure.get())
            assertThrows(CancellationException::class.java) {
                TtsScriptEngineClient.buildSynthesisRequest(engine, "text", coroutineContext = job)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun huoshanJsonValidationPrecedesAudioDecodeAndOldRequestsKeepTheirBehavior() = runBlocking {
        val request = TtsScriptEngineClient.TtsScriptRequest("https://fixture.example", responseType = "json", audioExtract = "audio.data", audioEncoding = "base64", audioContentType = "audio/wav",
            responseValidation = JsonResponseValidation("base_resp.status_code", "0", "base_resp.status_message"))
        suspend fun decode(body: String, context: kotlin.coroutines.CoroutineContext = EmptyCoroutineContext, legacy: Boolean = false): ByteArray =
            response(body).use { it.toAudioResponse(if (legacy) request.copy(responseValidation = null) else request, engine, "text", 50, 50, 50, null, null, context).use { audio -> audio.body.bytes() } }
        assertArrayEquals(byteArrayOf(1, 2, 3), decode("""{"base_resp":{"status_code":0},"audio":{"data":"AQID"}}"""))
        assertArrayEquals(byteArrayOf(1, 2, 3), decode("""{"audio":{"data":"AQID"}}"""))
        val failed = """{"base_resp":{"status_code":9,"status_message":"business failed"},"audio":{"data":"AQID"}}"""
        assertThrows(IllegalArgumentException::class.java) { runBlocking { decode(failed) } }
        assertArrayEquals(byteArrayOf(1, 2, 3), decode(failed, legacy = true))
        for (body in listOf("not json", "{}", """{"audio":{"data":""}}""", """{"audio":{"data":"!!!"}}""")) {
            assertTrue(runCatching { decode(body) }.isFailure)
        }
        assertThrows(CancellationException::class.java) { runBlocking { decode("{}", Job().apply { cancel() }) } }
        Unit
    }

    private fun response(body: String) = Response.Builder().request(Request.Builder().url("https://fixture.example").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    class JobProbe(private val expected: Job) {
        fun matchesJob(): Boolean = (org.htmlunit.corejs.javascript.Context.getCurrentContext() as com.script.rhino.RhinoContext).coroutineContext?.get(Job) === expected
    }
}

class MultiTtsTestApplication : Application() {
    override fun onCreate() { super.onCreate(); injectAsAppCtx() }
}
