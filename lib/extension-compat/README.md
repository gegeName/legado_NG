# Shared extension ABI

从源提交 339e9af 的 eu.kanade.tachiyomi 抽取 64 个漫画/视频/网络兼容文件，保留
插件查找的包名、构造器和方法。NetworkHelper、AppInfo 与挑战 WebView 的旧宿主
引用改走 ExtensionPlatform；模块不引用 NG 类或源目录。

这里只定义兼容 ABI 和网络/偏好外观，不安装扩展，不拥有仓库与业务源目录。
JavaScriptEngine 沿原实现明确报告未接入，不能据此宣称支持所有扩展 JS API。
源提交本身包含特定 suspend 扩展接口；本地夹具测试不代表任意 Mihon/Ansiomi APK
与其二进制兼容。真实 APK、Manifest 合并、重复类及混淆验证单独记录。

初始化由 runtime 装配 ExtensionPlatform 后注册 Injekt 公共依赖。应用内第三方
类型只能有一份；implementation 不能隔离 APK 中同名类。minSdk 21，Java 17，
OkHttp/Jsoup/AndroidX 跟随 NG 版本；RxJava 1.x/Injekt/serialization 沿源 ABI 版本。
