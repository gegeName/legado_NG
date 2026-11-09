
package eu.kanade.tachiyomi.network

import android.content.Context
import java.io.IOException

class JavaScriptEngine(context: Context) {

    suspend fun <T> evaluate(script: String): T = throw IOException("JavaScriptEngine is not wired to the host JS engine")
}
