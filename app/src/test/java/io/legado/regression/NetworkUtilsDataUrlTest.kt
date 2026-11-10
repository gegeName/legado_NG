package io.legado.regression

import io.legado.app.utils.NetworkUtils
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkUtilsDataUrlTest {
    private val payload = "data:;base64,eyJwYXRoIjoiL2RyYW1hL3BsYXkiLCJkYXRhIjp7ImlkIjoiNmFkZjFmOTE2YmQ1YWZlOThlNjQ4ZDA3Iiwic2VxIjoiMSJ9fQ=="
    private val chapter = "$payload,{\"type\":\"Buding\"}"

    @Test fun dataChapterPreservesPayloadAndOptionsWithoutMalformedUrlWarning() {
        assertQuietResolution(chapter, "  $chapter  ", chapter)
    }

    @Test fun dataBaseAllowsAbsoluteMediaTarget() {
        assertQuietResolution(chapter, "https://media.example/video.m3u8", "https://media.example/video.m3u8")
    }

    @Test fun opaqueDataBaseLeavesRelativeTargetForRuleProcessing() {
        assertQuietResolution(chapter, " /drama/play ", "/drama/play")
    }

    @Test fun httpBasePreservesDataPayload() {
        assertQuietResolution("https://example.com/chapter/1", payload, payload)
    }

    @Test fun httpBaseStillResolvesRelativeAndProtocolRelativeTargets() {
        assertQuietResolution("https://example.com/chapter/1,{\"type\":\"Buding\"}", "../video/2", "https://example.com/video/2")
        assertQuietResolution("https://example.com/chapter/1", "//cdn.example/2.m3u8", "https://cdn.example/2.m3u8")
    }

    private fun assertQuietResolution(base: String, target: String, expected: String) {
        val previous = System.err
        val errors = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(errors))
            assertEquals(expected, NetworkUtils.getAbsoluteURL(base, target))
            assertEquals("URL resolution must not log a malformed data base", "", errors.toString())
        } finally {
            System.setErr(previous)
        }
    }
}
