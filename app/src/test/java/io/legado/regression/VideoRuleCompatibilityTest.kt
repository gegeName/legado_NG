package io.legado.regression

import cn.hutool.core.util.HexUtil
import io.legado.app.help.JsEncodeUtils
import io.legado.app.model.analyzeRule.AnalyzeUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class VideoRuleCompatibilityTest {
    @Test fun percentEncodedOptionsPreserveHeadersScriptDelayAndPostBody() {
        val options = """{"method":"POST","body":"token=one","headers":{"Referer":"https://source.test/","Authorization":"Bearer fixture"},"webJs":"document.documentElement.outerHTML","webViewDelayTime":3456}"""
        val analyze = AnalyzeUrl("https://parser.test/watch," + URLEncoder.encode(options, "UTF-8"), headerMapF = emptyMap())
        assertEquals("https://parser.test/watch", analyze.url)
        assertEquals("https://source.test/", analyze.headerMap["Referer"])
        assertEquals("Bearer fixture", analyze.headerMap["Authorization"])
        assertEquals("document.documentElement.outerHTML", analyze.videoWebScript())
        assertEquals(3456L, analyze.videoWebDelay())
        assertTrue(analyze.isPost())
    }

    @Test fun redirectOptionsOverrideSeedHeadersWithoutLosingTheirScript() {
        val original = AnalyzeUrl("https://source.test/", headerMapF = emptyMap())
        val redirected = original.forVideoUrl("""https://parser.test/watch,{"headers":{"Referer":"https://actual.test/"},"webJs":"decryptAndPlay()","webViewDelayTime":4321}""",
            mapOf("Referer" to "old", "User-Agent" to "fixture-agent"))
        assertEquals("https://actual.test/", redirected.headerMap["Referer"])
        assertEquals("fixture-agent", redirected.headerMap["User-Agent"])
        assertEquals("decryptAndPlay()", redirected.videoWebScript())
        assertEquals(4321L, redirected.videoWebDelay())
    }

    @Test fun aesRuleBridgeDecryptsNistVectorAsHexBase64BytesAndStream() {
        val bridge = object : JsEncodeUtils {}
        // NIST SP 800-38A AES-128 CBC first block; independent of the implementation's encrypt path.
        val key = HexUtil.decodeHex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = HexUtil.decodeHex("000102030405060708090a0b0c0d0e0f")
        val cipher = HexUtil.decodeHex("7649abac8119b246cee98e9b12e9197d")
        val plain = HexUtil.decodeHex("6bc1bee22e409f96e93d7e117393172a")
        val crypto = bridge.createSymmetricCrypto("AES/CBC/NoPadding", key, iv)
        assertArrayEquals(plain, crypto.decrypt("7649abac8119b246cee98e9b12e9197d"))
        assertArrayEquals(plain, crypto.decrypt(Base64.getEncoder().encodeToString(cipher)))
        assertArrayEquals(plain, crypto.decrypt(cipher))
        assertArrayEquals(plain, crypto.decrypt(cipher.inputStream()))
    }
}
