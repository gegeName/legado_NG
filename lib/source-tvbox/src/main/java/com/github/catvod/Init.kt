package com.github.catvod

import android.content.Context
import java.lang.ref.WeakReference

object Init {

    private var context: WeakReference<Context?>? = null

    @JvmStatic
    fun set(context: Context?) {
        this.context = WeakReference(context)
    }

    @JvmStatic
    fun context(): Context? = this.context?.get()
}

