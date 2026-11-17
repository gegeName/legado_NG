package io.legado.app.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.design.components.compose.NgDialogSurface
import io.legado.app.ui.design.components.compose.NgFormSelectOption
import io.legado.app.ui.design.components.compose.NgSwitchControl
import io.legado.app.ui.design.components.compose.NgSwitchControlVariant
import io.legado.app.ui.design.theme.NgTheme

/** The header stays reachable while only the settings body scrolls. */
@Composable
internal fun VideoSettingsPanel(
    onDismiss: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val availableHeight = maxHeight
        NgDialogSurface(Modifier.widthIn(max = 480.dp).heightIn(max = availableHeight)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("视频设置", color = Color(NgTheme.colors.onSurface), fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        Text("按你的习惯调整播放体验", color = Color(NgTheme.colors.onSurfaceVariant),
                            fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_baseline_close), stringResource(R.string.close),
                            Modifier.size(22.dp), tint = Color(NgTheme.colors.onSurfaceVariant))
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(scrollState)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun VideoSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() },
            color = Color(NgTheme.colors.secondary), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(NgTheme.shapes.mediumDp.dp))
                .background(Color(NgTheme.colors.surfaceContainerLow)),
            content = content,
        )
    }
}

@Composable
internal fun VideoSettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Color(NgTheme.colors.outline).copy(alpha = .3f))
}

@Composable
private fun VideoSettingsLabel(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color(NgTheme.colors.onSurface), fontSize = 15.sp, lineHeight = 21.sp)
        if (!summary.isNullOrBlank()) {
            Text(summary, color = Color(NgTheme.colors.onSurfaceVariant), fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun VideoSettingsSwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else .45f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoSettingsLabel(title, summary, Modifier.weight(1f))
        // The entire row owns the toggle semantics and touch target.
        NgSwitchControl(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            modifier = Modifier.clearAndSetSemantics {},
            variant = NgSwitchControlVariant.COMPACT)
    }
}

@Composable
internal fun VideoSettingsValueRow(title: String, value: String, summary: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = value }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            VideoSettingsLabel(title, summary)
            Text(value, color = Color(NgTheme.colors.secondary), fontSize = 13.sp, lineHeight = 19.sp)
        }
        Icon(painterResource(R.drawable.ic_chevron_right_20), null, Modifier.size(20.dp),
            tint = Color(NgTheme.colors.onSurfaceVariant))
    }
}

@Composable
internal fun VideoSettingsSelectRow(
    title: String,
    selectedValue: String,
    options: List<NgFormSelectOption>,
    summary: String? = null,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val label = options.firstOrNull { it.value == selectedValue }?.label ?: selectedValue
    Box(Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        VideoSettingsValueRow(title, label, summary) { expanded = options.isNotEmpty() }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(density) { widthPx.toDp() }).heightIn(max = 360.dp),
            shape = RoundedCornerShape(NgTheme.shapes.mediumDp.dp),
            containerColor = Color(NgTheme.colors.dialogContainer).copy(alpha = 1f),
            tonalElevation = 0.dp,
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = isSelected },
                    text = {
                        Text(option.label, color = Color(if (isSelected) NgTheme.colors.secondary else NgTheme.colors.onSurface),
                            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)
                    },
                    trailingIcon = {
                        if (isSelected) Text("✓", color = Color(NgTheme.colors.secondary))
                    },
                    onClick = {
                        expanded = false
                        if (!isSelected) onValueChange(option.value)
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
