package io.legado.app.integration

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.integration.source.NgExtensionSourceBridge
import io.legado.app.integration.image.NgExtensionImageLoader
import io.legado.app.model.webBook.WebBook
import io.legado.features.api.source.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionIntegrationTest {
    @Test fun ordinarySourcesDoNotEnterTheExtensionRouter() {
        assertFalse(NgExtensionSourceBridge.handles(BookSource(bookSourceUrl = "https://fixture.example")))
        val manga = BookSource(bookSourceUrl = "tachiyomi://12", bookSourceType = BookSourceType.image)
        val anime = BookSource(bookSourceUrl = "tachiyomi://12", bookSourceType = BookSourceType.video)
        assertEquals("mihon", NgExtensionSourceBridge.key(manga)!!.provider)
        assertEquals("aniyomi", NgExtensionSourceBridge.key(anime)!!.provider)
        assertNotEquals(PluginUris.source(NgExtensionSourceBridge.key(manga)!!), PluginUris.source(NgExtensionSourceBridge.key(anime)!!))
    }
    @Test fun missingExtensionFailsExplicitlyWithoutFallingBackToNormalHttp() = runBlocking {
        val source = BookSource(bookSourceUrl = "feature-source://mihon/12", bookSourceType = BookSourceType.image)
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { WebBook.searchBookAwait(source, "fixture") } }
        assertTrue(error.message.orEmpty().contains("扩展未加载"))
        assertThrows(CancellationException::class.java) { runBlocking { withContext(Job().apply { cancel() }) { WebBook.searchBookAwait(source, "fixture") } } }
        Unit
    }
    @Test fun extensionImagesKeepDurableIdentifiersAndOrdinaryImagesKeepTheirPath() {
        val image = ImageReference(SourceKey("mihon", ContentKind.MANGA, "12"), 3, "/page?q=中/文", "https://fixture.example/image?a=1&b=2")
        assertTrue(NgExtensionImageLoader.handles(PluginUris.image(image)))
        assertFalse(NgExtensionImageLoader.handles("https://fixture.example/image"))
        assertEquals(image, PluginUris.image(PluginUris.image(image)))
        assertEquals("https://fixture.example/image?a=1&b=2", PluginUris.image("tachiyomi-img://12/https%3A%2F%2Ffixture.example%2Fimage%3Fa%3D1%26b%3D2")!!.imageUrl)
    }
}
