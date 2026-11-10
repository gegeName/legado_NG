package io.legado.app.integration.manga

import androidx.compose.runtime.*
import io.legado.app.data.entities.Book
import io.legado.app.integration.image.NgOriginalImageResources
import io.legado.features.api.host.ResourcePriority
import io.legado.features.api.manga.TranslationFileLease
import io.legado.features.api.manga.TranslationPageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import java.io.File

@Stable
internal class NgMangaTranslationDisplay {
    var file by mutableStateOf<File?>(null)
    var version by mutableStateOf<String?>(null)
    var status by mutableStateOf<TranslationPageStatus?>(null)
    var failed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    fun retry() { revision++ }
    fun displayFailed() { file = null; failed = true }
}

/** Resolves the cached or newly rendered translation for the visible page. */
@Composable
internal fun rememberNgMangaTranslation(book: Book?, url: String, chapterId: String, index: Int,
    enabled: Boolean, active: Boolean, settingsRevision: Int): NgMangaTranslationDisplay {
    val state = remember(book?.bookUrl, book?.origin, url, chapterId, index) { NgMangaTranslationDisplay() }
    val generation = remember(state, settingsRevision) { book?.let { NgOriginalImageResources.generation(it.bookUrl) } ?: 0L }
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(state, enabled, settingsRevision, state.revision) {
        state.failed = false
        if (!enabled || book == null) {
            state.file = null; state.version = null; state.status = null; state.busy = false
            return@LaunchedEffect
        }
        var lease: TranslationFileLease? = null
        try {
            // Once resolved, keep the lease for this composed page across chapter/active changes.
            // Disposing the page or changing settings still releases it in finally.
            snapshotFlow { currentActive }.first { it }
            state.busy = true
            lease = NgMangaTranslationBridge.translate(book.copy(), url, chapterId, index, ResourcePriority.VISIBLE, force = state.revision > 0, generation = generation)
            state.file = lease.file; state.version = lease.version; state.status = lease.status; state.busy = false
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.failed = true; state.busy = false }
        finally {
            state.file = null
            state.version = null
            state.busy = false
            lease?.close()
        }
    }
    return state
}
