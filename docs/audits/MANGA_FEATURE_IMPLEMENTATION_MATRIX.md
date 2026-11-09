# 漫画模块实现核对矩阵

核对日期：2026-11-09。对应 `LEGADO_FEATURE_MODULE_MIGRATION_PLAN.md` 第 6 节及 `MANGA_READER_PRELOAD_MIGRATION_AUDIT.md`。表中的“本地验证”限于 JVM/Robolectric 或固定文件；不代表设备性能、Android PdfRenderer 负载或长时间阅读验收。

## PDF P01–P12

| 条目 | 当前实现 | 本地验证/限制 |
| --- | --- | --- |
| P01 队列/合并/提级 | `PdfResources` 一个渲染 worker；相同 `PdfPageRequest` 共用 entry；USER_REQUEST > VISIBLE > PREFETCH，排队优先级可提升 | `PdfResourcesTest.sharedRenderingKeepsVisibleSubscriberAfterPreloadCancels` 验证共享渲染；原生并发与线程实测未做 |
| P02 预览后高清 | PRELOAD 窗口只提交 PREVIEW；Compose 先独立请求预览，普通显示请求 HIGH；档位在资源键中隔离 | API/分档代码可编译；尚无设备首屏时间与升级闪烁验证 |
| P03 窗口/后台上限 | PDF 独立 ahead 设置 0..16；窗口最多向前16页并保留向后16页；每次最多登记8个附近预取 | `PdfPoliciesTest` 验证零值、边界和大窗口；没有多书真实滚动基准 |
| P04 窗口取消/书籍代次 | 离窗预取取消；book epoch 在 cancelBook 改变，旧请求不得登记、完成后发布或推进 window/pageCount | `capturedEpochCannotRegisterNewWorkAfterBookCancellation`、`cancellationRejectsLateNativeRenderAndDoesNotPublish`；已开始的 native render 只能丢弃结果，无法立即中断 |
| P05 失败冷却 | 同资源失败3次后冷却30秒；force 清除冷却；取消不记为渲染失败 | `failureCooldownAllowsForceAndExpiredRetry` |
| P06 分辨率/共享估算预算 | 预览最多1MP；高清按 maxHeap 预算2/4/6MP，最长边4096；render 前申请进程级图像预算 | `dimensionsBoundPixelsAndLongPageEdgesAndZeroWindowStops`；预算为估算额度，不统计所有 native/Glide 分配 |
| P07 OOM 降级 | Android backend 最多3次分配/渲染尝试；OOM 后宽高各减半；每轮 finally recycle Bitmap | 当前 JVM fake backend 不触发真实 Android OOM；需设备覆盖 |
| P08 图像编码 | PREVIEW JPEG75；HIGH 根据采样灰度/颜色选择 PNG 或 JPEG85；缓存以内容校验识别，不依赖扩展名 | 编译与策略检查；无 Android 编码质量/耗时比较 |
| P09 原子发布/版本 | 唯一临时文件、sync、校验后 rename；key 含 PDF 文档版本、页、档位、屏宽；旧同页同档版本延迟到 reader lease 释放后删除 | `obsoletePageVariantsAreDeletedAfterTheirReadersRelease` 验证旧文件保留至 lease close；文件系统掉电原子性未验证 |
| P10 窗口清理 | 中心页移动至少3页才触发清理；清理执行前检查 window/center 仍与计划匹配；租约/任务中的文件跳过并延迟删除 | 队列与保护路径有单元测试；高频方向切换的设备 IO 未测 |
| P11 容量清理 | 每8次成功触发；每书300MiB、全局1GiB LRU；在用文件延后处理 | 固定文件队列测试覆盖 lease 与清理；超大真实缓存扫描成本未基准 |
| P12 内存回调/句柄释放 | App 把 trim/low-memory 转发至 PDF façade；普通压力取消预取，critical 取消 book epoch/任务并排队 release；Android backend 对 renderer/descriptor 的操作同步 | `criticalMemoryPressureInvalidatesEpochAndReleasesNativeHandle` 验证 fake backend release；Android lifecycle 未设备验收 |

## 其他漫画闭环

| 项目 | 实现位置 | 已验证内容及边界 |
| --- | --- | --- |
| 原图统一缓存/请求 | `SharedImageResources`、`NgOriginalImageResources`、BookHelp/Glide hooks | 3 总并发/1 后台、独立订阅、优先级、租约、防迟到写、源/配置/Cookie 指纹；本地集成和缓存夹具，不是实际网络性能测试 |
| 正文与图片完成状态 | `ChapterCache.kt`、`ReadManga` | 漫画正文含图不代表图片落盘；下载完成另行校验实际文件；非漫画文本不要求图片 |
| 视口/章节窗口 | `ChapterImageWindow`、`ImagePrefetchPlanner`、MangaImages hooks | 24 张网络候选预算；0 关闭网络预取但保留元数据/原图读取；规则测试通过 |
| 双页/进度 | `SpreadPlanner`、NgMangaSpread | 横向启用、同章配对、RTL 物理顺序映射，进度仍保存原始 MangaPage；既有分页/比例/样式回归通过 |
| 跨章导航 | `ChapterNavigation`、ReadManga hook | pending target、实际 imageCount 后提交、返回上一章真实末页、空卷最多跳32、错误/缺章/取消解除等待；无 Compose 设备测试 |
| 自动缓存/固定下载 | `NgOriginalImageResources.pin/discardAutomatic` | 自动清理尊重显式 pin，并保护活跃 reader lease；Android 文件操作仍需压力测试 |
| PDF 缺省构建 | adapter 的 `pdfDisabled` façade、`PdfFile` fallback | `-PlegadoPdf=false -PlegadoVideoEffects=false` 的应用 Kotlin 编译及边界/视觉/中间产物检查通过，见 `build/feature-minimal-check.log`；保留 NG 原 PDF reader |

## 尚未实测

单元测试不覆盖真实手机内存压力、低端 GPU、长时间双页阅读、网络断开/恢复、超大 PDF 首图延迟、电量、缓存目录增长曲线或系统进程回收。当前不提供提速百分比、硬性内存峰值或所有本地 PDF 格式兼容承诺。
