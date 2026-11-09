

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Cookie
import okhttp3.HttpUrl

interface ChallengeCookieResolver {

    fun get(url: HttpUrl): List<Cookie>

    fun remove(url: HttpUrl, cookieNames: List<String>?, maxAge: Int): Int
}

