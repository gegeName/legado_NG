# 漫画观看、预加载与缓存迁移核对表

核对日期：2026-11-09。源项目 `D:/project/legado`，`dev_text_v`，HEAD `339e9af`；目标 `D:/project/legado_NG`，`codex/migrate-legado-features`，HEAD `7b6087410`。本文件是[总迁移方案](../LEGADO_FEATURE_MODULE_MIGRATION_PLAN.md)第 5、6 节的详细依据，不表示功能已经迁入。

结论：不能只迁一个“预加载张数”设置。需要分别处理章节正文、章节原图、视口文件、图片尺寸、译图和 PDF 渲染；各层命中、完成、取消与清理语义不同。NG 已有部分更完整的 Compose 与缓存保护，应以 NG 为宿主补差异。

## 1. 证据索引与阅读方式

表中 `S` 表示源项目，`N` 表示目标项目；后续 `S1:630` 表示该文件第 630 行附近的函数。源码行号以本次核对为准，实施时按函数名重新定位。

| 编号 | 实际文件 | 主要核对内容 |
| --- | --- | --- |
| S1 | [源 ReadManga.kt](D:/project/legado/app/src/main/java/io/legado/app/model/ReadManga.kt:167) | 章节、图片缓存任务、跨章、双页、进度 |
| S2 | [源 BookHelp.kt](D:/project/legado/app/src/main/java/io/legado/app/help/book/BookHelp.kt:132) | 原图保存、校验、URL 与清理 |
| S3 | [源 ReadMangaActivity.kt](D:/project/legado/app/src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt:425) | 稳定定位、三种视口预取触发、设置与生命周期 |
| S4 | [源 MangaFlipController.kt](D:/project/legado/app/src/main/java/io/legado/app/ui/book/manga/MangaFlipController.kt:31) | 等待跨章、长图边界、RTL |
| S5 | [源 MangaSpreadPage.kt](D:/project/legado/app/src/main/java/io/legado/app/ui/book/manga/entities/MangaSpreadPage.kt:1) | 左右页、方向页与进度页 |
| S6 | [源 MangaAdapter.kt](D:/project/legado/app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaAdapter.kt:1) | 列表更新、双页槽、预取、回收 |
| S7 | [源 MangaVH.kt](D:/project/legado/app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaVH.kt:81) | 加载代次、缓存优先、进度、重试、PDF 升级 |
| S8 | [源 BookCover.kt](D:/project/legado/app/src/main/java/io/legado/app/model/BookCover.kt:139) | 显示 HIGH、预取 LOW、Drawable 预解码 |
| S9 | [源 MangaLayoutManager.kt](D:/project/legado/app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaLayoutManager.kt:1) | 额外布局空间 |
| S10 | [源 MangaTranslationManager.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaTranslationManager.kt:77) | 任务合并、输入、OCR、两级缓存与发布 |
| S11 | [源 MangaTranslationQueue.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaTranslationQueue.kt:5) | 前台提升、后台配额与公平调度 |
| S12 | [源 MangaTranslationInput.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaTranslationInput.kt:10) | 输入快照与读取重试 |
| S13 | [源 MangaPublicTranslator.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaPublicTranslator.kt:38) | 文本请求并发、提供方冷却 |
| S14 | [源 MangaTranslationLoader.kt](D:/project/legado/app/src/main/java/io/legado/app/help/glide/MangaTranslationLoader.kt:27) | 翻译模型、等待结果与取消边界 |
| S15 | [源 PdfRenderManager.kt](D:/project/legado/app/src/main/java/io/legado/app/model/localBook/PdfRenderManager.kt:18) | 优先级、双档、窗口、容量、取消 |
| S16 | [源 PdfFile.kt](D:/project/legado/app/src/main/java/io/legado/app/model/localBook/PdfFile.kt:28) | 像素预算、OOM 降级、压缩与释放 |
| S17 | [源 MangaTranslationConfig.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaTranslationConfig.kt:23) | 翻译预取与提供方配置 |
| S18 | [源 MangaTranslationFallback.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaTranslationFallback.kt:13) | 文本去重、缺失补译与增量缓存 |
| S19 | [源 MangaTranslationPageCache.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaTranslationPageCache.kt:1) | 完整/部分译图、缺失区域 |
| S20 | [源 MangaTextEraser.kt](D:/project/legado/app/src/main/java/io/legado/app/model/manga/translation/MangaTextEraser.kt:20) | 重叠框、擦字掩码、布局边界 |
| S21 | [源 ImageProvider.kt](D:/project/legado/app/src/main/java/io/legado/app/model/ImageProvider.kt:126) / [BookCover.kt](D:/project/legado/app/src/main/java/io/legado/app/model/BookCover.kt:140) / [OkHttpStreamFetcher.kt](D:/project/legado/app/src/main/java/io/legado/app/help/glide/OkHttpStreamFetcher.kt:53) | 正文与漫画共用书籍图片文件、漫画 Glide 本地优先及网络请求分流 |
| N1 | [NG ReadManga.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/model/ReadManga.kt:60) | 加载代次、目录重排屏障、章节预下载 |
| N2 | [NG MangaImages.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/ui/book/manga/MangaImages.kt:88) | 连续阅读、尺寸占位、可取消文件预取 |
| N3 | [NG MangaPagedImages.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/ui/book/manga/MangaPagedImages.kt:1) | 分页、翻页动画、手势与预取 |
| N4 | [NG MangaPageTurn.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/ui/book/manga/MangaPageTurn.kt:1) | 列表身份、跳页版本与提交条件 |
| N5 | [NG MangaReaderState.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/ui/book/manga/MangaReaderState.kt:1) | 原始页键、状态与动作消费 |
| N6 | [NG ReadMangaActivity.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt:176) | 内容更新、可见页、跳页、进度 |
| N7 | [NG MangaImageRatioCache.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/ui/book/manga/MangaImageRatioCache.kt:1) | 256 项比例 LRU |
| N8 | [NG BookCover.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/model/BookCover.kt:163) | downloadOnly、书籍缓存选项与显示策略 |
| N9 | [NG BookHelp.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/book/BookHelp.kt:170) | 章节发布保护、严格图片写入 |
| N10 | [NG BookImageCache.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/book/BookImageCache.kt:10) | 非空、图片尺寸/SVG 校验 |
| N11 | [NG OkHttpStreamFetcher.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/help/glide/OkHttpStreamFetcher.kt:61) | 本地书籍缓存优先、网络与解密 |
| N12 | [NG PdfFile.kt](D:/project/legado_NG/app/src/main/java/io/legado/app/model/localBook/PdfFile.kt:28) | 现有同步 PDF 渲染基础 |

## 2. 源项目功能逐项清单与 NG 差异

下表“迁移”指待实施动作；“保留”指 NG 已有能力。任务合并不等于完整资源预算，源码有取消判断也不等于可以中断所有已执行的底层操作。

### 2.1 章节正文、原图与缓存窗口

| ID | 源项目实际行为与证据 | NG 当前情况 | 迁移处理 |
| --- | --- | --- | --- |
| C01 | 当前章、后一章、前一章一起加载；切章复用相邻章对象（S1:239、463、506） | 已有相邻三章与复用（N1:199、312） | 保留，新增协调器接收结果，不再建第二套章节所有者 |
| C02 | 远端预下载从当前章 ±2 开始；向前到当前+N，向后最多 5 章（S1:630） | 范围结构相同，但 N 用 `preDownloadNum`（N1:424） | 保留调度结构，新增独立漫画章节配置；边界夹到有效章节，不复制到 chapterSize 的多余迭代 |
| C03 | 章节网络信号量 2、缺章延迟 1000ms、同章失败达到 3 次跳过（S1:80、630、689） | 同样已有 | 列为共同基线，取消不计失败；不称作新迁移优化 |
| C04 | loading、下载完成/失败计数同步访问（S1:167–200） | 有代次与部分同步保护 | 审核共享集合的完整生命周期，合并到 NG 保护之内 |
| C05 | 命中章节正文仍补齐原图，不能只凭正文标记图片下载完成（S1:270、689） | `downloadIndex` 命中正文即可标记 downloaded（N1:464） | 区分 ContentReady 与 ImagesComplete；仅漫画原图任务使用后者 |
| C06 | 当前±1章原图异步缓存，章节先交给阅读器；较远章等待原图缓存（S1:801） | 阅读章节链没有同等原图填充窗口 | 迁移“原图不阻塞内容发布”，统一交给会话任务池 |
| C07 | 原图窗口为当前−min(5,N)…当前+N，窗口外停止新增/保存并取消过期章任务（S1:217、662） | 视口预取会取消，章节原图层缺少该窗口 | 策略进 reader-support，执行与文件写入在宿主；判断还要包含源身份、代次 |
| C08 | 异步分支每章一个图片 Job；LAZY 注册后启动，旧 Job 只能移除自己；await 分支直接保存，不经过该 Job 表（S1:202、823、853） | 没有等价章节原图任务表 | 迁移幂等与身份检查，补齐跨分支资源共享；允许可见页加入同一资源任务 |
| C09 | 每次 `saveImages` 并发设为 1，保存前检查 `shouldContinue`（S1:823、853；S2:225） | 默认 saveImages 走通用线程配置，无窗口回调 | 加入可取消端口；每章 1 不代表全局 1，另设总并发预算 |
| C10 | 成功标记参考 `hasImageContent`：文件存在、尺寸或 SVG 可读；相对地址按章节绝对 URL 展开（S2:445） | 也有该函数，但用法/URL 基准不同，章节预取未以它判断完成（N9:433） | 统一 resolver、writer、completeness 的资源键；校验在 IO，避免可见帧逐章扫描 |
| C11 | 换书/重载/退出取消图片任务与下载子任务；请求捕获 bookUrl/origin/source（S1:85、239、729、997） | 有更强的 loadEpoch 和目录重排取消等待（N1:134、173） | 在 NG epoch 上增加源/图片配置快照；不能换成旧项目较弱的检查 |
| C12 | 虚拟插件书源可构造占位源走插件委托；插件图片走专用 fetcher（S1:270、729；S2:240） | 尚需接入 Mihon 模块 | 通过 source-mihon/ImageResourceResolver，不把插件运行时引进 reader-support |
| C13 | 章节解析只抑制相邻重复 URL，缓存路径提前带入页面（S1:1018） | 页模型与 URL 解析已有，未提供同等动态缓存路径逻辑 | 同章保留页面顺序；`distinctUntilChanged` 不是全书去重，原始页身份需稳定 |
| C14 | EPUB 图片保留内部路径，异步提取后再显示；普通本地文件与 PDF 可优先用缓存（S1:1018；S7:167） | 已有本地图片路径，读取契约需逐类适配 | 原生 EPUB/本地文件读取端口保留，不能把内部路径统一转 HTTP |

### 2.2 视口预取、图片展示与内存

| ID | 源项目实际行为与证据 | NG 当前情况 | 迁移处理 |
| --- | --- | --- | --- |
| I01 | 提交内容/定位完成后，两侧各预取一段；普通值夹到 2…12（S3:530） | 连续/分页按移动方向预取（N2:183、423；N3） | 增加可选初始两侧候选，和方向预取合成一次计划；总预算按原始图片数 |
| I02 | 滚动预取按逻辑方向；220ms 节流，同位置 600ms 内跳过；普通值夹到 2…6（S3:544） | index `distinctUntilChanged`，方向变化更新窗口 | 沿用 NG 的事件流，按需要加入时间合并；快速跳页/反向必须立即刷新候选 |
| I03 | 另有 `RecyclerViewPreloader`，数量来自原始设置/翻译设置（S3:1177） | 由 Compose MangaImagePreloader 承担 | 不移植 RecyclerView 监听器，收敛重复入口 |
| I04 | 展开双页后逐张预取，优先有效本地路径，翻译用独立图片版本身份（S3:573、611；S6:489） | 当前仅识别单 MangaPage | 候选使用 flatMap(realPages)，去重/预算均针对真实资源 |
| I05 | 96 项集合去重，超过上限整表清空；列表首尾/大小签名变化重置（S3:573、626） | 持有实际 Glide targets，窗口差集取消 | 保留 NG targets 所有权；改成会话资源键、配置/内容版本；失败后可重试，不照搬集合与弱签名 |
| I06 | 显示 HIGH、预取 LOW，预取返回 Drawable，可提前解码与变换；未跳过内存缓存（S8:139） | 显示跳过内存缓存，预取 downloadOnly 文件（N8:163、189） | 默认保留 NG 文件预取；可选解码预热需像素/字节上限，不能全量开启 Drawable 内存缓存 |
| I07 | holder 先本地缓存再远端；坏缓存允许删除后重取，并区分用户本地文件（S7:134、253） | fetcher 已优先读经校验的书籍原图，避免二次解密（N10；N11:61） | 保留统一入口；重试仅失效本资源的应用缓存，不删除用户原文件 |
| I08 | 请求键避免重复发起，request/load 双代次拒绝旧回调（S7:81、91、134） | Compose key/lifecycle、内容 Job 与翻页版本已有保护 | 迁移行为到新桥的状态快照；不复制 ViewHolder 字段或全局 listener |
| I09 | 缓存快读不闪加载层：延迟 150ms 显示，完成/回收时取消；原图百分比与翻译状态分开（S7:146） | 有图片加载与错误状态 | Compose 用会话 Job 实现延迟反馈，成功/失败/离页均取消 |
| I10 | 回收时 clear Glide、监听器、延迟加载、EPUB Job、原图切换等状态（S6；S7:81） | 预取 targets 在 inactive/dispose 时 clear（N2:98、183、481） | 保留 NG 生命周期，再加入翻译订阅/PDF 句柄；后台任务不可借 UI 消失继续无限排队 |
| I11 | RecyclerView 额外布局 3/4 屏幕（S9） | 使用 Compose Lazy 布局与自身预取机制 | 不迁旧 LayoutManager；先测布局成本，再调整 Compose，不能把额外布局等同网络预取 |
| I12 | 源当前链路没有 NG 同等的比例 LRU 与缓存元数据预读 | NG 每书/源 256 项比例 LRU；附近 index±4 仅缓存读尺寸，IO bounds 解码（N2:423；N7） | 必须保留；将尺寸读取算独立小预算并可取消，不为反方向尺寸补发网络 |
| I13 | 显示/缓存/翻译入口可复用已存原图；书籍文件缓存有共同读写路径，但不代表网络请求所有权已统一（S1:689、705；S2:240、303、362；S10:254、270） | NG Glide 有缓存复用，但章节 writer 与 Glide 仍是不同执行入口 | 保留书籍缓存桥并补全共享任务注册表；文件命中只证明可复用，不证明缺缓存时不会重复请求 |
| I14 | 普通正文 `ImageProvider.cacheImage/getImage` 与漫画章节 `BookHelp.saveImage` 通过 `BookHelp.getImage(book,url)` 共用按书籍/URL 定位的文件；`ReadManga` 组页与 `MangaAdapter` 预取先取缓存路径，命中后把本地路径交给 Glide；未命中时 `BookCover.preloadManga/loadManga` 设置 `mangaOption`，由 `OkHttpStreamFetcher` 选择漫画 OkHttp 路径（S1:689、705、1022；S2:240、303、362；S7:145；S8:140、170；S21） | 本地已解密书籍缓存优先和 Glide `downloadOnly` 预取均已存在；当前 Glide 网络预取结果不等同于书籍缓存写入，章节 writer 与 Glide 未共享在途请求 | 后续实现固定以 NG 书籍图片文件缓存为规范持久缓存：普通正文、章节 writer、可见漫画页、视口预取和翻译输入使用同一稳定资源键及共享 resolver；缺缓存时只由 resolver 下载/解密/校验/原子落盘一次，Glide loader 与翻译模块分别订阅同一个 FileLease，不另发网络。命中缓存时本地路径给 Glide，翻译可立即读快照并启动 OCR；翻译不等待 Glide 解码。Glide disk cache 仅作可丢弃派生层，不作为独立预取终点 |

### 2.3 跨章、双页、进度与观看

| ID | 源项目实际行为与证据 | NG 当前情况 | 迁移处理 |
| --- | --- | --- | --- |
| R01 | IDLE/WAITING_CONTENT；切章成功后等目标章就绪，再停止滚动和定位；失败解除锁（S4:31、149） | 可见页进入相邻章节即由 Activity 推进，分页另有 turn 协议 | 将命令状态提取到 reader-support；在首次推进前锁定 targetChapter+requestRevision，内容就绪后停惯性滚动、定位目标边缘并提交进度；失败/换源/重排清锁且保留有效进度 |
| R02 | 只在当前章真实首/末图到物理列表边缘且该页完全离开视口边界时翻章；长图未读完不推进（S4:125–193、241） | Compose 已有连续滚动/缩放和边缘信息，但还需统一当前章归属、真实边缘页与 RTL 映射 | Compose 上报中心页、当前章首末原始页、逻辑方向、缩放/偏移及 fully-at-edge；纯策略输出 MoveChapter，不导入 RecyclerView |
| R03 | RTL 把物理方向转逻辑前进/后退，双页取对应方向页（S4:184、268） | NG 普通横向 RTL 也已支持 | 复用 NG 方向协议，双页补映射，不能退回旧代码只在部分模式启用 RTL 的限制 |
| R04 | 回上一章可定位末张，未加载完先记 pending，再确认实际末张和保存；ReadManga 维护 cur/prev/next 可复用章并按移动方向移槽/加载（S1:471–552；S4:149） | 已预载当前±1章并支持 pendingJump，尚需核实并接入等待/失败恢复 | 沿用 NG loadEpoch；复用已就绪前/后章，只加载缺失目标；目标就绪后按真实末页 PageKey 定位；失败保持旧进度，不把0当临时末页写入 |
| R05 | 空分卷/不可读章节跳过，首末章提示；末章尝试 300ms/提示 1500ms 防抖（S1:463、562、1077） | 有 ReaderLoading/tail 状态 | 合并有界跳空章、终点与重试状态，自动阅读也遵守等待锁 |
| R06 | 初始锚点按 chapter/index/URL，定位保护最长 1500ms，期间避免滚动回调写错进度（S3:478、1431） | 连续模式 pendingJump，分页列表/跳页版本已校验（N2:124；N4） | 保留 NG revision 校验，增加锚点确认；超时只退出等待并报告，不保存不匹配页 |
| R07 | 仅横向+双页开启时同章相邻两页配对；奇数末页单独，标题/Loading 不参与（S1:404） | 仅 MangaPage/ReaderLoading（N6:176） | 迁移纯组合算法；首期对齐同章顺序配对，不新增源中不存在的智能拆分 |
| R08 | 左右随 RTL 排列，保存 progress/backward/forward 原始页；左右独立重试/原图（S5；S6） | UI、进度滑块、图片组件均假设单页 | 增加宿主 spread 映射、独立子页翻译状态，阅读进度始终为原始页 |
| R09 | 双页跳页按原始页查其组合位置（S3 末尾 skipToPage） | NG binarySearch 依赖单页顺序（N6:248） | 建原始 PageKey→DisplayItemKey/slot 映射；一并修改 currentPage、页数、分享/保存目标 |
| R10 | 列表更新与可见页状态分离；翻译/原图切换不应改变逻辑页（S3；S6） | NG 分页提交要求同列表实例、同 jumpRevision、匹配 from/to（N4） | 新组合/切风格/翻译更新保留或明确失效 turn，禁止把译图路径当页键 |
| R11 | 横竖、缩放、滤镜、自动阅读、底栏等源 UI 能力 | NG 已有连续/分页、翻页效果、RTL、手势、自动暂停、独立设计系统 | 保留 NG；旧 XML、Adapter、布局空间不作为必迁实现，逐项做已有行为回归 |

### 2.4 翻译预取、复用与调度

| ID | 源项目实际行为与证据 | NG 当前情况 | 迁移处理 |
| --- | --- | --- | --- |
| T01 | 翻译预取默认 6，夹到 1…12，与普通图片预取值独立（S17） | 没有本方案中的翻译模块 | 单独 translationCount；新增 0=禁用后台翻译，但可见页手动翻译仍可用，这是迁移设计变化 |
| T02 | 页任务总并发 3，默认后台最多 1，可见页优先并提升已排队/运行任务（S10:90、109；S11） | 待接入 | 迁移队列策略并加候选/内存上限；优先级提升不等于抢占正在执行的后台 OCR |
| T03 | 公共文本并发 3、后台最多 2，按页 key 的活动数/上次调度公平分配（S13:38；S11） | 待接入 | 翻译模块内部单独文本预算，避免一页多个气泡占满所有后台名额 |
| T04 | 同页任务合并、多回调；force 取消旧任务并增加图片 revision（S10:116、154） | 待接入 | 返回独立订阅句柄；旧结果按会话/配置/请求版本拒收，只有无消费者时才取消共享任务 |
| T05 | 原图读取先书籍缓存，再 Glide 仅缓存读取，最后 ImageProvider；输入复制为快照（S10:254、270；S12） | NG 已有严格原图缓存链，但翻译与漫画预取尚未共用单一资源订阅 | 翻译作为同一 ImageResourceResolver 的消费者：书籍缓存命中即拿 FileLease/快照启动 OCR，不等待 Glide 解码；未命中时与可见加载、章节补图、视口预取合并网络任务。OCR/文本/译图仍由独立翻译队列调度；读租约防清理竞争 |
| T06 | 输入读取 IOException 最多 3 次，间隔 100ms；临时输入 finally 删除（S12） | 待接入 | 保留有界重试，取消直传；不将所有错误作为无限重试 |
| T07 | 译图缓存包含原图 SHA-256、页、语言/OCR/引擎与 AI 模型、算法/渲染版本；clear/force 有 generation/revision（S10:116、236、308） | 待接入 | 缓存键使用 NG 现有 AI 服务/model 配置的非敏感稳定版本标识，不迁移源 AI 模型/provider/凭据；补 source、OCR/公共翻译配置指纹，不能在日志/文件名暴露密钥 |
| T08 | 文本块缓存跨页复用相同文本；同页同文本合并请求，仅补缺失文字，成功随时写缓存（S10:500、534；S18） | 待接入 | 保留；补配置指纹与原子文本写入，限制同时创建的块任务数 |
| T09 | 完整/部分译图分开缓存；缺失/保留文字区域记录，未译成功的区域保留原文，可补译（S19；S10:365、647；S7:298） | 待接入 | 状态区分 Partial/Ready/Failed，不把部分图标成完整；失败回原图 |
| T10 | ML Kit/AI 识别、重叠框合并、背景估计、擦字掩码和文字布局（S10:348、400、647；S20；MangaTranslationBackground） | 待接入 | OCR/几何与绘制编排归 translation；如需 AI 识别/视觉推理，调用 NG 现有 AI 服务，不移入源 AI 客户端；算法固定图验证，长图分块/下采样内存方案另验 |
| T11 | 源 AI 文本串行、60s 超时与失败冷却；公共提供方有限超时/冷却、手选与 auto 策略（S10:79、84、620；S13） | NG AI 能力已有服务入口，漫画翻译须复用 | AI 请求交 NG 现有服务管理模型、provider、凭据、限流与网络；翻译层只做页级排队/取消/结果版本映射。公共翻译的手选/auto 与限时策略独立保留，不复制源 AI provider 或冷却实现 |
| T12 | 译图写临时文件，取消检查后 move，支持非原子 move 回退；Bitmap finally recycle（S10:375） | 待接入 | 增加发布前会话/配置版本检查、唯一临时文件、清理协调；不能仅因 finally 就声称无竞态 |
| T13 | 当前源 Loader 等译图再回调；取消只是置标志，未退订 Manager；退出/关闭用 cancelAll（S14:76、114；S3:768） | NG 可在 Compose 中独立显示原图 | 改为先原图后译图；取消句柄逐页退订。模块级 cancelAll 限定当前会话，避免伤及其他阅读/离线任务 |

### 2.5 PDF 与本地阅读的性能支持

PDF 是本次发现的独立优化链路，不能漏掉，也不能塞入纯 JVM reader-support。NG 现有 PdfFile 是同步渲染基础，没有同等 PdfRenderManager。以下项作为单独 P4-PDF 交付，普通漫画能力不依赖它。

| ID | 源项目实际行为与证据 | 迁移处理 |
| --- | --- | --- |
| P01 | 单渲染线程，CURRENT > VISIBLE > PRELOAD；同 book/href/tier 合并，排队任务可提升（S15:18、63） | 新增可选 `lib/manga-pdf-support` Android Library；宿主提供文档句柄与文件存储 |
| P02 | PRELOAD 只渲染 PREVIEW；显示请求 HIGH，有 preview 则先回调预览再升级高清（S15:63；S7:189、220） | 两档独立资源键，Compose 拒绝旧会话升级；不能把一份 preview 当成 HIGH 完成 |
| P03 | 向后 16 页，向前 mangaPreDownloadNum 夹到 6…16；每书后台任务最多 8（S15:31、138） | 迁移为独立 PDF 预算；0 要显式停止后台，不能继续被夹为至少 6 |
| P04 | 窗口外取消非 CURRENT 任务，执行前/完成后再检查；cancel(book) 清理状态（S15:138、164、RenderTask） | 保留检查并加 epoch、订阅所有权；Android 原生 render 已执行时通常只能丢弃结果，不能声称立即中断 |
| P05 | 同资源失败 3 次进入 30s 冷却，force 可清零重试（S15:35、63、237） | 保留；取消不累计为失败，配置变化/文档替换清理对应旧状态 |
| P06 | 预览约屏宽 2/3、1MP；高清约两倍屏宽，最大边 4096，按堆上限限制 2/4/6MP（S16:31、158、193） | 迁移分辨率策略并共享总内存预算；同时存在原图/OCR/动画帧时不能分别吃满预算 |
| P07 | OOM 最多 3 次尝试，每次宽高减半，回收失败 Bitmap（S16:202） | 保留有界降级及失败状态；不是无限重试/画质保证 |
| P08 | 预览 JPEG75；高清采样色彩，低色/灰度倾向 PNG，其他 JPEG85（S16:317） | 保留压缩策略，缓存扩展名为 img，读取按实际格式；不宣称总能无损或最小体积 |
| P09 | 临时文件输出后替换，清理同页同档旧版本；href 含文档修改时间版本（S16:228、278） | 改为可校验发布，保留输入版本；复制回退不是原子发布，清理不得删正在显示的文件 |
| P10 | 移动至少 3 页触发窗口清理，generation 拒绝旧清理计划（S15:208） | 保留防抖与过期检查，清理队列 IO 执行 |
| P11 | 每 8 次成功调度容量清理，每书 300MiB、全局 1GiB，按 mtime 删除（S15:237、256） | 单独 PDF 目录/索引；当前源容量清理无完整读租约/离线固定保护，迁移时补齐，避免全 book_cache 扫描成本 |
| P12 | 内存低取消 PRELOAD，严重取消全部；App.onTrimMemory/onLowMemory 转发并释放；换文档/退出关闭 renderer/descriptor（S15:182；S16:41、98、103；源 App.kt:168） | 由 FeatureBootstrap/统一平台回调转发；避免第二个 Application，确保释放与在途渲染串行 |

### 2.6 清理与正确性补充

| ID | 已核对行为 | 迁移处理 |
| --- | --- | --- |
| G01 | 源每切章最多调度一次漫画原图清理；普通漫画保留当前−imageRetainNum…当前+mangaPreDownloadNum（S1:669；S2:132） | 拆成保留策略与宿主清理器，用实际保留章节及资源索引，不能边主线程遍历边删除 |
| G02 | `imageRetainNum == 0` 表示不自动清理；源无完整显式下载保护索引，非零时不能保证主动下载不被删（S2:132） | 保留 0 语义；新增自动缓存/用户固定下载所有权，不以是否有文件代替归属 |
| G03 | 源 saveImage 会记录无效图片但仍落盘；存在性判断可能命中坏缓存（S2:240） | 采用 NG `saveImageForCache` 严格解密、尺寸/SVG 校验与临时文件发布（N9:248、325；N10），不能照搬宽松写入 |
| G04 | 源/NG 某些 URL mutex 在 finally 移除，跨书/跨消费者并不等于完整共享注册表（S2:240；N9:252） | 用引用计数任务注册表/稳定 key 和同任务移除检查，避免旧任务删除新任务、等待者与新任务双写 |
| G05 | NG loadEpoch、目录重排 cancel+join、章节 URL 发布屏障、ReadingRecordTracker 都是现有约束（N1:60、134；N9:170） | 新增任务参与 pause/resume 屏障；目录重排不能继续按旧 chapterIndex 写缓存/进度 |
| G06 | NG 已有 MangaScrollDiagnostics 与比例/翻页/阅读契约测试 | 复用诊断与测试，另加命中、排队、取消、落盘字节等指标，不能把静态分析写成实测提速 |

## 3. 当前实现的风险与不能直接照搬的内容

1. **设置混用和零值不一致**：源 N 同时是章数、视口 item 数和 PDF 前看量；2…12/2…6/6…16 的夹取会让 0 仍有后台请求。双页 item 又可能含两张图。
2. **入口叠加**：源 RecyclerViewPreloader、首次两侧、滚动向前、章节原图、翻译原图各有触发；章节图片的 await 分支也未经过异步 Job 去重表。缓存能减少部分实际网络，但不能据此证明不会重复下载、重复解码或争抢前台。
3. **取消与去重有限**：96 项集合不是 LRU；size/首尾签名看不到内部替换；失败可能仍被去重。Glide 翻译 Fetcher 的取消没有释放 Manager 的订阅。源/NG 的章节 `cancelPreDownloadTask` 还以当前章和下一章已就绪为条件，不能用它替代新协调器无条件的会话退出/后台配额处理。
4. **原图完成判定有限**：hasImageContent 是存在/尺寸/SVG 级检查，非完整字节解码或校验和；迁移需统一相对 URL、源上下文，严格缓存校验也不能被宣传为证明所有图片格式都可显示。
5. **清理存在竞态**：源漫画清理和 PDF 容量清理都没有完整的固定下载/正在读写文件保护。译图 fallback 目录、版本元数据、输入快照也应纳入独立清理，不可只清 pages/text 主目录。
6. **内存不能靠数量保证**：源 Drawable 预热、译图全图 decode/copy、NG 翻页动画帧、PDF bitmap 可能同时存在。数量/并发上限和像素/字节预算必须一起控制。
7. **源未实现的行为不要写成现状**：双页没有按尺寸识别封面/宽页；队列没有抢占已运行后台 OCR；普通图片预取没有已测出的固定提速比例；PDF 改进不等于分块长图渲染。

## 4. 结合 NG 的模块落点与契约

| 所有者 | 负责 | 明确不负责 |
| --- | --- | --- |
| `lib/feature-api` | ReaderSessionKey、SourceKey、原始 PageKey、ImageResourceRef、优先级、订阅/文件租约、结果与错误 | NG Book/Room/Compose/Glide 类型 |
| `lib/manga-reader-support`（JVM） | ChapterWindowPlanner、ImagePrefetchPlanner、SpreadPlanner、ChapterTransition、AnchorPolicy；输出候选/动作 | 网络请求、Bitmap、PDF、NG 单例、真实文件删除 |
| `lib/manga-translation`（Android） | OCR/擦字/绘制、译文/译图缓存、翻译专用队列与配置快照 | 自己解析普通源/插件源、取全局 ReadManga、接管阅读进度 |
| `lib/manga-pdf-support`（可选 Android） | PDF 渲染、两档缓存、内存降级、优先级与文档释放 | 读 NG 数据库、承担网络漫画预取 |
| `app/integration/manga` | 读取 NG epoch 与 UI 快照，执行协调、状态映射、原始页锚点与持久化调用 | 重写 NG 阅读服务、维护第二份进度 |
| `app/integration/image` | 统一资源解析/请求注册、NG 严格缓存写入、Glide targets、缓存读租约、插件图片桥 | 各功能独自重复执行 source/headers/decode |
| `app/integration/platform` | 设置迁移、存储/清理索引、内存回调、日志与 AI 端口 | 把 app 类型带入 lib |

接口草案，均待实现：

```kotlin
data class ReaderSessionKey(
    val bookKey: String, val sourceKey: String,
    val loadEpoch: Long, val resourceRevision: Long
)
data class PageKey(val chapterKey: String, val originalIndex: Int)
data class PrefetchBudget(
    val chapterAhead: Int, val chapterBehind: Int,
    val rawImageCount: Int, val translationCount: Int,
    val maxNetworkJobs: Int, val maxDecodedBytes: Long
)
data class DisplayItem(val key: String, val pages: List<PageKey>) // 1 或 2 张
// 计划是纯数据；宿主将页映射到 ImageResourceRef，并按资源键合并消费者。
interface PrefetchPlanner {
    fun plan(snapshot: ReaderSnapshot, budget: PrefetchBudget): PrefetchPlan
}
interface ImageResourceResolver {
    fun acquire(ref: ImageResourceRef, priority: ResourcePriority): ImageSubscription
}
// ImageSubscription 观察 FileLease/失败；关闭只释放自身持有者。
```

示意类型 `ReaderSnapshot`、`PrefetchPlan`、`ImageSubscription`、`FileLease` 等需在 P1 固定完整契约，不将本片段当可直接编译的实现。

资源身份和任务有效性分开：磁盘资源键使用稳定 book/source/chapter/URL+请求头/解密配置的非敏感指纹与内容版本；epoch 用于回调/写入有效性，不能每次开书就使有效磁盘缓存全部失效。带签名 URL 不擅自删 query 归一化。显示项键由原始 PageKey 组成，翻译文件变更不改阅读页身份。

## 5. 唯一调度链与各层预算

```text
NG 阅读器的可见原始页/边缘/逻辑方向/会话/生命周期
  → app/integration/manga：一个会话协调器
    → reader-support：章节窗口 + 真实图片候选 + 双页/跨章动作
    → app/integration/image：共享资源请求注册表
      ├─ 前台原图：最高优先级，直接显示
      ├─ 视口原图文件：按方向、有界，窗口变化释放订阅
      ├─ 章节原图：低优先级、严格写书籍缓存
      ├─ 已缓存尺寸：仅缓存读取，填 NG 比例 LRU
      ├─ 翻译订阅：原图租约 → 模块页队列 → OCR/译文/译图 → 更新子页
      └─ PDF：转 PdfRenderPort，PREVIEW/HIGH 独立调度
```

共享请求注册表覆盖普通正文图片、章节 writer、Glide 可见加载、视口 fetcher 和翻译输入。NG 书籍图片缓存是规范持久缓存，Glide disk cache 是可丢弃的派生缓存：先查验书籍缓存；命中即交本地文件给 Glide，翻译直接取得租约并可启动 OCR；缺失时任一入口都向同一 resolver 订阅，由唯一网络任务下载、解密、校验并原子发布书籍缓存，其他订阅者复用同一 FileLease/文件。翻译不等待 Glide 解码；其 OCR/文本队列独立限流，文件租约覆盖快照读取期间。Glide 自定义 ModelLoader/Fetcher 作为适配器传递源身份、请求头、优先级和取消，不再直接另发 OkHttp 请求。不能仅“都调用 Glide”却仍让 `saveImageForCache` 独立下载一遍，也不能把 Glide `preload()` 命中误记为书籍缓存已填充。

优先级为可见页原图/当前 PDF > 可见页翻译与近邻原图 > 远章/PDF预览/后台翻译；各执行器保留前台名额，后台工作可提升。任务已在 CPU/原生库运行时采取协作取消和丢弃过期结果，不承诺硬抢占。

| 预算 | 初期接法 | 零值/上限与配置迁移 |
| --- | --- | --- |
| 章节正文窗口 | 当前±1保留，远章单独配置 | 新 chapterAhead 初次可取 NG 原 preDownloadNum，并记录迁移版本；用户后来调整互不联动 |
| 章节原图窗口 | 独立开关及章数，默认值需上线前结合流量样例确定 | 0 停后台原图；前台读取继续；不把用户图片数直接复制成章数 |
| 视口原图文件 | 继续读 NG mangaPreDownloadNum | 0 无视口网络预取；增加任务/字节硬上限，UI 的 9999 不等于同时 9999 请求 |
| 尺寸元数据 | 保留 ±4缓存读与256比例 LRU | 不额外发反向网络；IO 任务有界，离开会话可取消 |
| 后台翻译 | 独立 translationCount，源默认6、最多12作初始参考 | 新增0语义；并发/像素预算与计费提示单独约束，手动可见页不被禁止 |
| 解码预热 | 首期继续 downloadOnly | 如后续启用，按长宽像素和估计字节预算，优先极少近邻；默认不能变成全图内存缓存 |
| PDF | 单独前后窗口/后台数/像素/磁盘上限 | 源16/6…16/8/2…6MP等为参考基线；低内存时收缩，0不被夹为6 |
| 自动清理 | imageRetainNum 的0仍表示关闭自动清理 | 非零也保护用户固定下载、可见/翻译读租约、在写临时文件；超额可拒绝后台填充，不强删固定下载 |

清理动作使用 generation 快照，执行前再核对当前保留集合。配置切换/换源/目录重排/清缓存必须递增对应版本，取消并等待旧发布任务，释放旧订阅，再生成计划；内容可合法跨会话复用时由稳定缓存键判断。

## 6. NG 的实际改动位置

| 宿主位置 | 最小接入职责 | 不可破坏的现有约束 |
| --- | --- | --- |
| ReadManga.kt loadContent/downloadIndex/download/contentLoadFinish | 内容完成事件、原图补齐、完成状态分离；新增图片任务参与 pauseForTocReorder/unregister | loadEpoch、cancel+join、异常取消、相邻章复用、ReadingRecordTracker |
| BookHelp.kt + BookImageCache.kt | 严格原图端口、统一 URL/缓存键；委托清理策略与资源保护 | withChapterCachePublication、临时写入校验；常规下载路径行为 |
| BookCover.kt + OkHttpModelLoader/OkHttpStreamFetcher | 接入共享资源句柄/专用模型、优先级；保留本地已解密文件优先 | 原头信息、Cookie、sourceOrigin、解密、Wi-Fi限制、取消；仅命中特定漫画请求 |
| MangaReaderState.kt | 会话、DisplayItem、原始 PageKey、左右子页状态；独立预取设置 | active/modal/autoRunning、turn/jump revision、现有风格持久化 |
| MangaImages.kt | Lazy 可见边缘上报、双页布局、统一预取计划执行、延迟反馈 | 比例 LRU、jump 完成门控、方向流、清理 targets、长图/缩放 |
| MangaPagedImages.kt + MangaPageTurn.kt | 双页绘制与独立子页状态、原始锚点映射；触发一次预取 | from/to、同列表实例、jumpRevision提交校验、手势/动画帧释放 |
| ReadMangaActivity.kt | upContent映射、onPageVisible推进、skipToPage索引、失败恢复 | 不能保留“列表只有单页”的强制转换或原 binarySearch；不重复提交进度 |
| ReadMangaViewModel.kt | 桥的创建/订阅/关闭、配置与可见页动作协调 | 一个阅读会话，后台结果不能读当前全局书籍再误绑定 |
| MangaReaderScreen.kt | NG 风格的双页/翻译/独立预取设置、Partial与重试入口 | 原菜单、主题、触摸与自动阅读暂停规则 |
| PdfFile.kt / ImageProvider.kt / FeatureBootstrap | PDF委托、文档句柄、缓存文件与平台内存回调 | EPUB/ZIP等本地读链不整体替换；不在lib读取NG单例 |

这些 hook 均加入 `lib/UPSTREAM_HOOKS.md`（待实施）。业务策略留在 lib；宿主内部类型变化只修改桥与少量调用点，降低上游冲突，不能承诺后续零冲突。

## 7. 实施顺序与验收清单

| 批次 | 对应条目 | 交付与关键验收 |
| --- | --- | --- |
| P4-A 资源与章节 | C01–C14、G01–G05、I07/I13 | session/key/lease、严格缓存、章节原图窗口；正文有但图缺失能补；同图三个消费者只发一个请求；换源/重排不旧写 |
| P4-B 视口与观看 | I01–I12、R01–R11 | 保留NG文件预取/比例缓存；跨章、双页、原始页锚点；零后台请求、快速反向、长图边缘、未加载上一章末页、重复点击 |
| P4-C 翻译 | T01–T13 | 原图先显示、队列/订阅、部分补译与缓存；前台不被后台堵塞，单页退订不取消其他消费者，配置变化不串图 |
| P4-PDF PDF | P01–P12 | 独立模块、预览升高清、OOM降级/释放、窗口/容量清理；普通网络漫画不依赖PDF模块 |
| P4-D 收口 | G06与全部回归 | 内存/流量/首图/掉帧/缓存结果、上游hook演练，记录真实验证范围 |

固定测试/观察场景：

| 方面 | 必须覆盖 | 合格判断 |
| --- | --- | --- |
| 窗口/预算（JVM） | 0/1/N、首末章、空章、反向、跳百页、双页展开、相同URL不同源 | 有界候选、正确逻辑方向、不错误共享、无后台零值请求 |
| 请求所有权（可控服务器） | 显示/章节/翻译同时读一图；一个离页；失败再重试；取消发生在下载/解密/发布前后 | 按资源网络计数，其他消费者仍可完成；无半文件、旧回调或泄漏 |
| 缓存（本地固定文件） | 正文命中缺图、坏图/0字节/SVG、相对URL、解密图、缓存清理读写竞争 | 图片状态独立、非图片不标成功、不二次解密，不删用户原文件 |
| 跨章/进度（策略+Compose+ReadManga） | 长图底部未到边、边界页仍部分可见、RTL、WAITING重复输入、慢加载/失败、上一章末张未就绪、空分卷链、快速反向/惯性滚动 | 每次边界只推进一次；只在完全到边后切章；目标首/末原始页定位正确；失败/取消不覆盖旧进度、不连续跳章 |
| 双页（策略+Compose） | 奇偶、RTL、跨章、标题、切风格/旋转/进度滑块、左右独立失败/译图 | 不跨章拼接，位置可往返；翻页版本校验继续成立 |
| 会话/升级 | 同书换源、切书、目录重排、退出重进、备份恢复、上游更新 | 新旧任务隔离、正确URL发布、单一阅读记录；原设置迁移一次 |
| 翻译 | 模拟OCR/提供方慢响应、限流、部分译文、同文复用、强制补译、清缓存 | 原图始终可读，公平/配额有效，版本失效正确，临时快照被释放 |
| PDF | 多档、快速翻页、退出/换文档、文件变化、失败冷却、低内存、容量超限 | 不向新页回调旧图，文档关闭有序，后台收缩，固定/在读缓存受保护 |
| 性能（设备） | 冷/热缓存，连续/分页×横竖×RTL/LTR，长图+翻译+PDF/动画分别测 | 报告首图等待、P95帧耗时/长帧、内存峰值、实际网络请求/字节、预取命中/浪费与取消完成时间；不预设未测提速数字 |

复用 NG 的 `MangaPageTurnTest`、`MangaReaderContractTest`、`MangaImageRatioCacheTest`、`MangaReadingStyleTest`，以及章节缓存/重排测试；新增断言聚焦上述缺口。文档本次只做静态核对和引用检查，没有执行这些未来模块测试、APK构建、设备性能或真实漫画源验证。
