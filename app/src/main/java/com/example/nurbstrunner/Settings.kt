package com.example.nurbstrunner

/** Mutable runtime settings, persisted via SaveStore. */
class Settings {
    @Volatile var quality: Int = 2          // 0 low · 1 med · 2 high
    @Volatile var sound: Boolean = true
    @Volatile var music: Boolean = true
    @Volatile var haptics: Boolean = true
    @Volatile var seenTutorial: Boolean = false

    val particleCap: Int
        get() = when (quality) { 0 -> 60; 1 -> 110; else -> 150 }

    val qualityName: String
        get() = when (quality) { 0 -> "LOW"; 1 -> "MED"; else -> "HIGH" }
}
