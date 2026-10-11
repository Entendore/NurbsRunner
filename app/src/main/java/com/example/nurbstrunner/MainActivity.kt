package com.example.nurbstrunner

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
        const val MAX_SAVE_BYTES = 256 * 1024
    }

    private lateinit var settings: Settings
    private lateinit var lineage: Lineage
    private lateinit var sfx: SfxManager
    private lateinit var music: MusicEngine
    private lateinit var telemetry: LocalTelemetry
    private lateinit var ads: AdGateway
    private lateinit var world: GameWorld
    private lateinit var glView: GameView
    private lateinit var crashGuard: CrashGuard

    private lateinit var hud: TextView
    private lateinit var announce: TextView
    private lateinit var tutorial: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var pausePanel: ScrollView
    private lateinit var branchPanel: LinearLayout
    private lateinit var branchBox: LinearLayout
    private lateinit var lblSound: TextView
    private lateinit var lblMusic: TextView
    private lateinit var lblHaptics: TextView
    private lateinit var lblQuality: TextView
    private lateinit var lblReset: TextView
    private lateinit var lblCrash: TextView
    private lateinit var crashView: TextView
    private lateinit var hof: TextView
    private lateinit var runs: TextView
    private lateinit var eventLog: TextView

    private var userPaused = false
    private var resetArmed = false
    private var pendingBranchOptions: List<BranchOption> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val unarmRun = Runnable { disarmReset() }

    // GL-thread-built save JSON -> single-writer persistence
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private var lastSaveJson: String? = null

    // audio focus: yield to phone calls, pause on headphone unplug
    private val audioFocus = AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> music.stop()
            AudioManager.AUDIOFOCUS_GAIN ->
                if (settings.music && !userPaused) music.start()
        }
    }
    private val noisyRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = music.stop()
    }

    private val musicTick = object : Runnable {
        override fun run() {
            music.intensity = ((world.speed - 14f) / 20f).coerceIn(0f, 1f)
            music.zone = Themes.zoneIndex(world.scroll)
            uiHandler.postDelayed(this, 300)
        }
    }
    private fun startTick() { uiHandler.removeCallbacks(musicTick); uiHandler.post(musicTick) }
    private fun stopTick() { uiHandler.removeCallbacks(musicTick) }

    private fun focus() {
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.requestAudioFocus(
            audioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }
    private fun unfocus() {
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocus(audioFocus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        crashGuard = CrashGuard(File(filesDir, "crash")).also { it.install() }

        settings = Settings()
        lineage = Lineage()
        SaveStore.load(applicationContext, settings, lineage)
        sfx = SfxManager(applicationContext, settings)
        music = MusicEngine(settings)
        telemetry = LocalTelemetry()
        ads = AdFactory.create(this, settings)
        world = GameWorld(settings, lineage, sfx, telemetry, ads)

        glView = GameView(this, world)
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(GameRenderer(world, settings) { hudText, annText, state ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                hud.text = hudText
                announce.visibility = if (annText.isNotEmpty()) View.VISIBLE else View.GONE
                if (annText.isNotEmpty()) announce.text = annText
                pauseBtn.visibility = if (state.gameOver) View.GONE else View.VISIBLE

                // consistent save JSON (built on the GL thread) -> background writer
                state.saveJson?.let { json ->
                    lastSaveJson = json
                    saveExecutor.execute { SaveStore.persistRaw(applicationContext, json) }
                }

                // tutorial dismissal uses the snapshot, not live world fields;
                // the flag rides the next checkpoint save / onPause flush
                if (!settings.seenTutorial && (state.laneMoved || state.distanceM > 30)) {
                    settings.seenTutorial = true
                    tutorial.visibility = View.GONE
                }

                // thread-safe death UI + input gating
                world.inputEnabled = !userPaused && !state.branchActive && !state.gameOver
                if (state.branchActive && branchPanel.visibility != View.VISIBLE) {
                    showBranchChoice(state.branchOptions, state.reviveOffer)
                } else if (!state.branchActive) {
                    branchPanel.visibility = View.GONE
                }
            }
        })
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        buildUi()
        if (!settings.seenTutorial) tutorial.visibility = View.VISIBLE
        if (crashGuard.latest() != null) {
            lblCrash.visibility = View.VISIBLE
            toast("Recovered from a crash — see pause menu")
        }
        registerReceiver(noisyRx, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        immersive()
        setContentView(root)
    }

    private lateinit var root: FrameLayout

    private fun tv(text: String, size: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setShadowLayer(6f, 0f, 2f, 0xFF000000.toInt())
        }

    private fun buildUi() {
        root = FrameLayout(this)

        hud = tv("", 13f, 0xFF9FE8FF.toInt()).apply { setPadding(32, 40, 32, 0) }
        announce = tv("", 19f, 0xFF7CFF6B.toInt()).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        tutorial = tv(
            "SWIPE ◀ ▶ LANE · TAP JUMP · SWIPE ▼ DIVE\nDIVE FROM HEIGHT = SLAM · LAND CENTERED = PERFECT",
            13f, 0xFFB0B8C8.toInt()
        ).apply { gravity = Gravity.CENTER }

        pauseBtn = tv("❚❚", 18f, 0xFFFFFFFF.toInt()).apply {
            setPadding(28, 20, 28, 20)
            setBackgroundColor(0x66000000)
            setOnClickListener { setUserPaused(!userPaused); sfx.play(Sfx.UI) }
        }

        // ---- death: SECOND WIND offer + choose the successor branch ----
        branchBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        branchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xE0000000.toInt())
            visibility = View.GONE
            isClickable = true
            addView(branchBox)
        }

        fun menuLabel(t: String) = tv(t, 17f, 0xFFE8F4FF.toInt()).apply {
            setPadding(40, 22, 40, 22); gravity = Gravity.CENTER
        }
        lblSound = menuLabel(""); lblMusic = menuLabel("")
        lblHaptics = menuLabel(""); lblQuality = menuLabel("")
        val lblExport = menuLabel("⤓  EXPORT SAVE (local file)")
        val lblImport = menuLabel("⤒  IMPORT SAVE (local file)")
        lblReset = menuLabel("RESET LINEAGE")
        lblCrash = menuLabel("COPY LAST CRASH LOG").apply { visibility = View.GONE }

        val resume = menuLabel("▶  RESUME").apply {
            setOnClickListener { setUserPaused(false); sfx.play(Sfx.UI) }
        }
        // Safe: the pause menu is only tappable while the GL thread is paused,
        // so these Lineage/Settings writes cannot race the simulation.
        lblSound.setOnClickListener {
            settings.sound = !settings.sound
            refreshMenuLabels(); SaveStore.save(applicationContext, settings, lineage)
            sfx.play(Sfx.UI)
        }
        lblMusic.setOnClickListener {
            settings.music = !settings.music
            if (settings.music && !userPaused) music.start() else music.stop()
            refreshMenuLabels(); SaveStore.save(applicationContext, settings, lineage)
            sfx.play(Sfx.UI)
        }
        lblHaptics.setOnClickListener {
            settings.haptics = !settings.haptics
            refreshMenuLabels(); SaveStore.save(applicationContext, settings, lineage)
            sfx.play(Sfx.UI)
        }
        lblQuality.setOnClickListener {
            settings.quality = (settings.quality + 1) % 3
            refreshMenuLabels(); SaveStore.save(applicationContext, settings, lineage)
            sfx.play(Sfx.UI)
        }
        lblExport.setOnClickListener {
            sfx.play(Sfx.UI)
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "nurbs-runner-save.json")
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)   // no cloud providers offered
            }, REQ_EXPORT)
        }
        lblImport.setOnClickListener {
            sfx.play(Sfx.UI)
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)   // no cloud providers offered
            }, REQ_IMPORT)
        }
        lblReset.setOnClickListener {
            sfx.play(Sfx.UI)
            if (!resetArmed) {
                resetArmed = true
                lblReset.text = "TAP AGAIN TO CONFIRM RESET"
                lblReset.setTextColor(0xFFFF5A6E.toInt())
                uiHandler.postDelayed(unarmRun, 3000)
            } else {
                disarmReset()
                lineage.reset()
                SaveStore.save(applicationContext, settings, lineage)
                world.queue(Input.RESTART)
                refreshMenuLabels()
                toast("Lineage reset — fresh species")
            }
        }
        lblCrash.setOnClickListener {
            val f = crashGuard.latest() ?: return@setOnClickListener
            runCatching {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", f.readText().take(8000)))
                toast("Crash log copied to clipboard")
                crashGuard.clear()
                lblCrash.visibility = View.GONE
            }
        }

        hof = TextView(this).apply {
            textSize = 11f; setTextColor(0xFFB0B8C8.toInt())
            gravity = Gravity.CENTER; setPadding(24, 14, 24, 4)
        }
        runs = TextView(this).apply {
            textSize = 11f; setTextColor(0xFF9FE8FF.toInt())
            gravity = Gravity.CENTER; setPadding(24, 10, 24, 4)
        }
        crashView = TextView(this).apply {
            textSize = 9f; setTextColor(0xFFFF5A6E.toInt())
            gravity = Gravity.CENTER; setPadding(24, 4, 24, 4); visibility = View.GONE
        }
        eventLog = TextView(this).apply {
            textSize = 10f; setTextColor(0xFF8892A0.toInt())
            gravity = Gravity.CENTER; setPadding(24, 8, 24, 8)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            addView(resume)
            addView(lblSound)
            addView(lblMusic)
            addView(lblHaptics)
            addView(lblQuality)
            addView(lblExport)
            addView(lblImport)
            addView(lblReset)
            addView(lblCrash)
            addView(crashView)
            addView(tv("NURBS Runner v${GameConfig.VERSION} · offline · saves on-device only",
                11f, 0xFF8892A0.toInt()).apply {
                gravity = Gravity.CENTER; setPadding(0, 6, 0, 0)
            })
            addView(hof)
            addView(runs)
            addView(eventLog)
        }
        pausePanel = ScrollView(this).apply {
            setBackgroundColor(0xD8000000.toInt())
            visibility = View.GONE
            addView(inner)
        }
        refreshMenuLabels()

        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        root.addView(hud, FrameLayout.LayoutParams(-2, -2))
        root.addView(announce, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(tutorial, FrameLayout.LayoutParams(
            -1, -2, Gravity.BOTTOM or Gravity.CENTER).apply { bottomMargin = 120 })
        root.addView(pauseBtn, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 32, 32, 0) })
        root.addView(branchPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(pausePanel, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showBranchChoice(options: List<BranchOption>, revive: Boolean) {
        branchBox.removeAllViews()
        branchBox.addView(tv("SYSTEM FAILURE", 24f, 0xFFFF5A6E.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, 40, 0, 4)
        })
        branchBox.addView(tv("THE BLOODLINE DIVERGES\nCHOOSE YOUR SUCCESSOR", 14f,
            0xFFFFD479.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 18)
        })

        // Rewarded revive — only offered when an ad is loaded and unused this run.
        // Skilled players (FIT >= WIND_FIT) already revived FREE in-world.
        if (revive) {
            branchBox.addView(tv("⏵  SECOND WIND (WATCH AD)", 17f, 0xFF7CFF6B.toInt()).apply {
                gravity = Gravity.CENTER; setPadding(40, 24, 40, 24)
                setBackgroundColor(0x3366FF99)
                setOnClickListener {
                    branchPanel.visibility = View.GONE
                    ads.showRewarded { earned ->
                        runOnUiThread {
                            if (earned) {
                                world.inputEnabled = true
                                world.queue(Input.REVIVE)
                            } else {
                                showBranchChoice(pendingBranchOptions, false)
                            }
                        }
                    }
                }
            })
        }

        val letters = charArrayOf('A', 'B', 'C')
        val hints = arrayOf("conservative", "wild mutation", "hybrid")
        val colors = intArrayOf(0xFF7CFF6B.toInt(), 0xFFFFD479.toInt(), 0xFF9FE8FF.toInt())
        options.forEachIndexed { idx, o ->
            branchBox.addView(tv("${letters[idx]} · ${o.label}", 19f, colors[idx]).apply {
                gravity = Gravity.CENTER; setPadding(40, 16, 40, 2)
            })
            branchBox.addView(tv("${o.skin} · ${hints[idx]}", 12f, 0xFFB0B8C8.toInt()).apply {
                gravity = Gravity.CENTER; setPadding(40, 0, 40, 2)
            })
            branchBox.addView(tv(o.traits, 11f, 0xFF8892A0.toInt()).apply {
                gravity = Gravity.CENTER; setPadding(40, 0, 40, 4)
            })
            branchBox.addView(tv("CARRY THIS BRANCH ▸", 15f, 0xFFE8F4FF.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(40, 10, 40, 18)
                setBackgroundColor(0x33FFFFFF)
                setOnClickListener {
                    sfx.play(Sfx.UI)
                    world.inputEnabled = true
                    world.queue(Input.BRANCH_0 + idx)
                    branchPanel.visibility = View.GONE
                    ads.maybeShowInterstitial()
                }
            })
        }
        branchPanel.visibility = View.VISIBLE
    }

    private fun disarmReset() {
        if (!resetArmed) return
        resetArmed = false
        lblReset.text = "RESET LINEAGE"
        lblReset.setTextColor(0xFFE8F4FF.toInt())
        uiHandler.removeCallbacks(unarmRun)
    }

    private fun refreshMenuLabels() {
        lblSound.text = if (settings.sound) "SOUND   ON" else "SOUND   OFF"
        lblMusic.text = if (settings.music) "MUSIC   ON" else "MUSIC   OFF"
        lblHaptics.text = if (settings.haptics) "HAPTICS ON" else "HAPTICS OFF"
        lblQuality.text = "QUALITY ${settings.qualityName}"
        hof.text = lineage.stats() + "\n" + Traits.describe(lineage.traits) +
                "\n\n" + lineage.hallOfFame()
        runs.text = lineage.recentRuns()
        eventLog.text = telemetry.recent().takeLast(4).joinToString("\n")
        crashGuard.latest()?.let {
            crashView.text = runCatching {
                it.readText().lineSequence().take(4).joinToString("\n")
            }.getOrDefault("")
            crashView.visibility = View.VISIBLE
        } ?: run { crashView.visibility = View.GONE }
    }

    private fun setUserPaused(p: Boolean) {
        if (p == userPaused) return
        userPaused = p
        if (p) {
            refreshMenuLabels()
            glView.onPause()
            music.stop(); stopTick()
        } else {
            glView.onResume()
            music.start(); startTick()
            focus()
        }
        pausePanel.visibility = if (p) View.VISIBLE else View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQ_EXPORT -> runCatching {
                contentResolver.openOutputStream(data.data!!)?.use { os ->
                    os.write((lastSaveJson ?: SaveStore.snapshot(settings, lineage))
                        .toByteArray(Charsets.UTF_8))
                }
                toast("Save exported to local file")
            }.onFailure { toast("Export failed: ${it.message}") }

            REQ_IMPORT -> runCatching {
                val bytes = contentResolver.openInputStream(data.data!!)?.use { ins ->
                    val buf = ByteArray(MAX_SAVE_BYTES + 1)
                    val n = ins.read(buf)
                    if (n in 1..MAX_SAVE_BYTES) buf.copyOf(n) else null
                }
                val ok = bytes?.let {
                    SaveStore.restore(String(it, Charsets.UTF_8), settings, lineage)
                } == true
                if (ok) {
                    SaveStore.save(applicationContext, settings, lineage)
                    world.queue(Input.RESTART)
                    refreshMenuLabels()
                    toast("Imported — lineage applied")
                } else toast("Not a valid NURBS Runner save")
            }.onFailure { toast("Import failed: ${it.message}") }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
        else if (!world.gameOver) setUserPaused(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (world.gameOver || userPaused) { super.onBackPressed(); return }
        setUserPaused(true)
    }

    override fun onPause() {
        super.onPause()
        // GL thread is (or is being) paused; this is the final flush.
        // Common case uses the GL-thread-built cached JSON — race-free.
        SaveStore.persistRaw(applicationContext,
            lastSaveJson ?: SaveStore.snapshot(settings, lineage))
        music.stop(); stopTick(); unfocus()
        if (!userPaused) glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!userPaused) {
            glView.onResume(); music.start(); startTick()
            focus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(noisyRx) }
        uiHandler.removeCallbacks(unarmRun)
        uiHandler.removeCallbacks(musicTick)
        ads.onDestroy()
        sfx.release()
        music.release()
        saveExecutor.shutdown()
    }
}
