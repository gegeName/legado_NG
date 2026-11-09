# Shared extension runtime

源提交 339e9af 的仓库、索引（JSON/protobuf）、签名信任、版本校验、私有扩展安装、
系统包识别/卸载、类加载和安装广播迁入此模块，漫画与视频共享 ExtensionRuntime。
虚拟 NG BookSource 同步由宿主观察源状态后映射，不由运行环境导入 app 实体。
平台网络/WebView 由 extension-compat 的端口提供；配置通过 feature-api ConfigPort，
索引/私有扩展使用应用私有 feature_extensions 目录。

只有实际用户动作才能安装或卸载扩展。启动恢复已安装扩展；未信任、无签名、库版本
不支持和类加载失败均保留诊断状态，不注册成可用源。NG 在不进入管理页时也应完成
惰性注册，以供服务/进程恢复。安装不等于执行来源代码成功；真实 ABI/设备加载另验。

此目录的模型允许兼容层类型供 source-mihon/source-aniyomi 使用；app 只经 adapter
和中立 DTO 操作。Android Manifest 包可见性、后台生命周期及 release 混淆需在产物验证。

本地 APK 与仓库下载共用包名、签名、防降级和同包串行安装校验。源筛选每次请求创建独立会话，持久化使用 provider/类型/源身份；ConfigurableSource 的 AndroidX 偏好转换为中立字段，回调仍由原扩展校验。为兼容扩展，实际偏好文件沿用 `source_<id>`，漫画/视频扩展恰好使用相同 id 时的偏好文件冲突尚未消除。
