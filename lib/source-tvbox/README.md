# TVBox

配置、CMS、Spider 调用、解析及会话代理归本模块；NG 实体和界面不进入模块。
参考来源 `339e9af` 的 `help/tvbox`、`model/tvbox` 和 `com/github/catvod`。
配置和缓存按配置命名空间隔离；代理只监听 loopback，随机端口不进入配置备份。

每个后端的本地样例和设备验收分开记录；CMS 成功不能证明第三方 JAR/JS 全兼容。

配置最大 8 MiB，命名空间包含在源/作品协议中；分类和扩展筛选使用请求独立会话。启动只读本地数据，损坏配置保留并在管理页报告；移除配置停用虚拟源，不删除书架。

JAR 使用内容 SHA-256 身份、只读 DexClassLoader 文件和可选 MD5；当前 catvod ABI 是明确的子集，并不覆盖任意第三方包。JSON/网页解析、本地代理支持 loopback 会话路由和文件 Range。

JS 在 Android 23+ 使用已有 quickjs-kt 1.0.4 的独立进程常驻会话，21–22 为同步 Rhino；带宿主网络/配置桥、ESM 转换、内存/栈限制和超时看门狗。取消原生死循环会结束整个 JS 工作进程，其他 JS 会话需重建；`setInterval` 未支持。原生 Promise、绑定、进程死亡恢复和实际 Spider 仍须设备验收。

验证命令：`:lib:source-tvbox:testDebugUnitTest`。固定样例覆盖配置身份、CMS 查询/headers、同步 JS 状态与 Java 隔离、分类扩展筛选和代理范围/失效。实际结果见实施记录。
