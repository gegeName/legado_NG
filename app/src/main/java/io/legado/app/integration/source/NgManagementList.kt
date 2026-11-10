package io.legado.app.integration.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.design.components.compose.NgSwitchControl
import io.legado.app.ui.design.components.compose.NgSwitchControlVariant
import io.legado.app.ui.design.components.compose.NgGlassDefaults
import io.legado.app.ui.design.components.compose.NgGlassSurface
import io.legado.app.ui.design.components.compose.NgMaterialRole
import io.legado.app.ui.design.theme.NgTheme

@Composable
internal fun NgRepositoryCard(
    title: String,
    address: String,
    status: String,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    NgGlassSurface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        role = NgMaterialRole.CONTENT,
        style = NgGlassDefaults.bookDetailStyle(),
        shape = RoundedCornerShape(NgTheme.shapes.largeDp.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_cfg_source), null, Modifier.size(24.dp), tint = Color(NgTheme.colors.primary))
                Text(title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(address, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(NgTheme.colors.onSurfaceVariant), style = MaterialTheme.typography.bodySmall)
            Text(status, color = Color(NgTheme.colors.onSurfaceVariant), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
            content()
        }
    }
}

@Composable
internal fun NgSourceListItem(
    name: String,
    detail: String,
    enabled: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = Color(NgTheme.colors.onSurfaceVariant), style = MaterialTheme.typography.bodySmall)
            }
            NgSwitchControl(checked = enabled, onCheckedChange = { onToggle() }, enabled = !busy, variant = NgSwitchControlVariant.COMPACT)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        HorizontalDivider(color = Color(NgTheme.colors.outlineVariant))
    }
}
