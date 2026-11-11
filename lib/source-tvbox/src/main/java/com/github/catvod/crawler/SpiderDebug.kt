package com.github.catvod.crawler

import java.util.logging.Logger

object SpiderDebug {

    private val logger = Logger.getLogger("SpiderDebug")

    @JvmStatic
    fun log(th: Throwable?) {
        th?.let { logger.severe(it.toString()) }
    }

    @JvmStatic
    fun log(msg: String?) {
        if (!msg.isNullOrEmpty()) logger.fine(msg)
    }

    @JvmStatic
    fun log(tag: String?, msg: String?, vararg args: Any?) {
        if (!msg.isNullOrEmpty()) logger.fine("[$tag] ${String.format(msg, *args)}")
    }
}
