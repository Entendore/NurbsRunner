package com.example.nurbstrunner

interface GameSfx {
    fun play(id: Int)
    fun buzz(kind: Int)
}

object Sfx {
    const val ORB = 0; const val POWER = 1; const val HIT = 2; const val JUMP = 3
    const val BOOST = 4; const val EVOLVE = 5; const val DEATH = 6; const val UI = 7
    const val SWOOSH = 8
    const val WHOOSH = 9
    const val SMASH = 10
    const val LAND = 11
    const val WARN = 12
    const val CHARGE = 13
    const val BURST = 14
    const val GROW = 15
    const val SETTLE = 16
    const val PERFECT = 17
    const val COUNT = 18
}

object Buzz {
    const val LIGHT = 0; const val MEDIUM = 1; const val HEAVY = 2
}
