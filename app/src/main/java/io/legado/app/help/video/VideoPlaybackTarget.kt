package io.legado.app.help.video

import java.io.File

/** The source resolver only supplies media; it does not own a player view. */
interface VideoPlaybackTarget {
    var seekOnStart: Long
    var mapHeadData: Map<String, String>?
    fun setUp(url: String?, cache: Boolean, cachePath: File?, title: String?): Boolean
    fun startPlayLogic()
    fun loading() = Unit
    fun loadError(message: String) = Unit
}
