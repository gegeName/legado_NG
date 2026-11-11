
package eu.kanade.tachiyomi.network

import android.content.Context
import io.legado.features.extensioncompat.ExtensionServices
import eu.kanade.tachiyomi.network.interceptor.ExtensionCookieInterceptor
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
        .apply {
            // Provider interceptors are inserted before this. Tag every retry so alternate
            // host transports (including Cronet) read the current jar when actually sending.
            interceptors().add(0, ExtensionCookieInterceptor(cookieJar))
            addNetworkInterceptor(ExtensionCookieInterceptor.networkInterceptor())
        }

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
