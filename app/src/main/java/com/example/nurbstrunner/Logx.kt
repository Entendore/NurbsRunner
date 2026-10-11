package com.example.nurbstrunner

import android.util.Log

/** Gated logging. Wire ENABLED to BuildConfig.DEBUG if you prefer. */
object Logx {
    const val ENABLED = true
    fun d(tag: String, msg: String) { if (ENABLED) Log.d("NURBS/$tag", msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e("NURBS/$tag", msg, t) }
}
