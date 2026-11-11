package com.github.catvod

import android.content.Context

object Runtime {
    @Volatile
    private var applicationContext: Context? = null

    @JvmStatic
    fun set(context: Context) {
        applicationContext = context.applicationContext ?: context
    }

    @JvmStatic
    fun context(): Context = checkNotNull(applicationContext) {
        "CatVod Runtime has not been initialized"
    }
}
