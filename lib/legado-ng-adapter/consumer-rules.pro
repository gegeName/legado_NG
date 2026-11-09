# Implementation-specific consumer rules are owned by feature modules.
# These neutral DTOs are persisted by the host's Gson bridge.
-keep class io.legado.features.api.source.CatalogItem { *; }
-keep class io.legado.features.api.source.ChapterDescriptor { *; }
-keep class io.legado.features.api.source.VideoResource { *; }
-keep class io.legado.features.api.source.MediaTrack { *; }
-keep class io.legado.features.api.host.FeatureBackupEntry { *; }
-keep class io.legado.features.api.host.PortablePreference { *; }
