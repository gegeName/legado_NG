

package eu.kanade.tachiyomi.network.interceptor

import io.legado.features.extensioncompat.CompatLog
import io.legado.features.extensioncompat.cookieJarHeader
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

abstract class ChallengeInterceptorBase : Interceptor {

    private val hostStates = ConcurrentHashMap<String, HostChallengeState>()

    protected abstract fun isEngineAvailable(): Boolean

    protected abstract fun onEngineUnavailable()

    protected open fun prepareEngine() {}

    protected abstract fun oldClearance(request: Request): String?

    protected abstract fun clearClearance(request: Request)

    protected abstract fun solve(request: Request, oldClearance: String?)

    protected open fun canCarrySolvedCookies(request: Request): Boolean =
        request.header(cookieJarHeader) != null

    final override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isChallenge(response)) {
            return response
        }
        if (!isEngineAvailable()) {
            onEngineUnavailable()
            return response
        }

        if (!canCarrySolvedCookies(request)) {
            CompatLog.put("请求未启用 CookieJar, 解出的挑战 cookie 带不上, 跳过求解")
            return response
        }
        prepareEngine()

        val state = hostStates.computeIfAbsent(request.url.host) { HostChallengeState() }

        val challengeStart = System.nanoTime() / 1_000_000

        response.close()
        try {

            if (!state.lock.tryLock(CHALLENGE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw CloudflareBypassException()
            }
            try {
                if (state.lastAttemptEnd >= challengeStart) {

                    
                    if (!state.lastSolveOk) {
                        throw CloudflareBypassException()
                    }
                } else {
                    val old = oldClearance(request)
                    clearClearance(request)
                    try {
                        solve(request, old)
                        state.lastSolveOk = true
                    } finally {
                        state.lastAttemptEnd = System.nanoTime() / 1_000_000
                    }
                }
            } finally {
                state.lock.unlock()
            }

            return chain.proceed(request)
        }

        catch (e: CloudflareBypassException) {
            throw IOException(CLOUDFLARE_BYPASS_FAILURE_MESSAGE, e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun isChallenge(response: Response): Boolean =
        response.header("cf-mitigated") == "challenge" &&
                response.header("Server") in SERVER_CHECK

    protected fun clearanceValue(cookieStr: String): String? {
        cookieStr.split(';').forEach { pair ->
            val index = pair.indexOf('=')
            if (index > 0 && pair.take(index).trim() == COOKIE_CLEARANCE) {
                return pair.substring(index + 1).trim()
            }
        }
        return null
    }

    private class HostChallengeState {

        val lock = ReentrantLock(true)

        @Volatile
        var lastAttemptEnd = Long.MIN_VALUE

        @Volatile
        var lastSolveOk = false
    }
}

const val CHALLENGE_TIMEOUT_MS = 30_000L

private const val CHALLENGE_WAIT_MS = CHALLENGE_TIMEOUT_MS

const val CHALLENGE_CALL_TIMEOUT_MS = CHALLENGE_TIMEOUT_MS * 2 + 5_000L

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
const val COOKIE_CLEARANCE = "cf_clearance"

private const val CLOUDFLARE_BYPASS_FAILURE_MESSAGE = "Failed to bypass Cloudflare challenge"

class CloudflareBypassException : Exception()

