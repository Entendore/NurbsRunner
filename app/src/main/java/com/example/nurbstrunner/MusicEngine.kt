package com.example.nurbstrunner

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fully procedural music on AudioTrack: bass + minor-7th arpeggio + soft hats.
 * Tempo tracks game speed; the chord root follows the current zone.
 * 100% synthesized — no assets, no network.
 */
class MusicEngine(private val settings: Settings) {

    private companion object {
        const val SR = 44100
        const val BUF = 1024                                    // ~23 ms slices
        val ZONE_SEMI = intArrayOf(0, -2, 3, -4, 5)             // root per zone
        val ARP = intArrayOf(0, 3, 7, 10, 12, 10, 7, 3)         // minor 7th
        val PROG = intArrayOf(0, -4, -7, -2)                    // i · VI · III · VII
    }

    @Volatile var intensity = 0.4f                              // 0..1, from speed
    @Volatile var zone = 0

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val rng = Random(99)
    private val table = FloatArray(2048) { i -> sin(2.0 * PI * i / 2048).toFloat() }

    private fun osc(p: Float): Float = table[((p - p.toInt()) * 2048f).toInt() and 2047]

    fun start() {
        if (running || !settings.music) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(SR * 2)
            .build()
        track = t
        running = true
        thread = Thread({ loop(t) }, "nurbs-music").apply {
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500); thread = null
        runCatching { track?.stop(); track?.release() }
        track = null
    }

    fun release() = stop()

    private fun loop(t: AudioTrack) {
        val pcm = ShortArray(BUF)
        var stepCountdown = 0
        var step = 0
        var bar = 0
        var bassPhase = 0f; var arpPhase = 0f
        var bassInc = 0f; var arpInc = 0f
        var bassEnv = 0f; var arpEnv = 0f; var hatEnv = 0f
        var hatState = 0f
        val bassDecay = exp(-1f / (SR * 0.22f))
        val arpDecay = exp(-1f / (SR * 0.16f))
        val hatDecay = exp(-1f / (SR * 0.03f))

        fun trigger() {
            val bpm = 96f + intensity * 72f                     // 96 -> 168 with speed
            stepCountdown = (SR * 60f / bpm / 2f).toInt()       // eighth-note grid
            val root = 110f * 2f.pow(ZONE_SEMI[zone % 5] / 12f) *
                       2f.pow(PROG[bar % 4] / 12f)
            if (step % 2 == 0) {                                // bass on quarters
                bassInc = root * 2f.pow((if (step % 4 == 0) 0 else 7) / 12f) / SR
                bassEnv = 1f
            }
            arpInc = root * 2f * 2f.pow(ARP[step % 8] / 12f) / SR
            arpEnv = 1f
            hatEnv = if (step % 2 == 0) 1f else 0.45f
            step++
            if (step % 8 == 0) bar++
        }

        while (running) {
            for (i in 0 until BUF) {
                if (--stepCountdown <= 0) trigger()
                bassEnv *= bassDecay; arpEnv *= arpDecay; hatEnv *= hatDecay
                bassPhase += bassInc; if (bassPhase > 1f) bassPhase -= 1f
                arpPhase += arpInc;   if (arpPhase > 1f) arpPhase -= 1f
                hatState += 0.7f * ((rng.nextFloat() * 2f - 1f) - hatState)
                val s = osc(bassPhase) * bassEnv * 0.20f
                        + osc(arpPhase) * arpEnv * (0.09f + 0.05f * intensity)
                        + hatState * hatEnv * 0.05f
                pcm[i] = (s.coerceIn(-1f, 1f) * 0.85f * 32767f).toInt().toShort()
            }
            if (!running) break
            t.write(pcm, 0, BUF)
        }
    }
}
