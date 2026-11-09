# source-yiciyuan

来源：`legado` 的 `help/storage/YiciyuanSourceConverter.kt`（339e9af 与实施时工作区）。保留字段/规则/请求头转换、H/S/T/R 规则解密、JS 结果归一化和图片正文格式化；Android Base64、NG 实体、全局执行器和网络调用改为中立数据/宿主端口。

输出版本化 Legado JSON interchange，由宿主映射为自己的实体。兼容标记保存在可导出的 comment 中，避免增加 NG Room 字段。普通 JSON 不进入转换；已识别源错误必须显式报错。压缩输入限制解压尺寸，列表逐条处理。

验证：`:lib:source-yiciyuan:test`。本地夹具覆盖与尚未验证的真实源范围见迁移状态文档。没有把旧 AnalyzeRule/AnalyzeUrl 搬进模块。
