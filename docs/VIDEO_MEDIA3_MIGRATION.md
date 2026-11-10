# 视频直接接入 Media3

2026-11-10：移除 GSY Java/Exo2 依赖、IJK 适配器、旧 View 控件和不再调用的布局；升级 AndroidX Media3 到 1.11.1。

- `app/help/video/Media3VideoEngine.kt` 直接创建和释放 `ExoPlayer`，每次播放使用独立请求头和网络数据源。点播采用关键帧定位；直播时间线改为默认定位；直播落后允许一次回到直播点。
- `VideoPlaybackBridge` 保留 Compose 状态、音频焦点、书签、自动续集和输出租约。Activity/悬浮窗共享同一播放器；延迟到达的旧窗口回调不能清除新窗口输出。
- HLS、DASH、RTSP、SmoothStreaming、RTMP 是应用的直接依赖，关闭可选画面增强模块也保留协议支持。Media3 版本维持本工程的统一版本，不随本次改造升级。
- 设置新增均衡、弱网优先、直播三个缓冲配置，以及解码器回退开关。设置持久化，下次加载生效。缓冲阈值集中在 `VideoBufferPolicy`，网络超时和渲染器创建集中在 `Media3VideoEngine`，均由项目控制。
- 保留字幕、外部音轨、画面增强、弹幕和选集接入。单链接、订阅和书籍章节播放默认启用磁盘缓存，复用现有 Media3 缓存数据源，并逐请求传递请求头。缓存位于 externalCache/exoplayer，与现有播放器临时缓存共享 100MiB LRU 容量；仅缓存播放时加载的数据，不提供整集离线下载。

参考 `D:\project\legado` 的 `help/player/ExoVideoPlayer.kt` 和 `help/exoplayer/ExoPlayerHelper.kt`：点播缓冲为 15–50 秒、起播 500ms、恢复 300ms；直播配置为 5–30 秒、起播 2 秒、恢复 5 秒；连接/读取超时 15 秒，长连接无总请求时限。

本地编译和 JVM 回归只验证构建及状态辅助逻辑。真实站点、协议、直播、设备解码、画面效果、字幕和悬浮窗连续播放仍需 Android 设备验收。

本地验证：视频输出租约和选集 JVM 回归已在改造前通过；1.11.1 下应用 Kotlin 编译通过。单测任务遇到 Google Maven 网络拒绝，尚未在新依赖下重跑成功。待 APK 设备播放验收。

启动卡死原因：`loading()` 先把状态设为 `preparing=true`，随后 `startPlayLogic()` 又把这个状态误判成“已有准备任务”并直接返回，播放器从未调用 `prepare()`。已移除这个错误的短路；输出 Surface、起始进度和 `playWhenReady` 现在在 `prepare()` 前绑定，避免 READY 回调重复 seek 或重复启动。
