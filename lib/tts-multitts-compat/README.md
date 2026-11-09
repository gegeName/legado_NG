# MultiTTS 在线音色 → SCRIPT

纯 JVM 模块，读取根目录 engines.yaml / config.yaml，不提取头像或执行 YAML 类型。
输出 feature-api 中的预览 DTO、稳定身份与可导出的本地 voices() 目录，三类模板
XFPEIYIN、HUOSHAN、DIRECT_HTTP 各自构造 NG 现有 SCRIPT 请求。不含合成执行器、
离线库、播放或数据库，不依赖外部 MultiTTS。

ZIP 压缩 32 MiB、解压总量 64 MiB、单 YAML 2 MiB、其他条目 8 MiB、1024 条目；
YAML 嵌套 32，禁止集合别名、重复键及未知类型。取消检查由宿主注入。
未知引擎/参数逐项诊断，同名音色保留。更新默认按稳定 ID 合并，目录及协议哈希进入
脚本以更新现有音频缓存身份。命名空间默认 default，独立导入可由宿主指定新的命名空间。

协议模板生成和离线请求测试不能证明在线服务、全部音色或 Android 播放可用。
HUOSHAN 风格元数据不宣称能控制服务端；DIRECT_HTTP 只支持固定 GET 占位符，
不执行模板表达式，拒绝本地转发地址。头像仅作为原始引用元数据保留。
