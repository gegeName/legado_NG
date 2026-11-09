package io.legado.app.integration.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.features.adapter.Media3Features

@Composable fun NgVideoCaptions(modifier: Modifier) {
    AndroidView(factory = Media3Features::captionView, modifier = modifier)
}
