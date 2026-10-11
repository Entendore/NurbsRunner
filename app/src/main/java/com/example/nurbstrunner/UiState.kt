package com.example.nurbstrunner

/**
 * Immutable snapshot of everything the UI thread needs, built on the GL
 * thread (where all game mutation happens). The UI never touches GameWorld
 * directly — this is the thread-safety contract of the app.
 */
class UiState(
    val gameOver: Boolean,
    val branchActive: Boolean,
    val branchOptions: List<BranchOption>,
    val laneMoved: Boolean,
    val distanceM: Int,
    /** Non-null exactly once per checkpoint/death — a consistent save JSON. */
    val saveJson: String?,
    val reviveOffer: Boolean = false
) {
    val showDeath: Boolean get() = gameOver && !branchActive
}

class BranchOption(val label: String, val skin: String, val traits: String)
