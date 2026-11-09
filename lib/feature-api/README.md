# feature-api

纯 Kotlin/JVM 中立契约；无 NG、Android UI、Room 或 Rhino 依赖。SourceKey 同时包含 provider、内容类型、原始 ID 和配置命名空间。NotHandled 与已选功能失败分开。

RuleExecutionPort/RuleFetchPort 的调用者负责源作用域与取消。ImageResourceKey 的稳定配置指纹不得包含明文凭据；FileLease 由消费者关闭，load epoch 不作为永久磁盘身份。

验证：`:lib:feature-api:test`。尚未在本批提供图片网络/缓存实现。
