package io.legado.app.integration.tts

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.legado.app.ui.design.components.NgDialogVariant
import io.legado.app.ui.design.components.compose.NgDialog
import io.legado.app.ui.design.components.compose.NgDialogTextActionButton
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.widget.dialog.applyNgWindow
import io.legado.features.api.tts.MultiTtsImportResult

/** Host-only preview. No config changes occur until the existing importer receives the draft. */
object NgMultiTtsImportDialog {
    fun show(context: Context, preview: MultiTtsImportResult, onImport: (mergeExisting: Boolean) -> Unit): ComponentDialog {
        val dialog = ComponentDialog(context)
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                NgAppTheme(updateSystemBars = false) {
                    NgDialog(
                        title = "MultiTTS 在线音色导入预览",
                        variant = NgDialogVariant.STANDARD,
                        actions = {
                            NgDialogTextActionButton(text = "取消", onClick = dialog::dismiss)
                            if (preview.engines.isNotEmpty()) {
                                NgDialogTextActionButton(text = "作为独立目录导入", onClick = { dialog.dismiss(); onImport(false) })
                                NgDialogTextActionButton(text = "合并更新目录", onClick = { dialog.dismiss(); onImport(true) })
                            }
                        },
                    ) {
                        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                            Text("导入后可在现有朗读设置中选择。导入不会试听或切换当前引擎。")
                            preview.engines.forEach { engine ->
                                Text("${engine.name}（${engine.provider}）：${engine.voices.size} 条音色")
                                engine.diagnostics.forEach { Text("${it.location}：${it.message}") }
                            }
                            preview.diagnostics.forEach { Text("${it.location}：${it.message}") }
                        }
                    }
                }
            }
        }
        dialog.setContentView(view)
        dialog.show()
        dialog.applyNgWindow()
        return dialog
    }
}
