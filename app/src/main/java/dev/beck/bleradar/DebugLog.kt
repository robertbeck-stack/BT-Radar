package dev.beck.bleradar

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dagbók til villuleitar. Hver lína fer í Logcat (tag „BLE3D“) og er líka geymd í minni,
 * svo hægt sé að sýna hana á skjánum (hnappurinn „Log“) og vista með CSV skránni.
 */
object DebugLog {
    private const val TAG = "BLE3D"
    private const val MAX_LINES = 3000
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun i(msg: String) = add('I', msg, null)
    fun w(msg: String, e: Throwable? = null) = add('W', msg, e)

    @Synchronized
    private fun add(level: Char, msg: String, e: Throwable?) {
        if (level == 'W') Log.w(TAG, msg, e) else Log.i(TAG, msg)
        val extra = e?.let { " — ${it.javaClass.simpleName}: ${it.message} @ ${it.stackTrace.firstOrNull()}" } ?: ""
        lines.addLast("${fmt.format(Date())} $level $msg$extra")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized fun tail(n: Int): List<String> = lines.takeLast(n)
    @Synchronized fun all(): List<String> = lines.toList()
}
