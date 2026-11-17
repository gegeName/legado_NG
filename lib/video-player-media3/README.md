# Media3 video pipeline

从 Legado 339e9af 抽取 VideoEnhanceHelper 的 GLSL 效果，适配目标统一 Media3 版本。提供字幕/外部音轨媒体合并、按请求隔离 headers 和七档效果。NG 保留现有播放器实例、音频焦点、窗口/悬浮、进度和完成事件。

2026-11-17：CAS 改为 AMD 官方 sharpen-only 公式的 GLES 移植；新增 AMD RCAS 锐化，启用上游的噪点锐化抑制。两者保持输入尺寸、保留透明度，HDR 暂时原样透传；组合模式复用同一 CAS 后接暗部提亮。已有配置 id 0–5 不变，RCAS 追加为 6。源码固定提交、MIT 许可、移植差异与其他候选见 [第三方来源](THIRD_PARTY_NOTICES.md)。这两种算法增强已有边缘与局部对比度，不恢复源视频中不存在的细节；未引入完整 FSR 超分或 AI 模型。

本地验证：模块单测、应用 Kotlin 编译、16 个模块边界检查通过；`tools/check_fidelityfx_shaders.py` 使用 Android SDK 的桌面 SwiftShader 执行实际 GLSL，62 项参考像素/边缘/边界/噪点/透传分支检查通过，日志见 `build/video-fidelityfx-check.log`、`build/video-fidelityfx-shader-check.json`。透传检查使用 SDR 范围的像素；未验证真实 HDR 亮度、Android 设备播放、实时帧率、发热或耗电。应用编译仍排除已有失败的 `verifyNgDesignSystem`。

包含 HLS/DASH/RTSP/SmoothStreaming/RTMP 数据源依赖；编译不能证明所有服务器格式、编码或设备 GLES 可用。CAS/动漫/暗部/Lanczos 是模式名称，不构成质量或性能保证。效果初始化失败应降级到原画，实际 GL 运行错误由宿主播放错误通道报告。
