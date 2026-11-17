package io.legado.app.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.integration.video.NgVideoFeatureSettings
import io.legado.app.integration.video.NgVideoPreload
import io.legado.app.ui.design.components.NgDialogVariant
import io.legado.app.ui.design.components.compose.NgDialog
import io.legado.app.ui.design.components.compose.NgDialogTextActionButton
import io.legado.app.ui.design.theme.NgTheme

@Composable
internal fun VideoSettings(onDismiss: () -> Unit) {
    var auto by remember { mutableStateOf(VideoPlay.autoPlay) }
    var full by remember { mutableStateOf(VideoPlay.startFull) }
    var progress by remember { mutableStateOf(VideoPlay.fullBottomProgressBar) }
    var speed by remember { mutableIntStateOf(VideoPlay.longPressSpeed) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var decoderFallback by remember { mutableStateOf(VideoPlay.decoderFallback) }
    var preopen by remember { mutableStateOf(NgVideoPreload.enabled) }
    val scrollState = rememberScrollState()
    if (picker) {
        Dialog(onDismissRequest = { picker = false }) {
            VideoHoldSpeedPicker(speed, { picker = false }) {
                speed = it; VideoPlay.longPressSpeed = it; picker = false
            }
        }
    } else {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            VideoSettingsPanel(onDismiss, scrollState) {
                NgVideoFeatureSettings {
                    VideoSettingsDivider()
                    VideoSettingsSwitchRow("预加载下一集", "提前解析并预开，减少切集等待", preopen) {
                        preopen = it; NgVideoPreload.enabled = it
                    }
                    VideoSettingsDivider()
                    VideoSettingsSwitchRow("解码器回退", "解码失败时尝试其他解码器，下次加载生效", decoderFallback) {
                        decoderFallback = it; VideoPlay.decoderFallback = it
                    }
                }
                VideoSettingsSection("播放与手势") {
                    VideoSettingsSwitchRow(stringResource(R.string.auto_play), checked = auto) { auto = it; VideoPlay.autoPlay = it }
                    VideoSettingsDivider()
                    VideoSettingsSwitchRow(stringResource(R.string.start_full), "开启自动播放后生效", full, enabled = auto) {
                        full = it; VideoPlay.startFull = it
                    }
                    VideoSettingsDivider()
                    VideoSettingsSwitchRow("底部进度条", "全屏时显示播放进度", progress) { progress = it; VideoPlay.fullBottomProgressBar = it }
                    VideoSettingsDivider()
                    VideoSettingsValueRow(stringResource(R.string.press_speed), "${speed / 10f}×") { picker = true }
                }
            }
        }
    }
}
@Composable
private fun VideoHoldSpeedPicker(speed: Int, onCancel: () -> Unit, onConfirm: (Int) -> Unit) {
    val list = rememberLazyListState((speed - 5).coerceIn(0, 55))
    val selected by remember { derivedStateOf { (list.firstVisibleItemIndex + 5).coerceIn(5, 60) } }
    var input by rememberSaveable { mutableStateOf("${speed / 10f}") }
    var previousSelection by rememberSaveable { mutableIntStateOf(selected) }
    val focus = LocalFocusManager.current
    LaunchedEffect(selected) {
        if (previousSelection != selected) input = "${selected / 10f}"
        previousSelection = selected
    }
    val entered = input.toFloatOrNull()?.takeIf { it.isFinite() && it in .5f..6f }?.times(10)?.roundToInt()
    NgDialog(stringResource(R.string.press_speed), variant = NgDialogVariant.CLASSIC_CONFIRMATION,
        titleFontSize = 18.sp, titleFontWeight = FontWeight.Medium, actions = {
        NgDialogTextActionButton(stringResource(R.string.btn_default_s), {
            focus.clearFocus(); onConfirm(30)
        })
        NgDialogTextActionButton(stringResource(R.string.cancel), { focus.clearFocus(); onCancel() })
        NgDialogTextActionButton(stringResource(R.string.ok), {
            entered?.let { focus.clearFocus(); onConfirm(it) }
        }, enabled = entered != null)
    }) {
        // The dialog measures actions before this flexible viewport, including with the IME open.
        BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 144.dp)) {
            val wheelHeight = this.maxHeight
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(wheelHeight),
                contentPadding = PaddingValues(vertical = ((wheelHeight - 48.dp) / 2).coerceAtLeast(0.dp)),
                flingBehavior = rememberSnapFlingBehavior(list), horizontalAlignment = Alignment.CenterHorizontally) {
                items(56, key = { it + 5 }) { index ->
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text("${(index + 5) / 10f}", color = if (selected == index + 5) Color.Transparent else Color(NgTheme.colors.onSurfaceVariant), fontSize = 20.sp)
                    }
                }
            }
            val inputLabel = stringResource(R.string.press_speed)
            BasicTextField(input, { input = it }, Modifier.align(Alignment.Center).width(80.dp).heightIn(min = 48.dp)
                .semantics { contentDescription = inputLabel },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                cursorBrush = SolidColor(Color(NgTheme.colors.primary)),
                decorationBox = { field -> Box(contentAlignment = Alignment.Center) { field() } },
                textStyle = TextStyle(color = Color(NgTheme.colors.primary), fontSize = 20.sp, textAlign = TextAlign.Center))
        }
    }
}
