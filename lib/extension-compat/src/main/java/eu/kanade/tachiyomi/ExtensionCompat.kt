

package eu.kanade.tachiyomi

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.ExtensionInterceptorsProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.ChallengeCookieResolver
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

fun registerExtensionCompat(application: Application) {
    Injekt.addSingleton(application)
    Injekt.addSingletonFactory<Json> {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    
    Injekt.addSingletonFactory<ExtensionInterceptorsProvider> {
        object : ExtensionInterceptorsProvider {
            override fun clientInterceptors(
                context: Context,
                cookieJar: ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ): List<Interceptor> = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
                CloudflareInterceptor(context, cookieJar, defaultUserAgent),
            )

            override fun cloudflareClientInterceptors(
                context: Context,
                cookieJar: ChallengeCookieResolver,
                defaultUserAgent: () -> String,
            ): List<Interceptor> = listOf(
                UncaughtExceptionInterceptor(),
                UserAgentInterceptor(defaultUserAgent),
            )
        }
    }
    Injekt.addSingletonFactory { NetworkHelper(application) }
}

