
package eu.kanade.tachiyomi.network

import android.content.Context
import io.legado.features.extensioncompat.ExtensionServices
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class NetworkHelper(private val context: Context) {

    val cookieJar = AndroidCookieJar()

    fun defaultUserAgentProvider(): String = ExtensionServices.require().userAgent()

    private val interceptorProvider: ExtensionInterceptorsProvider by injectLazy()

    private fun newClientBuilder(): OkHttpClient.Builder = ExtensionServices.require().client.newBuilder()

        .callTimeout(0, TimeUnit.MILLISECONDS)
        .cookieJar(cookieJar)

    val client: OkHttpClient = newClientBuilder()
        .apply {

            interceptors().addAll(
                0,
                interceptorProvider.clientInterceptors(context, cookieJar, ::defaultUserAgentProvider),
            )
        }
        .build()

    val cloudflareClient: OkHttpClient = newClientBuilder()
        .apply {
            interceptors().addAll(
                0,
                interceptorProvider.cloudflareClientInterceptors(context, cookieJar, ::defaultUserAgentProvider),
            )
        }
        .build()
}
