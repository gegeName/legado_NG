package io.legado.features.translation

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call

@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> withTranslationCallCancellation(call: Call, action: () -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val handle = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
    try { return action().also { context.ensureActive() } }
    catch (e: Exception) { context.ensureActive(); throw e }
    finally { handle?.dispose() }
}
