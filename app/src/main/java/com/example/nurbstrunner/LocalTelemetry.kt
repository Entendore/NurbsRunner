package com.example.nurbstrunner

/**
 * 100% on-device telemetry: a bounded in-memory ring buffer, mirrored to
 * logcat. Contains no network client of any kind.
 */
class LocalTelemetry : Telemetry {

    private val ring = ArrayDeque<String>(CAP)
    private var dropped = 0

    override fun event(name: String, params: Map<String, Any?>) {
        if (ring.size >= CAP) { ring.removeFirst(); dropped++ }
        val p = if (params.isEmpty()) ""
                else " " + params.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val line = "$name$p"
        ring.addLast(line)
        Logx.d("Telemetry", line)
    }

    /** Newest-last; used by the pause-menu event log. */
    fun recent(): List<String> = ring.toList()

    private companion object { const val CAP = 64 }
}
