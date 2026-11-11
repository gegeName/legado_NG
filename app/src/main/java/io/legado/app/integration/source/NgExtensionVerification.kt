package io.legado.app.integration.source

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.legado.app.help.http.CookieStore
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.browser.WebViewModel
import io.legado.app.utils.startActivity
import io.legado.features.adapter.ExtensionChallengeSession
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Uses NG's visible browser while extension requests keep their shared WebView cookie jar. */
object NgExtensionVerification {
    private const val SESSION = "extensionChallengeSession"
    private val sessions = ConcurrentHashMap<String, ExtensionChallengeSession>()

    fun verify(context: Context, url: String, headers: Map<String, String>, oldClearance: String?, isCancelled: () -> Boolean): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (isCancelled()) return false
        val id = UUID.randomUUID().toString()
        val session = ExtensionChallengeSession(url, headers, oldClearance)
        sessions[id] = session
        try {
            ContextCompat.getMainExecutor(context).execute {
                if (session.isFinished) return@execute
                try {
                    context.startActivity<WebViewActivity> {
                        putExtra("url", url)
                        putExtra("title", "扩展源人机验证")
                        putExtra("sourceName", url.toHttpUrl().host)
                        putExtra(SESSION, id)
                    }
                } catch (_: Exception) {
                    session.cancel()
                }
            }
            return session.await(isCancelled)
        } finally {
            session.cancel()
            sessions.remove(id, session)
        }
    }

    fun isVerification(intent: Intent): Boolean = intent.hasExtra(SESSION)

    /** Raw extension URLs and headers must not be reinterpreted as NG rule URLs. */
    fun prepare(intent: Intent, model: WebViewModel): Boolean {
        if (!isVerification(intent)) return false
        val session = sessions[intent.getStringExtra(SESSION)]
        model.baseUrl = session?.url ?: intent.getStringExtra("url").orEmpty()
        model.headerMap.clear()
        session?.headers?.forEach { (name, value) ->
            model.headerMap[if (name.equals("User-Agent", true)) "User-Agent" else name] = value
        }
        return true
    }

    fun attach(activity: WebViewActivity, view: WebView) {
        if (!isVerification(activity.intent)) return
        val session = sessions[activity.intent.getStringExtra(SESSION)]
        // CookieManager already belongs to the extension client. Do not inject stale NG cookies.
        val poll = object : Runnable {
            override fun run() {
                if (session == null || session.isFinished || complete(activity.intent)) {
                    activity.finish()
                } else {
                    view.postDelayed(this, 500)
                }
            }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                view.removeCallbacks(poll)
                if (!activity.isChangingConfigurations) session?.cancel()
            }
        })
        view.post(poll)
    }

    fun complete(intent: Intent): Boolean {
        val session = sessions[intent.getStringExtra(SESSION)] ?: return false
        val cookie = CookieManager.getInstance().getCookie(session.url)
        return session.complete(cookie) { accepted ->
            CookieManager.getInstance().flush()
            CookieStore.replaceCookie(session.url, accepted)
        }
    }

    fun finish(intent: Intent) {
        if (!isVerification(intent)) return
        val session = sessions[intent.getStringExtra(SESSION)] ?: return
        if (!complete(intent)) session.cancel()
    }
}
