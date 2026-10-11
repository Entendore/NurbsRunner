package com.example.nurbstrunner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only persistence. SharedPreferences (private internal storage) is
 * the canonical store; snapshot()/restore() expose the identical JSON to
 * the in-app export/import so the user can move saves between devices with
 * a plain file — no cloud involved.
 *
 * persistRaw() exists for the thread-safety contract: the GL thread builds
 * the JSON (the only thread that mutates Lineage), the UI writes it.
 */
object SaveStore {
    private const val PREFS = "nurbs_save"
    private const val KEY = "json"
    private const val VERSION = 4

    // ---------------- canonical JSON ----------------

    private fun toJson(s: Settings, l: Lineage): JSONObject {
        val j = JSONObject()
        j.put("v", VERSION)
        l.best?.let { j.put("genes", genesToJson(it)) }
        j.put("fit", l.bestFit.toDouble())
        j.put("dist", l.bestDist.toDouble())
        j.put("evo", l.evolutions)
        j.put("deaths", l.deaths)
        j.put("hist", JSONArray().apply { for (v in l.history) put(v.toDouble()) })
        j.put("q", s.quality); j.put("snd", s.sound)
        j.put("mus", s.music); j.put("hap", s.haptics); j.put("tut", s.seenTutorial)
        j.put("tr", JSONArray().apply { for (v in l.traits) put(v.toDouble()) })
        j.put("anc", JSONArray().apply {
            for (a in l.ancestors) put(JSONObject()
                .put("n", a.gen).put("f", a.fit.toDouble()).put("b", a.bias.toDouble())
                .put("g", genesToJson(a.genes)))
        })
        j.put("dl", JSONArray().apply {
            for (d in l.deathLog) put(JSONObject()
                .put("c", d.cause).put("d", d.dist).put("g", d.gen).put("s", d.skin))
        })
        j.put("runs", JSONArray().apply {
            for (r in l.runLog) put(JSONObject()
                .put("d", r.dist).put("s", r.score).put("g", r.gen))
        })
        return j
    }

    private fun genesToJson(g: FloatArray) =
        JSONArray().apply { for (v in g) put(v.toDouble()) }

    private fun genesFromJson(a: JSONArray): FloatArray? {
        if (a.length() != Genetics.N_GENES) return null
        val g = FloatArray(a.length()) { a.optDouble(it).toFloat() }
        Genetics.clampAll(g)
        return g
    }

    /** @return true when the document was understood (v1/v2/v3 migrate forward). */
    private fun fromJson(j: JSONObject, s: Settings, l: Lineage): Boolean {
        val v = j.optInt("v")
        if (v !in 1..VERSION) return false

        j.optJSONArray("genes")?.let { genesFromJson(it)?.let { g -> l.best = g } }
        l.bestFit = j.optDouble("fit", -1e9).toFloat()
        l.bestDist = j.optDouble("dist", 0.0).toFloat().coerceAtLeast(0f)
        l.evolutions = j.optInt("evo", 0).coerceAtLeast(0)
        l.deaths = j.optInt("deaths", 0).coerceAtLeast(0)
        l.history.clear()
        j.optJSONArray("hist")?.let { h ->
            for (i in 0 until h.length()) l.history.add(h.optDouble(i).toFloat())
        }
        s.quality = j.optInt("q", 2).coerceIn(0, 2)
        s.sound = j.optBoolean("snd", true)
        s.haptics = j.optBoolean("hap", true)
        s.seenTutorial = j.optBoolean("tut", false)

        if (v >= 2) s.music = j.optBoolean("mus", true)

        // traits (v3+; older saves start at neutral 0.5)
        l.traits = j.optJSONArray("tr")?.let { tr ->
            if (tr.length() == Traits.N)
                FloatArray(tr.length()) { tr.optDouble(it).toFloat() }
            else Traits.defaults()
        } ?: Traits.defaults()
        Traits.clamp(l.traits)

        if (v >= 2) {
            l.ancestors.clear()
            j.optJSONArray("anc")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val g = o.optJSONArray("g")?.let { genesFromJson(it) } ?: continue
                    l.ancestors.add(Lineage.Ancestor(
                        g, o.optInt("n", 0), o.optDouble("f", 0.0).toFloat(),
                        o.optDouble("b", 0.0).toFloat(), l.describe(g)))
                    if (l.ancestors.size >= 10) break
                }
            }
        }
        if (v >= 3) {
            l.deathLog.clear()
            j.optJSONArray("dl")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    l.deathLog.add(Lineage.DeathRecord(
                        o.optString("c", "?"), o.optInt("d", 0),
                        o.optInt("g", 0), o.optString("s", "")))
                    if (l.deathLog.size >= 10) break
                }
            }
        }
        if (v >= 4) {
            l.runLog.clear()
            j.optJSONArray("runs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    l.runLog.add(Lineage.RunRecord(
                        o.optInt("d", 0), o.optInt("s", 0), o.optInt("g", 0)))
                    if (l.runLog.size >= GameConfig.RUN_HISTORY_MAX) break
                }
            }
        }
        return true
    }

    // ---------------- device store ----------------

    @Synchronized
    fun save(ctx: Context, s: Settings, l: Lineage) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, toJson(s, l).toString()).apply()
        }.onFailure { Logx.e("Save", "save failed: ${it.message}", it) }
    }

    @Synchronized
    fun load(ctx: Context, s: Settings, l: Lineage) {
        runCatching {
            val raw = ctx.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return
            if (!fromJson(JSONObject(raw), s, l)) {
                Logx.e("Save", "unsupported save version — starting fresh species")
                l.best = null; l.bestFit = -1e9f; l.history.clear()
            }
        }.onFailure {
            Logx.e("Save", "load failed, starting fresh: ${it.message}", it)
            l.best = null; l.bestFit = -1e9f; l.history.clear()
        }
    }

    /** Persists a pre-built snapshot (created on the GL thread). */
    @Synchronized
    fun persistRaw(ctx: Context, json: String) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json).apply()
        }.onFailure { Logx.e("Save", "persistRaw failed: ${it.message}", it) }
    }

    // ---------------- portable snapshot (export/import) ----------------

    @Synchronized
    fun snapshot(s: Settings, l: Lineage): String = toJson(s, l).toString()

    @Synchronized
    fun restore(json: String, s: Settings, l: Lineage): Boolean =
        runCatching { fromJson(JSONObject(json), s, l) }.getOrDefault(false)
}
