

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

    protected open fun solve(request: Request, oldClearance: String?, isCancelled: () -> Boolean) =
        solve(request, oldClearance)

    protected open fun canCarrySolvedCookies(request: Request): Boolean =
        request.header(cookieJarHeader) != null

    protected open fun onChallengeResponse(response: Response, retried: Boolean) {}

    final override fun intercept(chain: Interceptor.Chain): Response {
        val challengeStart = System.nanoTime() / 1_000_000
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isChallenge(response)) {
            return response
        }
        onChallengeResponse(response, false)
        val challengeRequest = response.request
        if (!isEngineAvailable()) {
            onEngineUnavailable()
            return response
        }

        if (!canCarrySolvedCookies(challengeRequest)) {
            CompatLog.put("请求未启用 CookieJar, 解出的挑战 cookie 带不上, 跳过求解")
            return response
        }
        prepareEngine()

        val state = hostStates.computeIfAbsent(challengeRequest.url.host) { HostChallengeState() }

        response.close()
        try {

            val waitDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHALLENGE_WAIT_MS)
            while (!state.lock.tryLock(250, TimeUnit.MILLISECONDS)) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                if (System.nanoTime() >= waitDeadline) throw CloudflareBypassException()
            }
            try {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                if (state.lastAttemptEnd >= challengeStart) {

                    
                    if (!state.lastSolveOk) {
                        throw CloudflareBypassException()
                    }
                } else {
                    state.lastSolveOk = false
                    val old = oldClearance(challengeRequest)
                    clearClearance(challengeRequest)
                    try {
                        solve(challengeRequest, old) { chain.call().isCanceled() }
                        state.lastSolveOk = true
                    } finally {
                        state.lastAttemptEnd = System.nanoTime() / 1_000_000
                    }
                }
            } finally {
                state.lock.unlock()
            }

            if (chain.call().isCanceled()) throw IOException("Canceled")
            val retried = chain.proceed(request)
            onChallengeResponse(retried, true)
            if (isChallenge(retried)) {
                retried.close()
                throw CloudflareBypassException()
            }
            return retried
        }

        catch (e: CloudflareBypassException) {
            throw IOException(CLOUDFLARE_BYPASS_FAILURE_MESSAGE, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Canceled", e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private fun isChallenge(response: Response): Boolean =
        response.header("cf-mitigated").equals("challenge", ignoreCase = true)

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

private const val CHALLENGE_WAIT_MS = CHALLENGE_TIMEOUT_MS + 185_000L

const val CHALLENGE_CALL_TIMEOUT_MS = CHALLENGE_TIMEOUT_MS * 2 + 5_000L

const val COOKIE_CLEARANCE = "cf_clearance"

private const val CLOUDFLARE_BYPASS_FAILURE_MESSAGE = "Failed to bypass Cloudflare challenge"

class CloudflareBypassException : Exception()

