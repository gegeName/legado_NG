package io.legado.app.integration.source

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.legado.app.base.BaseActivity
import io.legado.app.base.ComposeActivityBinding
import io.legado.app.ui.design.components.compose.NgFloatingTitleToolbar
import io.legado.app.ui.design.components.compose.NgWindowLiquidGlassBackdropHost
import io.legado.app.ui.design.theme.NgAppTheme
import io.legado.app.ui.design.theme.NgTheme

/** Full-page management, with normal Activity back navigation between sections. */
class NgSourceManagementActivity : BaseActivity<ComposeActivityBinding>() {
    private val extensionModel by viewModels<NgExtensionManagerViewModel>()
    override val binding by lazy { ComposeActivityBinding.inflate(layoutInflater) }
    override val bindNgToolbarMenu = false

    companion object {
        private const val SECTION = "section"
        fun open(context: Context, section: String = "extensions") {
            context.startActivity(Intent(context, NgSourceManagementActivity::class.java).putExtra(SECTION, section))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            NgAppTheme {
                when (intent.getStringExtra(SECTION)) {
                    "repositories" -> NgExtensionRepositoriesScreen(::finish)
                    "tvbox" -> NgTvBoxManagerScreen(this, ::finish)
                    else -> NgExtensionManagerScreen(this, extensionModel, ::finish) { section -> open(this, section) }
                }
            }
        }
    }
}

@Composable
internal fun NgSourceManagementPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    NgWindowLiquidGlassBackdropHost(Modifier.fillMaxSize()) {
        // BaseActivity owns the image/gradient/scene behind this Compose page.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            contentColor = Color(NgTheme.colors.onSurface),
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ).imePadding()) {
                NgFloatingTitleToolbar(
                    title = title,
                    onBack = onBack,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    trailingContent = actions,
                )
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), content = content)
            }
        }
    }
}
