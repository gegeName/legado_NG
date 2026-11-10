package io.legado.features.adapter

import io.legado.features.video.VideoRuleAnalysis

object VideoRuleFeatures {
    data class Page(val url: String, val html: String?)
    fun capture(url: String, html: String? = null) = VideoRuleAnalysis.capture(url, html)
    fun captured(): Page? = VideoRuleAnalysis.captured()?.let { Page(it.url, it.html) }
    suspend fun <T> analyze(block: suspend () -> T): T = VideoRuleAnalysis.withAnalysis(block)
}
