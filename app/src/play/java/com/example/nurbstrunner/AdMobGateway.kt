package com.example.nurbstrunner

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * AdMob implementation with UMP consent (EEA/UK compliant), preloaded
 * rewarded + interstitial, and a conservative interstitial cadence.
 * Degrades gracefully offline: nothing loads, everything no-ops.
 */
class AdMobGateway(private val activity: Activity) : AdGateway {

    private companion object {
        // WARNING: TEST unit IDs — replace with your real ones BEFORE release
        const val REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"
        const val INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
        const val INTERSTITIAL_EVERY = 3
        const val INTERSTITIAL_SESSION_CAP = 4
    }

    private var consentDone = false
    private var rewarded: RewardedAd? = null
    private var interstitial: InterstitialAd? = null
    private var continuations = 0
    private var shownThisSession = 0

    init {
        requestConsent {
            MobileAds.initialize(activity) { loadRewarded(); loadInterstitial() }
        }
    }

    private fun requestConsent(onDone: () -> Unit) {
        if (consentDone) { onDone(); return }
        val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    consentDone = true
                    onDone()
                }
            },
            { consentDone = true; onDone() }   // offline → proceed; no fill anyway
        )
    }

    private fun loadRewarded() {
        RewardedAd.load(activity, REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            rewarded = null; loadRewarded()
                        }
                        override fun onAdFailedToShowFullScreenContent(e: AdError) {
                            rewarded = null
                        }
                    }
                }
                override fun onAdFailedToLoad(e: LoadAdError) { rewarded = null }
            })
    }

    private fun loadInterstitial() {
        InterstitialAd.load(activity, INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(e: LoadAdError) { interstitial = null }
            })
    }

    override fun isRewardedReady(): Boolean = rewarded != null

    override fun showRewarded(onResult: (Boolean) -> Unit) {
        val ad = rewarded
        if (ad == null) { onResult(false); return }
        var earned = false
        var resolved = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewarded = null; loadRewarded()
                if (!resolved) { resolved = true; onResult(earned) }
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                rewarded = null
                if (!resolved) { resolved = true; onResult(false) }
            }
        }
        ad.show(activity) { earned = true }
    }

    override fun maybeShowInterstitial() {
        continuations++
        if (continuations % INTERSTITIAL_EVERY != 0) return
        if (shownThisSession >= INTERSTITIAL_SESSION_CAP) return
        val ad = interstitial ?: return
        shownThisSession++
        ad.show(activity)
        interstitial = null
        loadInterstitial()
    }

    override fun onDestroy() {
        rewarded?.fullScreenContentCallback = null
        interstitial?.fullScreenContentCallback = null
        rewarded = null; interstitial = null
    }
}
