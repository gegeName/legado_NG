package com.github.catvod.crawler

import android.content.Context
import com.github.catvod.net.OkHttp
import okhttp3.Dns
import okhttp3.OkHttpClient

abstract class Spider {

    @JvmField
    var siteKey: String? = null

    companion object {

        @JvmStatic
        fun safeDns(): Dns = OkHttp.dns()

        @JvmStatic
        fun client(): OkHttpClient = OkHttp.client()
    }

    @Throws(Exception::class)
    open fun init(context: Context?) {
    }

    @Throws(Exception::class)
    open fun init(context: Context?, extend: String?) {
        init(context)
    }

    @Throws(Exception::class)
    open fun homeContent(filter: Boolean): String = ""

    @Throws(Exception::class)
    open fun homeVideoContent(): String = ""

    @Throws(Exception::class)
    open fun categoryContent(tid: String?, pg: String?, filter: Boolean, extend: HashMap<String, String>?): String = ""

    @Throws(Exception::class)
    open fun detailContent(ids: List<String>?): String = ""

    @Throws(Exception::class)
    open fun searchContent(key: String?, quick: Boolean): String = ""

    @Throws(Exception::class)
    open fun searchContent(key: String?, quick: Boolean, pg: String?): String = ""

    @Throws(Exception::class)
    open fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String = ""

    @Throws(Exception::class)
    open fun liveContent(url: String?): String = ""

    @Throws(Exception::class)
    open fun manualVideoCheck(): Boolean = false

    @Throws(Exception::class)
    open fun isVideoFormat(url: String?): Boolean = false

    @Throws(Exception::class)
    open fun proxy(params: Map<String, String>?): Array<Any?>? = null

    @Throws(Exception::class)
    open fun action(action: String?): String? = null

    open fun destroy() {
    }
}

