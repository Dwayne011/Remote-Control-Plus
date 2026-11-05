package com.remotecontrolplus.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostics log. Everything that talks to a TV writes here, so a
 * failure can be read and shared from the phone without a debugger.
 *
 * Also mirrored to logcat under the "RCP" tag. Never log credentials: callers
 * are responsible for passing tokens through [redact].
 */
object Diagnostics {
    data class Entry(val time: Long, val tag: String, val message: String, val error: String? = null)

    private const val LIMIT = 1500

    /** Long wire dumps (the Apple TV's 7 KB system info) would swamp a shared log. */
    private const val MAX_LINE = 240
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String, error: Throwable? = null) {
        val entry = Entry(
            time = System.currentTimeMillis(),
            tag = tag,
            message = if (message.length > MAX_LINE) message.take(MAX_LINE) + " … (${message.length} chars)" else message,
            error = error?.let { "${it.javaClass.simpleName}: ${it.message}\n" + it.stackTraceToString().lines().take(6).joinToString("\n") },
        )
        _entries.value = (_entries.value + entry).takeLast(LIMIT)
        if (error != null) Log.w("RCP", "[$tag] $message", error) else Log.i("RCP", "[$tag] $message")
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** Keep enough of a secret to tell two apart, never enough to use it. */
    fun redact(secret: String?): String = when {
        secret == null -> "none"
        secret.length <= 8 -> "***"
        else -> secret.take(4) + "…" + secret.takeLast(2) + " (${secret.length} chars)"
    }

    fun export(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("Remote Control Plus diagnostics")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine()
            for (e in _entries.value) {
                append(fmt.format(Date(e.time))).append(' ').append('[').append(e.tag).append("] ").appendLine(e.message)
                e.error?.let { appendLine("    $it") }
            }
        }
    }
}
