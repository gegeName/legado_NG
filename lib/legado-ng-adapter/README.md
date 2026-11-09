# legado-ng-adapter

面向宿主的公共 façade，持有具体功能模块，app 只依赖本模块与 feature-api。未标记的源返回默认无操作兼容策略；只有显式导入并保留版本标记的异次元源启用兼容。

扩展装配需要 Android，采用 minSdk 21 的 Android Library。ExtensionFeatureHost 提供 Application/网络/WebView/配置；对外仅返回中立源/管理 DTO，不导入 app 实体、资源或数据库。源执行与共享图片资源的订阅、优先级、租约策略归模块；实际网络/解密/文件发布经端口执行。

验证：`:lib:legado-ng-adapter:testDebugUnitTest`。
