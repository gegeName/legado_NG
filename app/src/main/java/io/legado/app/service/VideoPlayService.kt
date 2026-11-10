package io.legado.app.service

import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.MediaHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.VideoPlay
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager

/**
 * 视频悬浮窗服务
 */
class VideoPlayService : BaseService(), androidx.savedstate.SavedStateRegistryOwner, androidx.lifecycle.ViewModelStoreOwner {
    companion object {
        @JvmStatic
        var pause = true
            private set
        private const val APP_ACTION_STOP = "Stop"
    }
    private val savedStateController = androidx.savedstate.SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = androidx.lifecycle.ViewModelStore()
    private var foregroundReady = false
    private var ownsWindow = false
    private var stateJob: Job? = null
    private var ownerJob: Job? = null
    private var completionJob: Job? = null
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val mediaSessionCompat by lazy {
        MediaSessionCompat(this, "videoPlayService")
    }
    private val floatingView by lazy {
        androidx.compose.ui.platform.ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VideoPlayService)
            setViewTreeSavedStateRegistryOwner(this@VideoPlayService)
            setViewTreeViewModelStoreOwner(this@VideoPlayService)
            setContent {
                io.legado.app.ui.design.theme.NgAppTheme(updateSystemBars = false) {
                    io.legado.app.ui.video.VideoFloatingContent(this@VideoPlayService, ::stop, ::toggleFullScreen,
                        ::cancelAnimator, { x, y -> params.x += x.toInt(); params.y += y.toInt(); updateViewPosition() },
                        ::startEdgeAnimation)
                }
            }
        }
    }
    private val playback get() = io.legado.app.help.video.VideoPlaybackBridge
    private var upNotificationJob: Coroutine<*>? = null
    private var animator: SpringAnimation? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    private var broadcastReceiver: BroadcastReceiver? = null
    private fun updateViewPosition() {
        try {
            windowManager.updateViewLayout(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelAnimator() {
        animator?.cancel()
    }

    private val layoutParamsXProperty =
        object : FloatPropertyCompat<WindowManager.LayoutParams>("x") {
            override fun getValue(layoutParams: WindowManager.LayoutParams): Float {
                return layoutParams.x.toFloat()
            }

            override fun setValue(layoutParams: WindowManager.LayoutParams, value: Float) {
                layoutParams.x = value.toInt()
                updateViewPosition()
            }
        }

    private fun startEdgeAnimation() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val viewWidth = floatingView.width
        val viewHeight = floatingView.height
        val currentX = params.x
        val endX = if (viewWidth == screenWidth) {
            0
        } else if (currentX + viewWidth / 2 > screenWidth / 2) {
            screenWidth - viewWidth - 30
        } else {
            30
        }
        val currentY = params.y
        var endY = currentY
        if (currentY < 30) {
            endY = 30
        } else if (currentY > screenHeight - viewHeight - 60) {
            endY = screenHeight - viewHeight - 60
        }
        if (endY != currentY) {
            ObjectAnimator.ofInt(currentY, endY).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    params.y = animation.animatedValue as Int
                    updateViewPosition()
                }
                start()
            }
        }
        animator = SpringAnimation(params, layoutParamsXProperty, endX.toFloat()).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW  // 低刚度更Q弹
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY  // 中等阻尼
            start()
        }
    }

    override fun onCreate() {
        savedStateController.performAttach()
        super.onCreate()
        savedStateController.performRestore(null)
        // Establish the foreground obligation before permissions, source resolution or window creation.
        startForegroundNotification()
        if (!foregroundReady) return
        initMediaSession()
        initBroadcastReceiver()
        ownerJob = lifecycleScope.launch {
            playback.owner.collect { owner ->
                if (owner === this@VideoPlayService) ownsWindow = true
                else if (ownsWindow && owner != null) stopSelf()
            }
        }
        completionJob = lifecycleScope.launch {
            playback.completions.collect {
                if (playback.owner.value === this@VideoPlayService) stop()
            }
        }
        stateJob = lifecycleScope.launch {
            var previousPlaying: Boolean? = null
            var previousTitle = ""
            var previousSize = 0 to 0
            playback.state.collect { state ->
                pause = !state.playing
                if (previousPlaying != state.playing || previousTitle != state.title) {
                    previousPlaying = state.playing; previousTitle = state.title
                    upMediaMetadata(); upVideoPlayNotification()
                    if (::params.isInitialized) {
                        params.flags = if (state.playing) params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            else params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                        updateViewPosition()
                    }
                }
                upMediaSessionPlaybackState(if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
                if (::params.isInitialized && previousSize != (state.width to state.height) && state.width > 0 && state.height > 0) {
                    previousSize = state.width to state.height
                    updateFloatingSize()
                }
            }
        }
        execute {
            ImageLoader
                .loadBitmap(this@VideoPlayService, VideoPlay.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upMediaMetadata()
                upVideoPlayNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!foregroundReady) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                checkFloatPermission()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        intent.action?.let { action ->
            when (action) {
                IntentAction.pause -> pause()
                IntentAction.resume -> resume()
                IntentAction.prev -> VideoPlay.upDurIndex(-1, playback)
                IntentAction.next -> VideoPlay.upDurIndex(1, playback)
                IntentAction.stop -> stop()
            }
            return START_NOT_STICKY
        }
        val isNew = intent.getBooleanExtra("isNew", true)
        if (isNew) {
            VideoPlay.singleUrl = intent.hasExtra("videoUrl")
            VideoPlay.videoUrl = intent.getStringExtra("videoUrl")
            VideoPlay.videoTitle = intent.getStringExtra("videoTitle")
            val sourceKey = intent.getStringExtra("sourceKey")
            val sourceType = intent.getIntExtra("sourceType", 0)
            val bookUrl = intent.getStringExtra("bookUrl")
            val record = intent.getStringExtra("record")
            VideoPlay.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            if (!VideoPlay.initSource(sourceKey, sourceType, bookUrl, record)) {
                stopSelf()
                return START_NOT_STICKY
            }
            VideoPlay.startPlay(playback)
            if (!VideoPlay.singleUrl) VideoPlay.saveRead(VideoPlay.durChapterPos)
        }
        if (floatingView.parent == null) {
            try { createFloatingWindow() } catch (error: Exception) {
                AppLog.put("创建视频小窗失败", error, true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun initMediaSession() {
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onSeekTo(pos: Long) = playback.seekTo(pos)
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onCustomAction(action: String?, extras: Bundle?) {
                action ?: return
                when (action) {
                    APP_ACTION_STOP -> stop()
                }
            }
            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
                VideoPlay.upDurIndex(-1, playback)
            }
            override fun onSkipToNext() {
                super.onSkipToNext()
                VideoPlay.upDurIndex(1, playback)
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    private fun upVideoPlayNotification() {
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.VideoPlayService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建视频播放通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    override fun startForegroundNotification() {
        if (foregroundReady) return
        try {
            // No cover loading or MediaSession initialization is required for startup.
            val notification = NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
                .setSmallIcon(R.drawable.ic_volume_up).setContentTitle(getString(R.string.video))
                .setOngoing(true).setOnlyAlertOnce(true).build()
            startForeground(NotificationId.VideoPlayService, notification)
            foregroundReady = true
        } catch (error: Exception) {
            AppLog.put("启动视频前台服务失败", error, true)
            stopSelf()
        }
    }

    /**
     * 断开耳机监听
     */
    private fun initBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                    pause()
                }
            }
        }
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 暂停播放
     */
    private fun pause(fromCB: Boolean = false) {
        try {
            pause = true
            if (!fromCB) {
                playback.onVideoPause()
            }
            upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
            upVideoPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    /**
     * 恢复播放
     */
    @SuppressLint("WakelockTimeout")
    private fun resume(fromCB: Boolean = false) {
        try {
            pause = false
            if (!fromCB) {
                playback.onVideoResume()
            }
            upVideoPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
            stop()
        }
    }

    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, playback.state.value.position, playback.state.value.speed)
                .addCustomAction(
                    APP_ACTION_STOP,
                    getString(R.string.stop),
                    R.drawable.ic_stop_black_24dp
                )
                .build()
        )
    }

    private fun createNotification(): NotificationCompat.Builder {
        val nTitle = getString(R.string.audio_play_t) + ": ${VideoPlay.videoTitle}"
        val nSubtitle = getString(R.string.audio_play_s)
        val builder = NotificationCompat.Builder(this@VideoPlayService, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.video))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<VideoPlayerActivity>("activity") { putExtra("isNew", false) }
            )
        builder.setLargeIcon(cover)
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous),
            servicePendingIntent<VideoPlayService>(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                servicePendingIntent<VideoPlayService>(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                servicePendingIntent<VideoPlayService>(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next),
            servicePendingIntent<VideoPlayService>(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            servicePendingIntent<VideoPlayService>(IntentAction.stop)
        )
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSessionCompat.sessionToken)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return builder
    }

    @OptIn(UnstableApi::class)
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenWidth = resources.displayMetrics.widthPixels
        val videoWidth = playback.state.value.width
        val videoHeight = playback.state.value.height
        val windowWidth = if (videoHeight > videoWidth * 1.2) {
            screenWidth / 2 //竖屏时为屏幕的1/2
        } else {
            screenWidth * 3 / 4 //默认为屏幕3/4宽
        }
        val windowHeight = if (videoWidth > 0 && videoHeight > 0) (windowWidth * videoHeight / videoWidth) else (windowWidth * 9 / 16) // 默认16:9比例
        // 设置窗口参数
        params = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    (if (playback.state.value.playing) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 30
            y = screenWidth / 10
        }
        windowManager.addView(floatingView, params)

    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateFloatingSize()
    }

    private fun updateFloatingSize() {
        if (!::params.isInitialized) return
        val metrics = resources.displayMetrics
        val aspect = playback.state.value.aspect
        params.width = if (aspect < 1f / 1.2f) metrics.widthPixels / 2 else metrics.widthPixels * 3 / 4
        params.height = (params.width / aspect).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
        updateViewPosition()
    }

    private fun stop() {
        stopSelf()
        pause = true
    }

    private fun toggleFullScreen() {
        val fullscreenIntent = Intent(this, VideoPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("isNew", false)
        }
        startActivity(fullscreenIntent)
    }


    private fun upMediaMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, VideoPlay.videoTitle ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, VideoPlay.book?.name ?: "视频播放")
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, VideoPlay.book?.author ?: "null")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playback.state.value.duration)
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    override fun onDestroy() {
        completionJob?.cancel()
        stateJob?.cancel(); ownerJob?.cancel()
        cancelAnimator()
        if (::windowManager.isInitialized && floatingView.parent != null) windowManager.removeView(floatingView)
        floatingView.disposeComposition()
        playback.release(this)
        broadcastReceiver?.let { unregisterReceiver(it) }
        mediaSessionCompat.release()
        upNotificationJob?.cancel()
        notificationManager.cancel(NotificationId.VideoPlayService)
        viewModelStore.clear()
        super.onDestroy()
    }
}
