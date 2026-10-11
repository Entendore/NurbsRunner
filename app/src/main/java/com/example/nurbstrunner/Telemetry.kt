package com.example.nurbstrunner

/**
 * Telemetry seam. In this project it is ALWAYS local: see LocalTelemetry.
 * Do NOT add a cloud implementation — it would break the offline guarantee.
 */
interface Telemetry {
    fun event(name: String, params: Map<String, Any?> = emptyMap()) {}

    companion object { val NONE = object : Telemetry {} }
}
