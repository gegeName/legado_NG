package io.legado.app.integration.manga

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.design.components.compose.*
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.widget.dialog.applyNgWindow
import io.legado.features.api.manga.TranslationConfiguration

object NgMangaTranslationDialog {
    fun show(context: Context, onSaved: () -> Unit): ComponentDialog {
        val dialog = ComponentDialog(context)
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NgAppTheme(updateSystemBars = false) {
                    val initial = remember { NgMangaTranslationBridge.configuration }
                    var enabled by remember { mutableStateOf(initial.enabled) }
                    var settings by remember { mutableStateOf(initial.settings) }
                    var preloadCountText by remember { mutableStateOf(initial.settings.preloadCount.toString()) }
                    var error by remember { mutableStateOf("") }
                    NgDialog(title = "漫画翻译", actions = {
                        NgDialogTextActionButton(text = "取消", onClick = dialog::dismiss)
                        NgDialogTextActionButton(text = "保存", onClick = {
                            val preloadCount = preloadCountText.toIntOrNull()?.takeIf { it in 0..24 }
                            if (preloadCount == null) {
                                error = "后台预译图片数需为 0 到 24"
                            } else try {
                                NgMangaTranslationBridge.configuration = TranslationConfiguration(
                                    enabled, settings.copy(preloadCount = preloadCount),
                                )
                                onSaved(); dialog.dismiss()
                            } catch (e: Exception) { error = e.message.orEmpty() }
                        })
                    }) {
                        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                            NgFormSwitchRow("显示漫画译图", enabled, { enabled = it })
                            NgFormSelectField(label = "文字翻译服务", selectedValue = settings.textEngine,
                                options = listOf("自动" to "auto", "必应" to "bing", "Google" to "google", "当前 AI 服务" to "ai", "DeepL" to "deepl", "百度" to "baidu", "LibreTranslate" to "libre").map { NgFormSelectOption(it.first, it.second) },
                                onValueChange = { settings = settings.copy(textEngine = it) }, arrowIcon = painterResource(R.drawable.ic_ng_spinner_arrow_down))
                            NgFormSelectField(label = "原文语言", selectedValue = settings.sourceLanguage,
                                options = listOf("自动" to "auto", "日文" to "ja", "英文 / 拉丁文" to "en", "韩文" to "ko").map { NgFormSelectOption(it.first, it.second) },
                                onValueChange = { settings = settings.copy(sourceLanguage = it) }, arrowIcon = painterResource(R.drawable.ic_ng_spinner_arrow_down))
                            NgFormField("目标语言", settings.targetLanguage, { settings = settings.copy(targetLanguage = it.trim()) })
                            NgFormField("后台预译图片数（默认4，0关闭）", preloadCountText, { value ->
                                preloadCountText = value
                                value.toIntOrNull()?.takeIf { it in 0..24 }?.let { settings = settings.copy(preloadCount = it) }
                            }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            when (settings.textEngine) {
                                "deepl" -> {
                                    NgFormField("DeepL 密钥", settings.providers.deeplKey, { settings = settings.copy(providers = settings.providers.copy(deeplKey = it.trim())) }, visualTransformation = PasswordVisualTransformation())
                                    NgFormField("DeepL 接口", settings.providers.deeplEndpoint, { settings = settings.copy(providers = settings.providers.copy(deeplEndpoint = it.trim())) })
                                }
                                "baidu" -> {
                                    NgFormField("百度 APP ID", settings.providers.baiduAppId, { settings = settings.copy(providers = settings.providers.copy(baiduAppId = it.trim())) })
                                    NgFormField("百度密钥", settings.providers.baiduSecret, { settings = settings.copy(providers = settings.providers.copy(baiduSecret = it.trim())) }, visualTransformation = PasswordVisualTransformation())
                                }
                                "libre" -> {
                                    NgFormField("LibreTranslate 接口", settings.providers.libreEndpoint, { settings = settings.copy(providers = settings.providers.copy(libreEndpoint = it.trim())) })
                                    NgFormField("LibreTranslate 密钥", settings.providers.libreKey, { settings = settings.copy(providers = settings.providers.copy(libreKey = it.trim())) }, visualTransformation = PasswordVisualTransformation())
                                }
                            }
                            if (error.isNotBlank()) Text(error)
                            NgDialogTextActionButton(text = "清理翻译缓存", onClick = { NgMangaTranslationBridge.clear(); onSaved() })
                        }
                    }
                }
            }
        }
        dialog.setContentView(view)
        dialog.setOnShowListener { dialog.applyNgWindow() }
        dialog.show()
        return dialog
    }
}
