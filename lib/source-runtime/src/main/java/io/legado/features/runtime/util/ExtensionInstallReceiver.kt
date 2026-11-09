package io.legado.features.runtime.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.legado.features.runtime.RuntimeLog
import kotlinx.coroutines.Dispatchers
import io.legado.features.runtime.ExtensionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

internal class ExtensionInstallReceiver(
    private val appContext: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onExtensionsChanged: () -> Unit,
) : BroadcastReceiver(), AutoCloseable {
    private var registered = false

    private sealed interface Event {
        data class Added(val pkgName: String, val isPrivateAction: Boolean) : Event
        data class Replaced(val pkgName: String, val isPrivateAction: Boolean) : Event
        data class Removed(val pkgName: String, val isPrivateAction: Boolean) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    handleEvent(event)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    RuntimeLog.put("扩展事件处理失败: $event", e)
                }
            }
        }
    }

    private val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(ACTION_EXTENSION_ADDED)
        addAction(ACTION_EXTENSION_REPLACED)
        addAction(ACTION_EXTENSION_REMOVED)
        addDataScheme("package")
    }

    @Synchronized fun register() {
        if (registered) return
        ContextCompat.registerReceiver(appContext, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    @Synchronized override fun close() {
        if (registered) appContext.unregisterReceiver(this)
        registered = false
        events.close()
        scope.cancel()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pkgName = getPackageNameFromIntent(intent) ?: return

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (isReplacing(intent)) return
                events.trySend(Event.Added(pkgName, isPrivateAction = false))
            }

            ACTION_EXTENSION_ADDED -> {
                events.trySend(Event.Added(pkgName, isPrivateAction = true))
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                events.trySend(Event.Replaced(pkgName, isPrivateAction = false))
            }

            ACTION_EXTENSION_REPLACED -> {
                events.trySend(Event.Replaced(pkgName, isPrivateAction = true))
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                if (isReplacing(intent)) return
                events.trySend(Event.Removed(pkgName, isPrivateAction = false))
            }

            ACTION_EXTENSION_REMOVED -> {
                events.trySend(Event.Removed(pkgName, isPrivateAction = true))
            }
        }
    }

    private fun handleEvent(event: Event) {
        when (event) {
            is Event.Added -> {
                if (event.isPrivateAction || ExtensionLoader.isExtensionPackage(appContext, event.pkgName)) {
                    onExtensionsChanged()
                }
            }

            is Event.Replaced -> {
                if (event.isPrivateAction || ExtensionLoader.isExtensionPackage(appContext, event.pkgName)) {
                    onExtensionsChanged()
                }
            }

            is Event.Removed -> {
                if (event.isPrivateAction || ExtensionRuntime.hasExtension(event.pkgName)) {
                    onExtensionsChanged()
                }
            }
        }
    }

    private fun isReplacing(intent: Intent): Boolean {
        return intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
    }

    private fun getPackageNameFromIntent(intent: Intent): String? {
        return intent.data?.encodedSchemeSpecificPart?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val ACTION_EXTENSION_ADDED = "io.legado.features.runtime.ACTION_EXTENSION_ADDED"
        private const val ACTION_EXTENSION_REPLACED = "io.legado.features.runtime.ACTION_EXTENSION_REPLACED"
        private const val ACTION_EXTENSION_REMOVED = "io.legado.features.runtime.ACTION_EXTENSION_REMOVED"

        fun notifyAdded(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_ADDED)
        }

        fun notifyReplaced(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REPLACED)
        }

        fun notifyRemoved(context: Context, pkgName: String) {
            notify(context, pkgName, ACTION_EXTENSION_REMOVED)
        }

        private fun notify(context: Context, pkgName: String, action: String) {
            val intent = Intent(action).apply {
                data = "package:$pkgName".toUri()
                `package` = context.packageName
            }
            context.sendBroadcast(intent)
        }
    }
}

