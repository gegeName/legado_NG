package io.legado.app.integration

import io.legado.app.data.entities.BookSource
import io.legado.app.integration.rule.NgYiciyuanBridge
import io.legado.app.integration.source.NgSourceImportAdapter
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.ui.association.readBookSourceImport
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageUtils
import io.legado.app.help.JsExtensions
import io.legado.features.api.rule.FetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class YiciyuanIntegrationTest {
    private val input = """{
      "bookSourceUrl":"https://fixture.example", "bookSourceName":"fixture", "bookSourceType":"COMIC",
      "ruleSearchUrl":"/search?q=searchKey", "ruleSearchList":"$.items[*]", "ruleSearchName":"$.name",
      "ruleSearchNoteUrl":"$.url", "ruleChapterList":"$.chapters[*]", "ruleChapterName":"$.title",
      "ruleContentUrl":"$.url", "ruleBookContent":"$.images[*]"
    }"""

    @Test fun importPreviewKeepsMixedCollectionsAndCancellation() = runBlocking {
        val sources = arrayListOf<BookSource>()
        readBookSourceImport("[$input,{\"bookSourceUrl\":\"normal\",\"searchUrl\":\"/query\"}]".reader(), false, { sources.add(it) }, {})
        assertEquals(2, sources.size)
        assertTrue(NgYiciyuanBridge.isAdapterSource(sources[0]))
        assertFalse(NgYiciyuanBridge.isAdapterSource(sources[1]))
        assertEquals("/search?q={{key}}", sources[0].searchUrl)
        assertThrows(CancellationException::class.java) {
            runBlocking { readBookSourceImport("[$input,$input]".reader(), false, { throw CancellationException("stop") }, {}) }
        }
        Unit
    }

    @Test fun compressedImportAndExportPreserveTheCompatibilityVersion() = runBlocking {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(input.toByteArray()) }
        val text = Base64.getEncoder().encodeToString(out.toByteArray())
        var source: BookSource? = null
        readBookSourceImport(text.reader(), false, { source = it }, {})
        assertTrue(NgYiciyuanBridge.isAdapterSource(source))
        val restored = GSON.fromJson(GSON.toJson(source), BookSource::class.java)
        assertTrue(NgYiciyuanBridge.isAdapterSource(restored))
        assertNull(NgSourceImportAdapter.convertOrNull(GSON.toJson(restored)))
    }

    @Test fun importedRulesResolveSearchTocAndOriginalImageUsingNgEngine() {
        val source = NgSourceImportAdapter.convertOrNull(input)!!.single()
        val engine = AnalyzeRule(source = source)
        engine.setContent("""{"items":[{"name":"fixture title","url":"/book"}]}""", source.bookSourceUrl)
        val book = engine.getElements(source.ruleSearch!!.bookList!!).single()
        assertEquals("fixture title", engine.setContent(book).getString(source.ruleSearch!!.name))
        assertEquals("/book", engine.getString(source.ruleSearch!!.bookUrl))
        engine.setContent("""{"chapters":[{"title":"chapter 1","url":"/chapter/1"}]}""")
        val chapter = engine.getElements(source.ruleToc!!.chapterList!!).single()
        assertEquals("chapter 1", engine.setContent(chapter).getString(source.ruleToc!!.chapterName))
        assertEquals("/chapter/1", engine.getString(source.ruleToc!!.chapterUrl))
        engine.setContent("""{"images":["https://images.example/1.jpg","https://images.example/2.jpg"]}""")
        val content = NgYiciyuanBridge.policy(source).formatImageContent(engine.getString(source.ruleContent!!.content))
        assertEquals("<img src=\"https://images.example/1.jpg\">\n<img src=\"https://images.example/2.jpg\">", content)
    }

    @Test fun jsObjectArrayAndJsonArrayStringUseTheCompatibilityPolicy() {
        val source = NgSourceImportAdapter.convertOrNull(input)!!.single()
        val engine = AnalyzeRule(source = source).setContent("fixture")
        assertEquals("https://images.example/1.jpg", engine.getString("@js:[{url:'https://images.example/1.jpg'}]"))
        assertEquals(2, engine.getElements("@js:JSON.stringify([{url:'a'}, {url:'b'}])").size)
        assertEquals("x", engine.getString("@js:java.match('xyz', '/(x)/')[1]"))
        assertEquals("12", engine.getString("@js:java.get({id:12}, 'id')"))
    }

    @Test fun htmlSourcesKeepHtmlAndImageHeaderOptionsSurviveMarkup() {
        val source = NgSourceImportAdapter.convertOrNull(input)!!.single()
        val engine = AnalyzeRule(source = source).setContent("<div class='entry'><a href='/book'>title</a></div>")
        assertEquals("title", engine.getString("@CSS:.entry a@text"))
        val url = "https://images.example/1.jpg,{\"headers\":{\"Referer\":\"https://fixture.example/?a=1&b=2\"}}"
        val html = NgYiciyuanBridge.policy(source).formatImageContent(url)
        assertEquals(url, org.jsoup.Jsoup.parse(html).selectFirst("img")!!.attr("src"))
    }

    @Test fun featureInternalsAreNotDirectlyImportableButReturnedResponseIsUsable() {
        val source = NgSourceImportAdapter.convertOrNull(input)!!.single()
        assertTrue(source.evalJS("String(Packages.io.legado.features.adapter.FeatureAssembly)").toString().startsWith("[JavaPackage "))
        val response = FeatureBootstrap.features.fetch("https://fixture.example", null) {
            FetchResult("https://fixture.example/final", "fixture-body", 201)
        }
        val result = source.evalJS("String(response.body().string()) + '|' + response.code()") {
            put("response", response)
        }
        assertEquals("fixture-body|201", result)
    }

    @Test fun imageDecodeUsesNgRuntimeForBothCoverAndContent() {
        val source = NgSourceImportAdapter.convertOrNull(input)!!.single()
        source.coverDecodeJs = "@js:result"
        source.getContentRule().imageDecode = "<js>result</js>"
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, ImageUtils.decode("https://images.example/cover", bytes, true, source))
        assertArrayEquals(bytes, ImageUtils.decode("https://images.example/page", bytes.inputStream(), false, source)!!.readBytes())
    }

    @Test fun legacyWebViewSignatureDelegatesToTheExistingHost() {
        val host = object : JsExtensions {
            override fun getSource(): BookSource? = null
            override fun getTag(): String = "fixture"
            override fun webView(html: String?, url: String?, js: String?): String = "$html|$url|$js"
        }
        assertEquals("null|https://fixture.example|document.title", host.webView("https://fixture.example", "document.title"))
    }
}
