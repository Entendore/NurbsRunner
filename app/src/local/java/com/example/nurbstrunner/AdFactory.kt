package com.example.nurbstrunner

import android.app.Activity

/** Local flavor: no ads, ever. */
object AdFactory {
    fun create(activity: Activity, settings: Settings): AdGateway = AdGateway.NONE
}
