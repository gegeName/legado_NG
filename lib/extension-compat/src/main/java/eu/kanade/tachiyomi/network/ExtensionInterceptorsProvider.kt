

package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver
import okhttp3.Interceptor

interface ExtensionInterceptorsProvider {

    fun clientInterceptors(
        context: Context,
        cookieJar: ChallengeCookieResolver,
        defaultUserAgent: () -> String,
    ): List<Interceptor>

    fun cloudflareClientInterceptors(
        context: Context,
        cookieJar: ChallengeCookieResolver,
        defaultUserAgent: () -> String,
    ): List<Interceptor>
}

