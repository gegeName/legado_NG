# 功能模块迁移实施记录

实施日期：2026-11-09。对应 [迁移方案](LEGADO_FEATURE_MODULE_MIGRATION_PLAN.md)。本记录区分代码接入、本地验证和真实运行验收；编译、可控 HTTP 夹具或桌面音频样本均不代表 Android 设备验收。

## 基线与工作区

- 目标：`D:\project\legado_NG`，分支 `codex/migrate-legado-features`，HEAD `7b6087410f287511080f2a93b82795231f4b72fd`。
- 参考来源：`D:\project\legado`，HEAD `339e9af79a15beae42e3839191a9531b4542ba08`，只读参考，未修改来源工程。
- 开工前已有 `.gitignore`、`app/build.gradle`、`gradle.properties`、`settings.gradle` 及未跟踪方案/audits。原有仓库、JDK、Glide 和环境调整保留；未重置、提交或覆盖整个工作区。
- 固定样例位于各模块和 `app/src/test/java/io/legado/app/integration`，使用内存扩展、固定密文和回环 HTTP。测试源码纳入可审查改动。

## 实施范围

| 批次 | 已接入代码 | 验证与限制 |
| --- | --- | --- |
| P0/P1 | 15 个独立模块、中立 API、adapter、集中构建、integration、hook 清单、边界检查 | 默认装配已通过 Kotlin 编译及 15 模块边界检查；未改 NG Room schema |
| P2 异次元 | 单条/合集/压缩源导入、预览保存、规则转换/结果归一化、JS fetch/AES/ES、URL 与图片正文/解密桥 | JVM/宿主固定样例；不承诺任意加密规则或真实站点兼容 |
| P3 Mihon/扩展底座 | 公共 ABI、仓库 JSON/protobuf、信任、私有/本地 APK 安装、加载/卸载、源启停、筛选、偏好、搜索到图片/缓存/下载 | 有本地提供方与运行时测试；实际 APK 动态加载、系统包可见性和自定义偏好界面需设备验证 |
| P4-A 漫画资源 | 统一书籍原图缓存、共享在途任务、独立订阅与租约、3 总并发/1 后台、配置/Cookie/扩展版本指纹、目录重排屏障、固定下载保护 | 正文完成与原图补齐分开；Glide、正文、章节、视口和翻译共用 resolver；未实测流量/性能 |
| P4-B 漫画观看 | 独立章节/原图预取设置、0 预取、24 张视口预算、元数据只读、跨章等待/失败释放、最多 32 个空分卷跳过、横向双页/RTL/原始进度映射 | 保留 NG 连续/分页和翻页代次；布局、比例、翻页本地回归，长图/旋转/手势需设备验证 |
| P4-C 漫画翻译 | ML Kit OCR、Google/Bing/DeepL/百度/LibreTranslate、NG 文本 AI、擦字回填、独立前后台队列、部分译文补译、原图先出、租约/缓存清理 | 手选提供方不自动换源；自动模式有限回退；OCR 效果/在线凭据服务未验收 |
| P4-PDF | 可选 PDF 模块、预览/高清、原生单队列、窗口/冷却/OOM/临时发布/容量与租约保护 | P01–P12 逐项记录见 [漫画实施矩阵](audits/MANGA_FEATURE_IMPLEMENTATION_MATRIX.md)；夹具不证明原生渲染或堆峰值 |
| P4-D | 阅读回归、hook 登记、性能证据边界 | 性能、流量与内存基准尚无；不声明提速比例 |
| P5 视频/Aniyomi | 季度分卷、重复 episode ID 隔离、hoster/线路/清晰度、字幕/外部音轨、会话代次、位置恢复、过期资源一次重解析；复用 NG 单一播放器 | Media3 统一版本并纳入 HLS/DASH/RTSP/SmoothStreaming/RTMP；设备协议和效果另验 |
| P6 TVBox | 配置/站点管理、CMS、JAR、常驻 QuickJS/同步 Rhino、JSON/聚合解析、WebView 嗅探、会话代理/Range/HEAD、私有文件路径/命名空间 | CMS、同步 JS、解析和代理有本地夹具；原生 QuickJS、真实 Spider/JAR/WebView 未验收 |
| T1 MultiTTS | 安全 ZIP/YAML、稳定音色目录、XFPEIYIN/HUOSHAN/DIRECT_HTTP 三类模板、NG SCRIPT 导入/目录、签名取消/响应校验 | 复用 SYSTEM/SCRIPT、多角色与播放；实际 ZIP、讯飞桌面抽样不代表 Android 发声或其他模板全量在线验收 |
| P7 备份/可选构建 | 版本化配置归档、恢复前校验、NG 加密备份入口、未知/未来模块保留、本机信任隔离；PDF/播放器增强编译期可选 | 备份 JVM 测试；宿主恢复、APK/DEX/R8 和完整上游合并演练尚未验收 |

## 模块与数据

模块清单见 [lib/README.md](../lib/README.md)，NG 既有文件接入面见 [UPSTREAM_HOOKS.md](../lib/UPSTREAM_HOOKS.md)。`app` 只直接依赖 `feature-api` 与 `legado-ng-adapter`。功能库不反向引用 NG 实体、`appDb`、`AppConfig`、`ReadManga` 或 `app.R`。

源身份包含 provider、内容类型、原始 ID 和 TVBox 配置命名空间。新数据采用版本化插件 URI，旧 `tachiyomi://` 按内容类型解码。Aniyomi 季度目录保留 parentItem/originalId。停用、卸载和配置移除不会级联删除书架。

扩展实际偏好文件沿用 ABI 的 `source_<id>`，Mihon/Aniyomi 使用相同数字 id 时仍可能碰撞；provider 隔离的路由、筛选和图片键不改变这个限制，不能宣称偏好存储完全隔离。

原图使用 NG 书籍图片缓存作为规范持久缓存，Glide 缓存是派生层。原图指纹包含源、解密/请求配置、Cookie、插件版本/签名/偏好，以 JSON 编码保留字段边界和 null 值，避免缺失规则与字面脚本 `null` 冲突；译图另含原图内容、OCR/翻译参数和 AI 身份。租约保护读文件，失效代次拒绝旧发布。PDF/OCR/译图合成共享估算内存准入预算；预算不统计全部 Glide/原生分配，不是硬性堆峰值保证。

## 备份与可选构建

NG OTHER 备份分类新增加密 `featureModules.json.aes`。恢复前校验整个归档及已知配置，保留未知/未来 contributor；恢复开始时失效原图、翻译、PDF、预取和播放会话，恢复后重建扩展/TVBox 源。运行中的 JS/代理端口、缓存、扩展 APK/JAR 和本机信任授权不导出；MultiTTS SCRIPT 定义仍随 NG `config.xml`。不覆盖数据库文件或新增源项目 Room 表。

默认启用全部 15 个模块。以下构建移除 PDF 分档优化和 Media3 画面增强，其余功能目前固定装配：

```powershell
.\gradlew.bat :app:compileAppDebugKotlin verifyFeatureBoundaries verifyFeatureArtifacts `
  -PlegadoPdf=false -PlegadoVideoEffects=false -x :app:verifyNgDesignSystem
```

缺 PDF 优化时 adapter 使用缺省实现，宿主回到 NG 原 PDF 读取并隐藏优化菜单；缺播放器增强时使用 NG 原播放器。

## 验证

工具链为 JDK 17 (`D:\jdk-17.0.2`) 和项目固定 Gradle/Kotlin；本轮不执行 APK 构建、安装、设备或截图操作。

| 日志 | 结果 |
| --- | --- |
| `build/feature-closure-check.log` | 默认 15 模块装配、应用 Kotlin、选定模块测试及边界检查成功，8m13s |
| `build/feature-final-check.log` | 31 个模块测试类共 167 项、0 失败、2 项未传 MultiTTS 本地样本参数而跳过；视觉及中间产物检查通过。该次宿主全组有环境/缓存失败，见下文 |
| `build/feature-host-regression.log` | 宿主 125 项中 123 通过、2 个缓存回归失败；修复后两个对应类共 15 项聚焦回归通过，见 `build/feature-focused-host-tests.log` |
| `build/feature-focused-recheck.log` | 最新图片指纹及缓存重排修改的应用 Kotlin 编译、15 模块边界、28 个 integration/library UI 视觉检查和中间产物检查通过；随后宿主测试阶段因 Robolectric SDK 下载失败而中止 |
| `build/feature-minimal-check.log` | PDF 与播放器增强关闭时应用 Kotlin 编译、边界/视觉/中间产物检查通过，5m39s |
| `build/feature-full-host-tests.log` | 全量宿主复跑未完成：书架分组 fixture 唯一键冲突已修正，5 项独立回归通过（`build/book-group-assignment-recheck.log`）；后续测试尝试联网获取 Robolectric Android SDK 时受运行环境网络限制，未将整套宿主测试记为通过 |
| `build/upstream-hooks-check.log` | 已跟踪 hook 差异在临时索引可应用到 `15e1deb88`，退出 0；仅文本检查，不是完整合并或构建 |

`BookChapterCacheTest.changedDecodeConfigurationCannotReuseOldVersionOrReplaceItWithFailure` 原因为旧指纹把 null 与字符串 `null` 合并，现已修正；`ChapterCacheReorderTest` 原因为 Robolectric 各测试方法切换应用临时目录，而音频重排使用另一个缓存根，测试夹具现统一目录。两个类的全部 15 项测试通过。系统 Gradle 缓存下的聚焦重跑复用已缓存的 Robolectric API 29 依赖；应用 Kotlin 编译和模块结构检查也重新通过。完整宿主复跑触发其他测试的动态 SDK 下载，当前环境不能在测试 JVM 内联网，因此不能据此判断整套宿主测试通过。

书架分组测试原 NPE 是 fixture 唯一键冲突：`Book` 的 `(name, author)` 唯一索引与多条默认空名称/作者记录冲突，Room `REPLACE` 使查询不到先前插入项。测试现为每本 fixture 指定唯一名称，`BookGroupAssignmentTest` 五项全部通过；生产分组 DAO 无需改动。

`verifyFeatureVisuals` 覆盖 integration 和 Library UI，`verifyFeatureArtifacts` 检查中间 JAR 的共享 ABI 唯一性、merged minSdk、隔离 JS Service、authority 与可选依赖。中间产物检查不等价 APK/DEX/R8 验证。

`:app:verifyNgDesignSystem` 有两项本批之前的失败：`BookInfoActivity.kt` hex 为 1（基线 0），`AccentStrokeImageButton.kt` visual_boolean 为 4（基线 0）。文件与 HEAD 无差异，本批未更改基线，应用 Kotlin 编译排除此任务。旧全组漫画回归另有 `MangaFooterConfigTest` 反射字段数与 `MangaReaderContractTest` 在 JDK 17 下 Gson 访问 LocalDate 两项已知问题；本轮聚焦测试不将它们算作通过。

## 未完成验收

### 2026-11-10 仓库管理补齐

- Mihon/Aniyomi 扩展管理增加仓库列表、地址、逐仓库刷新结果与自定义仓库移除；内置仓库标识并保留。支持 `repo.json` 转同目录 JSON 索引，添加时先验证元信息和索引，失败不保存。部分仓库刷新失败时保留其上次索引。
- 扩展、仓库、TVBox 和来源设置改为 `NgSourceManagementActivity` 承载的完整页面，沿用来源工程 Activity 导航方式，顶部返回/系统返回回到上一页。各页使用单一滚动列表，修复固定控件挤占高度导致列表不可见；统一 NG Surface 与文字颜色，避免白底文字不可见。签名信任/卸载确认仍使用小型确认对话框。
- 管理页公共容器改为透明，直接透出 `BaseActivity` 的主题底图、渐变或动态场景；标题栏复用 `NgFloatingTitleToolbar`，仓库/TVBox 卡片复用 `NgGlassSurface` 与既有详情卡材质，并接入统一 backdrop host，避免整页不透明白底遮挡背景。文字颜色继续跟随主题。
- 背景验证 `build/extensions-backdrop-ui-check.log`：应用 Kotlin 编译、`verifyFeatureVisuals` 和 2 项原生 Compose 截图录制通过；在 411dp 浅色、320dp 深色样例背景下查看了底图透出及文字/按钮对比度。测试使用渐变背景替身，未进行真实主题底图或动态场景的设备验收；继续排除既有失败的 `verifyNgDesignSystem`。
- 扩展列表按来源工程的 44dp 图标、名称/版本/语言、右侧主操作重排；加入仓库/语言与已导入/更新/下载中筛选，管理入口收进顶部菜单。仓库用分层卡片，来源使用名称/描述、独立开关与次级操作。
- 扩展列表进入时自动刷新；选择具体仓库立即拉取对应索引，选择“全部仓库”刷新全部，顶部刷新按钮遵循当前仓库选择。快速切换取消旧请求并等待其结束，只显示当前请求的错误；下载任务保持独立。进入仓库管理自动刷新并显示逐仓库结果。索引刷新共用互斥锁，取消时恢复仓库状态，避免停留在“正在刷新”。
- 扩展下载由 Activity ViewModel 保存，每个包有独立任务、百分比、导入阶段、错误、取消与重试；多个包可同时下载，同包重复点击去重。旋转和进入子管理页保留任务；退出扩展 Activity 或进程结束后不承诺继续下载。并发状态回归使用受控 Flow，不等于真实 APK 联网/设备验收。
- 本轮 `build/extensions-final-ui-check.log`：应用 Kotlin 编译、主题规范、5 项下载回归（包括立即取消后重试）及 2 项原生 Compose 截图录制通过。截图覆盖 411dp 常规与 320dp 深色列表；已查看截图并修正深色箭头/占位图标/取消按钮对比度。截图使用固定示例数据，不代表真实仓库与 APK 已验收。
- 仓库选择刷新补充验证 `build/extensions-repository-refresh-check.log`：应用/运行时 Kotlin 编译、`verifyFeatureVisuals`、3 项仓库刷新回归及 5 项下载回归通过。覆盖具体仓库/全部仓库请求范围、连续切换时旧请求取消与错误隔离、同仓库失败后重试；2 个截图测试方法通过，本次未重新录制截图。仍排除既有失败的 `verifyNgDesignSystem`，未执行真实网络或设备操作。
- TVBox 明确仓库添加、更新和移除结果，显示已保存仓库/站点数量及空列表提示。
- 对照来源工程的 `ExtensionInstaller`，继续使用应用私有 APK 文件加载，无需系统安装；界面改为“下载并导入”，并在导入完成后显式请求重新加载。签名信任仍保留。
- 真实仓库联网、Android APK 动态加载及设备界面仍需实际验收；本地编译与测试不能替代以上验收。

1. 设备/模拟器上的扩展安装和动态加载、QuickJS 原生 Promise/字节桥/超时、WebView、OCR、PDF 原生渲染、视频字幕/音轨/增强及音色真实播放。
2. 按扩展/站点/版本记录真实 Mihon、Aniyomi、TVBox JAR/JS、异次元源；带凭据的翻译服务、HUOSHAN/DIRECT_HTTP 实际音色样本。Rhino 夹具不代表原生 QuickJS 通过。
3. 长图、双页、旋转、缩放、自动阅读和后台/悬浮设备回归，以及性能/流量/内存/电量测量。
4. 宿主跨版本完整备份恢复及除 PDF/播放器以外的任意可选模块组合。
5. APK DEX、原生 ABI、R8/混淆验证，以及另一 NG 基线的真实合并、冲突处理和编译回归。

上述验收未完成前，不将迁移方案最终结束标准标记为通过。
