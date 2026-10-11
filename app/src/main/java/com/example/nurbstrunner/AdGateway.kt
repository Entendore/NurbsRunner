package com.example.nurbstrunner

/**
 * Ad seam, mirroring GameSfx/Telemetry. The game core knows only this
 * interface; flavors decide whether ads exist at all.
 */
interface AdGateway {
    /** True when a rewarded video can be shown right now. */
    fun isRewardedReady(): Boolean

    /** Shows a rewarded ad; [onResult](true) only when the reward was earned. */
    fun showRewarded(onResult: (Boolean) -> Unit)

    /** Called after the player continues the lineage; shows an interstitial on a cadence. */
    fun maybeShowInterstitial()

    fun onDestroy() {}

    companion object {
        val NONE = object : AdGateway {
            override fun isRewardedReady() = false
            override fun showRewarded(onResult: (Boolean) -> Unit) { onResult(false) }
            override fun maybeShowInterstitial() {}
        }
    }
}
