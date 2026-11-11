package io.legado.app.integration.source

import android.view.ContextThemeWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.legado.app.R
import io.legado.app.ui.design.theme.*
import io.legado.features.api.source.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = InterfaceFontTestApplication::class)
class NgExtensionCatalogScreenshotTest {
    @Test fun concurrentDownloadRows() = captureCatalog()

    @Test
    fun extensionStatesMapToDistinctTabs() {
        val available = ExtensionSummary("a", "pkg.a", "A", "1", "available")
        assertEquals(NgExtensionStatus.NOT_DOWNLOADED, extensionStatus(available, null))
        assertEquals(
            NgExtensionStatus.DOWNLOADING,
            extensionStatus(available, ExtensionDownload(InstallProgress("downloading"), true)),
        )
        assertEquals(
            NgExtensionStatus.DOWNLOADED,
            extensionStatus(available, ExtensionDownload(InstallProgress("installed"), false)),
        )
        assertEquals(
            NgExtensionStatus.UPDATE_AVAILABLE,
            extensionStatus(available.copy(state = "loaded", updateAvailable = true), null),
        )
        val updateFailure = available.copy(state = "loaded", updateAvailable = true)
        val failedDownload = ExtensionDownload(InstallProgress("error"), false)
        assertEquals(NgExtensionStatus.DOWNLOAD_FAILED, extensionStatus(available, failedDownload))
        assertEquals(NgExtensionStatus.DOWNLOAD_FAILED, extensionStatus(updateFailure, failedDownload))
        assertEquals(
            true,
            extensionMatchesStatus(updateFailure, failedDownload, NgExtensionStatus.DOWNLOAD_FAILED),
        )
        assertEquals(
            true,
            extensionMatchesStatus(updateFailure, failedDownload, NgExtensionStatus.UPDATE_AVAILABLE),
        )
        assertEquals(
            false,
            extensionMatchesStatus(
                updateFailure,
                ExtensionDownload(InstallProgress("installed"), false),
                NgExtensionStatus.UPDATE_AVAILABLE,
            ),
        )
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-night-xxhdpi")
    fun compactDarkDownloadRows() = captureCatalog(dark = true)

    private fun captureCatalog(dark: Boolean = false) {
        captureRoboImage {
            val context = LocalContext.current
            val themed = remember(context) { ContextThemeWrapper(context, if (dark) R.style.AppTheme_Dark else R.style.AppTheme_Light) }
            val snapshot = NgThemeResolver.resolve(NgLegacyThemeInput(
                primaryColor = if (dark) 0xFF202522.toInt() else 0xFFF5F6F4.toInt(), accentColor = 0xFF168778.toInt(),
                backgroundColor = if (dark) 0xFF161A18.toInt() else 0xFFF8F9F7.toInt(),
                bottomBackground = if (dark) 0xFF202522.toInt() else 0xFFF5F6F4.toInt(), errorColor = 0xFFB3261E.toInt(), isDark = dark, isEInk = false,
            ))
            CompositionLocalProvider(LocalContext provides themed) {
                NgAppTheme(snapshot = snapshot, updateSystemBars = false) {
                    val entries = listOf(
                        ExtensionSummary("a", "pkg.a", "A 漫画示例", "1.4.12", "available", language = "zh"),
                        ExtensionSummary("b", "pkg.b", "B 动画示例", "14.8", "available", language = "zh"),
                        ExtensionSummary("c", "pkg.c", "C 已加载的扩展", "1.4.5", "loaded", language = "en"),
                        ExtensionSummary("d", "pkg.d", "D 等待信任的扩展", "1.4.9", "notLoaded", reason = "等待信任签名", fingerprint = "fixture", language = "zh"),
                        ExtensionSummary("e", "pkg.e", "E 下载失败的扩展", "1.4.2", "available", language = "ja"),
                        ExtensionSummary("f", "pkg.f", "F 可用更新", "1.4.1", "loaded", updateAvailable = true, language = "en"),
                    )
                    // A visible backdrop catches accidental opaque page containers.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        if (dark) listOf(Color(0xFF183A45), Color(0xFF26332B), Color(0xFF302A42))
                        else listOf(Color(0xFFBDDFED), Color(0xFFD5E9D4), Color(0xFFE3D6EC)),
                    ))) {
                    NgSourceManagementPage("Mihon / Aniyomi", {}, actions = {
                        IconButton(onClick = {}) { Icon(painterResource(R.drawable.ic_bgm_menu_refresh), "刷新") }
                        IconButton(onClick = {}) { Icon(painterResource(R.drawable.ic_more_vert), "更多") }
                    }) {
                        NgExtensionCatalog(
                            entries = entries,
                            sources = emptyList(),
                            repositories = listOf(ExtensionRepository("fixture", "示例仓库", "fixture")),
                            downloads = mapOf(
                                "pkg.a" to ExtensionDownload(InstallProgress("downloading", 32), true),
                                "pkg.b" to ExtensionDownload(InstallProgress("downloading", 78), true),
                                "pkg.e" to ExtensionDownload(
                                    InstallProgress("error", error = "网络请求失败，请重试"),
                                    false,
                                ),
                            ),
                            refreshing = false,
                            message = "",
                            repository = "",
                            onSelectRepository = {},
                            onManageRepositories = {},
                            onInstall = {},
                            onUpdateAll = {},
                            onCancel = {},
                            onTrust = {},
                            onUninstall = {},
                            sourceBusy = false,
                            onToggleSource = {},
                            checkingWebsitePackage = null,
                            onCheckWebsite = {},
                            initialFilter = NgExtensionStatus.DOWNLOADING,
                        )
                    }
                    }
                }
            }
        }
    }
}
