# PDF reader support

来源：Legado 339e9af 的 PdfFile/PdfRenderManager。独立文档版本、预览/高清、单渲染队列、订阅提升和文件租约；Android PdfRenderer 由模块拥有，宿主只提供文件描述符。普通漫画不调用 PDF 能力。

参考限制：1MP 预览，按堆上限 2/4/6MP 高清、最大边 4096；OOM 三次减半、三次失败/30 秒冷却；独立窗口、每书后台最多八任务；窗口和容量清理保护读取者。

本地策略/队列夹具不能代替 Android 原生 PDF、内存峰值及预览升级的设备验证。

PDF 渲染和 OCR/译图合成通过 feature-api 的进程内 ImageMemoryBudget 共同申请估算内存额度；原生渲染期间不会因订阅取消强行回收 Bitmap。预算不统计 Glide/其他原生分配，不能当作实际堆峰值保证。

使用 `-PlegadoPdf=false` 可从编译依赖中移除本模块。adapter 提供缺省装配，宿主退回 NG 原有 PDF 读取，优化设置入口隐藏。
