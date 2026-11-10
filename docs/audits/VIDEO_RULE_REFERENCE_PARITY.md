# 视频规则与嗅探对照

日期：2026-11-10。参考 `D:\project\legado` 当前源码，HEAD `339e9af79a15beae42e3839191a9531b4542ba08`；来源工作区的探索页改动与本次解析链无关，未修改来源工程。目标 `D:\project\legado_NG` 保留原有暂存及未暂存内容。

## 执行链及来源

普通视频规则改用 `LegadoVideoResolver`；不再使用先前为这些入口接入的简化 `WebVideoResolver`。后者保留给 TVBox 等已有中立调用者。宿主端口集中于 `integration/video/NgLegadoVideoResolver.kt`，继续使用 NG 自己的 `AnalyzeUrl`、`BackstageWebView`、规则变量及 Cookie/代理实现。

| 环节 | 参考源码 | NG 接入与验证 |
| --- | --- | --- |
| 正文读取 | `ui/book/video/VideoPlayViewModel.resolveChapterMediaItem` | 优先已有章节正文，再请求正文；失败/空正文回退非分卷章节地址，取消向外传播 |
| 主动浏览器跳转 | `ui/book/video/AnalyzingVideoRule`，`JsExtensions.startBrowser/Await` | 视频规则上下文捕获仍优先；正常规则不打开前台浏览器；NG 使用独立协程上下文，保留并发隔离 |
| 多线路候选 | `VideoPlayViewModel.normalizeVideoPlayUrls` | 按行 `线路::地址` 拆分，逐候选解析；保留完整 URL 规则选项，在拆分后解析；首个候选复用 AnalyzeUrl，避免重复执行签名 JS |
| 历史扩展 JSON | `VideoPlayViewModel.parsePluginVideoSource` / `data/entities/VideoSource` | 解析 resolutions/defaultIndex，源 headers 与所选清晰度 headers 按来源顺序合并；NG 既有扩展会话仍保留原资源刷新与选择流程 |
| 编码规则选项 | `AnalyzeUrl.analyzeUrl` / `paramPattern` | 识别 `,{...}` 和 `,%7B...`，先 URL 解码再解析 headers/webJs/webViewDelayTime/POST 参数 |
| 直链与包装页判定 | `AnalyzeUrl.isValidVideoUrl` | 移植原判定顺序、包装页排除、HLS 路径及 ts/m4s；NG 另保留本地文件/content/proxy 和原有流协议直通 |
| 正文提取 | `AnalyzeUrl.extractVideoUrlFromText` | 移植正文/HTML 匹配顺序、Java 转义处理、Base64 HTML 解码及 atob 内 GZIP M3U8 解压；原版正则的边界行为也保留测试 |
| HTTP 检查 | `AnalyzeUrl.getMediaItemAwait` | 媒体 MIME → 响应 URL/正文媒体 → EXT M3U；文本检查包含 XML，最多 256 KiB；保留参考实现的源请求头传递 |
| 后台嗅探 | 同上及 `BackstageWebView.SnifferWebClient` | 12 秒局部预算；相同资源正则；导航、onLoadResource、新旧 shouldInterceptRequest 均匹配；执行请求自身 webJs，延迟 max(源延迟, 2000ms)+1000ms |
| HTML 回退 | `AnalyzeUrl.getMediaItemAwait` 第三阶段 | 嗅探空结果/异常/局部超时继续 `getStrResponseAwait(useWebView=true)`，执行既有响应处理；父任务取消不会被当成普通失败 |
| 对称/非对称加密 | `help/crypto/SymmetricCryptoAndroid`、`AsymmetricCrypto`，`JsEncodeUtils` | 两个 crypto 类的源码一致；继续宿主原桥接，AES 固定 NIST 向量验证 hex/Base64/字节/流 |
| 异次元 AES/ES | `help/storage/YiciyuanSourceConverter` | 对照模块 `source-yiciyuan` 的对应方法及 CompatibilityCrypto，原模式、key/IV、空数据和四类输入语义保留；现有模块回归重跑 |
| 原始 M3U8 广告过滤 | `help/exoplayer/M3u8AdFilter` | 规则正文沿用同样关键字及 EXTINF/下一行处理，直播不变，保留 KEY/IV 元数据 |
| 播放 MIME | `help/exoplayer/ExoPlayerHelper.inferMimeType` | 媒体项显式设置同样 MIME，尤其 data: HLS，避免被当成普通渐进文件 |
| 流与密钥请求 | `ExoPlayerHelper.SmartDataSource` | 播放列表/ts/key/HLS/直播走无磁盘缓存上游；普通视频仍沿用 NG 缓存；DefaultDataSource 保留 data/本地/RTMP 路由 |
| 历史请求头 | `IVideoPlayer.toVideoSource` / `ExoPlayerHelper.createResolvingDataSource` | 在 URL 进入规则解析前拆分 🚧 JSON 请求头，播放列表、分片及密钥继续沿用最终请求头 |

## 验证边界

新增参考解析器回归覆盖各阶段顺序、签名查询串、Base64+GZIP 加密 HLS、KEY/IV/序号保留、局部超时回退与父取消、多线路和历史头部。宿主定向测试覆盖百分号编码选项、重定向参数和 NIST AES-CBC 向量，异次元原有加解密回归也重跑。

最终本地结果：source-tvbox 36 项、source-yiciyuan 12 项、video-support 12 项、宿主定向回归 8 项，共 68 项通过；应用 Kotlin 编译和 `verifyFeatureBoundaries` 通过，`git diff --check` 通过。宿主定向测试使用临时 Gradle init 脚本仅选择两组回归源码，未改应用默认测试源集。日志为根目录忽略文件 `video-reference-final-verification.log`。

编译与 JVM 回归不能证明具体站点已在 Android WebView 中完成 JS 解密或设备已成功解码；尚未安装 APK，也未完成真实书源设备复测。这里描述源码对照及本地验证范围，不宣称任意站点、DRM 或所有加密格式均可播放。
