# Aniyomi source adapter

Maps anime catalogs, episode updates, hosters and stream variants to neutral source/video contracts. Shared ABI and installation live in extension-compat/source-runtime. Per-request filters and per-anime update locks isolate concurrent work.

Preserves stream headers, subtitle/audio tracks and preferred quality. Unsupported local-server/MPV/FFmpeg-only requests fail explicitly instead of forwarding a dead URL to NG. No arbitrary extension APK/site or device playback compatibility is asserted by fixture tests.
