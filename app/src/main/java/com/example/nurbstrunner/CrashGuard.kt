package com.example.nurbstrunner

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes uncaught exceptions to [dir] (private app storage — fully local),
 * chains to the platform handler, and keeps only the newest [maxFiles] logs.
 */
class CrashGuard(
    private val dir: File,
    private val maxFiles: Int = 3,
    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()
) : Thread.UncaughtExceptionHandler {

    fun install() {
        dir.mkdirs()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching { writeLog(thread, error) }
        defaultHandler?.uncaughtException(thread, error)
    }

    private fun writeLog(thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
            "thread=${thread.name}\n\n$sw"
        )
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.drop(maxFiles)
            ?.forEach { it.delete() }
    }

    fun latest(): File? =
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }

    fun clear() {
        dir.listFiles { f -> f.name.startsWith("crash-") }?.forEach { it.delete() }
    }
}
