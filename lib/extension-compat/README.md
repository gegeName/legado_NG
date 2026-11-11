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

普通 client 与 cloudflareClient 均检测 `cf-mitigated: challenge`。后台 WebView 自动
验证失败或要求交互时，通过 ExtensionPlatform.verifyChallenge 打开宿主可见验证页；
复用原请求 User-Agent 和 WebView CookieManager，完成后只重试一次原请求。
取消请求会结束等待；可见验证最多等待三分钟，关闭页面或验证后仍受挑战则返回失败。

扩展请求携带独立 Cookie 上下文，普通 OkHttp 沿用原生 CookieJar；宿主 Cronet
在每次发出请求（包括重定向）前调用 adapter 的 ExtensionNetwork 端口，读取共享
WebView Jar，并把重定向/最终响应的 Cookie 写回。只对标记的扩展请求生效；
不让 NG 的 CookieJar 标记触发另一套旧 Cookie 合并，也不跨来源转发手工 Cookie。

扩展默认 UA 来自宿主设备的 WebView（移除 `wv` / `Version/4.0` 标记），验证页与
扩展请求保持同一身份，避免继承 NG 桌面 Cronet UA 的过期 Chrome 版本。扩展显式
设置的 UA 仍然保留。挑战/重试日志只记录状态码、协议和凭据是否携带/一致，不输出
Cookie 值；普通 OkHttp 的日志从网络拦截器观察实际请求，避免 Bridge 回填原请求后
误报“未携带 Cookie”。
