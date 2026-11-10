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

2026-11-10 网页视频接入：参照 `D:\project\legado` 的 `AnalyzeUrl.getMediaItemAwait()` 和 `BackstageWebView.SnifferWebClient`，把普通源单链接、订阅与视频选集统一交给 `NgVideoBridge.prepare()`。URL 判定/HTTP 响应处理/HTML 提取在 `source-tvbox` 的 `WebVideoResolver`，后台 WebView 继续复用该模块的 `WebViewSniffer`，adapter 只暴露中立输入与返回值。

- 直接媒体与本地/RTSP/RTMP 保留直通；播放器包装页的查询参数含 `.m3u8` 不再误判为媒体。HTTP 检查使用 NG `AnalyzeUrl`，沿用源 Cookie、请求规则与代理，最多读取 256 KiB 文本。
- 网页通过导航和资源请求回调捕获媒体，定时读取 video/source 并尝试静音播放，触发延迟加载。普通网页重定向与 iframe 继续在后台 WebView 内加载，捕获后交给现有应用视频播放页。
- 保留实际媒体请求头，过滤 Range/Host 等请求状态头，跨主机提取不复制解析站 Cookie/Authorization；嗅探资源可从 WebView CookieManager 取得目标域 Cookie。
- HTML 正文使用章节/文章地址为 base 加载；只有 MPD 文档写入原有临时 DASH 文件。30 秒总解析预算，切集/退出取消旧请求，迟到结果仍受播放会话版本保护。

本地 JVM 夹具覆盖包装页、重定向响应、无后缀媒体 MIME、相对/转义地址、请求头隔离、HTML/MPD 区分及取消/超时。后台 WebView 的真实站点 JavaScript、跨域 iframe、登录挑战和设备连续播放仍需 APK/真机验收；`blob:`、DRM 及必须手动点击的站点不保证可提取原生播放器地址。

验证结果：`:lib:source-tvbox:testDebugUnitTest` 19 项通过（含新增网页视频解析 9 项），`:app:compileAppDebugKotlin` 与 `verifyFeatureBoundaries` 通过。未进行 APK 安装或真机站点播放验证。

规则主动跳转的补齐：对照来源工程 `ui/book/video/AnalyzingVideoRule.kt`、`VideoPlayViewModel.resolveChapterMediaItem()` 以及 `JsExtensions.startBrowser()/startBrowserAwait()`，视频正文规则可能通过调用浏览器 API 提供播放页，而非返回地址。单纯给正文返回值增加嗅探不足以阻止这类规则打开前台网页。

现在单链接、订阅、选集从规则执行到播放地址准备都处于 `VideoRuleAnalysis` 上下文内。`startBrowser()` 与 `startBrowserAwait()` 捕获目标 URL/HTML 后立即返回，前者不启动浏览器，后者返回与来源工程一致的 URL 形式 `StrResponse`，不等待网页关闭。原生浏览器启动入口、`showBrowser()`、HTTP `openUrl()` 与嵌套 `openVideoPlayer()` 也在这一上下文中被捕获，避免绕过或另起播放器；捕获地址优先于规则普通返回值，即便规则随后返回空文本或解析失败，也继续后台解析已捕获的播放页。取消始终继续向外传播。

会话使用协程 `ThreadContextElement` 传播各自的捕获状态，取代来源工程的全局 `isAnalyzing/capturedUrl` 标志。并发解析、线程切换和嵌套执行互不串用，退出或取消后恢复普通网页操作。真实站点和设备跳转行为仍待真机验证。

补齐后的本地验证：`:lib:video-support:test` 12 项通过（含新增规则拦截上下文 6 项），`:lib:source-tvbox:testDebugUnitTest` 19 项通过，应用 Kotlin 编译及模块边界检查通过。未安装 APK 或验证真实书源跳转。

2026-11-10 data URI 规则兼容：设备日志中的 `unknown protocol: data` 来自 `NetworkUtils.getAbsoluteURL()` 先将章节的 data 基准地址传入 `java.net.URL`。现先返回绝对 HTTP(S)/data 目标，遇到 data 基准地址则保留目标供规则继续处理。保留 `data:;base64,...,{"type":"Buding"}` 的数据、选项及原有 Base64→字节→十六进制处理。日志在异常后已经记录“获取成功”，因此该异常属于已捕获的地址处理警告，不能据此断定真实站点播放失败的原因。

验证：定向运行 `NetworkUtilsDataUrlTest` 5 项全部通过，覆盖日志原始章节、data 基准地址下的绝对/相对目标、HTTP 基准地址下的 data 目标及正常相对/协议相对地址拼接，同时断言不输出异常。使用临时 Gradle init 脚本隔离此次测试源；应用 Kotlin 编译与 `verifyFeatureBoundaries` 通过。未进行 APK 安装或真实书源设备播放验证。

21:36 设备日志补齐：正文已经包含签名 M3U8，但后续在 WebView 启动前报“未找到有效的视频播放网页”。正文不再提前被浏览器捕获值替换；有效 HTTP/媒体/内嵌 HTML 捕获仍优先，不支持的 data 规则载荷则保留正文作为回退。参照原版 `AnalyzeUrl.extractVideoUrlFromText()` / `extractGzipM3u8OrUrlFromHtml()`，增加 `data:text/html` Base64/百分号解码，以及 `atob()` 内 GZIP M3U8 提取；解压上限为 2 MiB。动态内嵌 HTML 交给后台 WebView，基准地址及 Cookie 使用 HTTP 章节地址或书源地址，不把虚拟 data 章节当作网页地址。

新增回归覆盖日志格式的混合正文和签名 URL、Base64 HTML 视频、GZIP 播放列表、动态 HTML 基准地址、无效捕获回退。source-tvbox 共 24 项测试通过；应用 Kotlin 编译及模块边界检查通过。日志未记录捕获值，无法仅凭此日志断定其确切内容；此次补齐缺失路径，尚未进行真实书源设备复测。

严格对照补齐：普通视频规则现使用按来源实现移植的 `LegadoVideoResolver` 和宿主 `NgLegadoVideoResolver`，取代上述简化解析器接入；捕获页恢复来源优先级，补齐完整 HTTP→12秒嗅探→HTML 回退、源 webJs/延迟、编码 URL 选项、历史头部、多线路和播放 MIME/密钥请求策略。完整出处、差异与加解密核对见 [视频规则对照](audits/VIDEO_RULE_REFERENCE_PARITY.md)。仍保留 NG 协程会话隔离、现有播放器和扩展本地媒体入口。

严格对照最终验证：68 项 JVM/宿主定向测试通过，应用 Kotlin 编译、模块边界检查及 diff 空白检查通过。真实书源加解密、后台 WebView 及设备播放仍未验收。
