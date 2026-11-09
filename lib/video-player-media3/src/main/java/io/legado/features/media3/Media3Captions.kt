package io.legado.features.media3

import android.content.Context
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import java.lang.ref.WeakReference

/** Same playback instance, with independently attached window/overlay subtitle surfaces. */
class Media3Captions {
    private val views = mutableListOf<WeakReference<SubtitleView>>()
    private var cues = CueGroup.EMPTY_TIME_ZERO
    private var generation = 0L
    fun view(context: Context): View = SubtitleView(context).also { view ->
        view.setUserDefaultStyle(); view.setUserDefaultTextSize(); view.setCues(cues.cues)
        views += WeakReference(view)
    }
    fun attach(player: Player) {
        clear()
        val epoch = generation
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                if (epoch != generation) return
                cues = cueGroup
                views.removeAll { it.get() == null }
                views.forEach { it.get()?.setCues(cues.cues) }
            }
        })
    }
    fun clear() {
        generation++
        cues = CueGroup.EMPTY_TIME_ZERO
        views.removeAll { it.get() == null }
        views.forEach { it.get()?.setCues(emptyList()) }
    }
}
