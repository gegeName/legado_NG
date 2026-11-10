package io.legado.app.help.gsyVideo

import android.os.Message
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.shuyu.gsyvideoplayer.GSYVideoBaseManager
import io.legado.app.R

/**
基类管理器
 */
class ExoVideoManager: GSYVideoBaseManager() {
    /** Match GSY's synchronous setDisplay; its MediaHandler ignores HANDLER_SETDISPLAY. */
    fun setDisplay(surface: Surface, width: Int, height: Int) {
        val msg = Message()
        msg.what = HANDLER_SETDISPLAY
        msg.obj = VideoSurfaceOutput(surface, width, height)
        playerManager?.showDisplay(msg)
    }
    companion object {
        val SMALL_ID: Int = R.id.small_id
        val FULLSCREEN_ID: Int = R.id.full_id
        var TAG: String = "GSYExoVideoManager"
    }

    init {
        super.init()
    }

    @OptIn(UnstableApi::class)
    override fun getPlayManager(): ExoPlayerManager {
        return ExoPlayerManager()
    }

//    fun prepare(
//        url: String,
//        mapHeadData: MutableMap<String?, String?>?,
//        index: Int,
//        loop: Boolean,
//        speed: Float,
//        cache: Boolean,
//        cachePath: File?,
//        overrideExtension: String?
//    ) {
//        val msg = Message()
//        msg.what = HANDLER_PREPARE
//        msg.obj =GSYModel(url, mapHeadData, loop, speed, cache, cachePath, overrideExtension)
//        sendMessage(msg)
//    }
    /**
     * 上一集
     */
    @OptIn(UnstableApi::class)
    fun previous() {
        if (playerManager == null) {
            return
        }
        (playerManager as ExoPlayerManager).previous()
    }


    fun setDisplayNew(holder: Any?) {
        val msg = Message()
        msg.what = HANDLER_SETDISPLAY
        msg.obj = holder
        if (playerManager != null) {
            playerManager.showDisplay(msg)
        }
    }

    /**
     * 下一集
     */
    @OptIn(UnstableApi::class)
    fun next() {
        if (playerManager == null) {
            return
        }
        (playerManager as ExoPlayerManager).next()
    }

}

internal data class VideoSurfaceOutput(val surface: Surface, val width: Int, val height: Int)
