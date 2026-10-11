package com.example.nurbstrunner

import android.app.Activity

object AdFactory {
    fun create(activity: Activity, settings: Settings): AdGateway = AdMobGateway(activity)
}
