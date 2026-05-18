package com.dyrpa.agent.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenSize {
    fun get(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }
}
