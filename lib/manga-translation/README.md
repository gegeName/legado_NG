# Manga translation

来源：Legado `339e9af` 的 `model/manga/translation`。OCR、公共翻译、擦字回填和任务调度在本模块；图片从宿主共享原图租约读取，AI 通过已有宿主服务端口调用。

公共入口使用 feature-api 中立请求和不可变配置；不依赖阅读器、NG 实体、Glide 或全局 AI 配置。默认本地 OCR 包含日文、拉丁文、韩文；AI 识图仅在宿主声明支持图片时可用。

模块正在接入和验证；实际 ML Kit 识别、外部翻译提供方和 Android 绘制效果另验，不以本地策略测试代替。
