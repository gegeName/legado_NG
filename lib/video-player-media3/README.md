# Media3 video pipeline

从 Legado 339e9af 抽取 VideoEnhanceHelper 的 GLSL 效果，适配目标统一 Media3 版本。提供字幕/外部音轨媒体合并、按请求隔离 headers 和六档效果。NG 保留现有播放器实例、音频焦点、窗口/悬浮、进度和完成事件。

包含 HLS/DASH/RTSP/SmoothStreaming/RTMP 数据源依赖；编译不能证明所有服务器格式、编码或设备 GLES 可用。CAS/动漫/暗部/Lanczos 是模式名称，不构成质量或性能保证。效果初始化失败应降级到原画，实际 GL 运行错误由宿主播放错误通道报告。
