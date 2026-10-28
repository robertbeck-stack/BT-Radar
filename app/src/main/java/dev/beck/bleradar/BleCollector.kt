package dev.beck.bleradar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Skannar öll BLE auglýsingapakka (advertisements) eins hratt og síminn leyfir.
 *
 * Android stöðvar skönnun stundum án þess að láta vita: ef hún er ræst oftar en 5× á 30 s,
 * ef ósíuð skönnun keyrir lengur en 30 mín, eða ef slökkt er á Bluetooth/staðsetningu.
 * [ensure] er því kallað reglulega og ræsir eða endurræsir eftir þörfum.
 */
class BleCollector(
    ctx: Context,
    private val onResult: (address: String, name: String?, rssi: Int, tNanos: Long, record: ScanRecord?) -> Unit,
) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val location = ctx.getSystemService(LocationManager::class.java)

    private var wanted = false
    @Volatile private var scanning = false
    private var startedAtMs = 0L
    @Volatile private var lastAdvertMs = 0L
    private val startTimes = ArrayDeque<Long>()

    // Til að greina „dofnaða“ skönnun: auglýsingar á 2 s bili, síðustu 10 s, og hægt dvínandi hámark
    private val recent = AtomicInteger()
    private val window = ArrayDeque<Int>()
    private var peakPer10s = 0.0

    /** Ástæða þess að ekki er verið að skanna (á íslensku), annars null. */
    @Volatile var error: String? = null
        private set

    /** Fjöldi auglýsinga sem hafa borist; dagbókin les og núllstillir. */
    val adverts = AtomicInteger()

    val isScanning get() = scanning

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = deliver(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::deliver)
        override fun onScanFailed(errorCode: Int) {
            DebugLog.w("Skönnun mistókst: ${failName(errorCode)} ($errorCode)")
            error = "BLE skönnun mistókst: ${failName(errorCode)}"
            scanning = false
        }
    }

    private fun deliver(r: ScanResult) {
        lastAdvertMs = SystemClock.elapsedRealtime()
        adverts.incrementAndGet()
        recent.incrementAndGet()
        val rec = r.scanRecord
        onResult(r.device.address, rec?.deviceName, r.rssi, r.timestampNanos, rec)
    }

    @Synchronized fun resume() { wanted = true; start() }

    @Synchronized fun pause() { wanted = false; stop("hlé") }

    /** Kallað reglulega: ræsir ef skönnun á að vera í gangi en er það ekki, endurræsir ef hún er stöðnuð. */
    @Synchronized
    fun ensure() {
        if (!wanted) return
        if (!scanning) { start(); return }
        val now = SystemClock.elapsedRealtime()
        // ensure() er kallað á 2 s fresti: 5 síðustu bil ≈ 10 s
        window.addLast(recent.getAndSet(0))
        while (window.size > 5) window.removeFirst()
        val per10s = window.sum()
        peakPer10s = maxOf(per10s.toDouble(), peakPer10s * 0.99)
        val reason = when {
            adapter?.isEnabled != true -> "slökkt á Bluetooth"
            location?.isLocationEnabled == false -> "slökkt á staðsetningu"
            now - startedAtMs > RESTART_AFTER_MS -> "20 mín liðnar (Android takmarkar langar skannanir)"
            now - max(startedAtMs, lastAdvertMs) > SILENT_MS -> "engar niðurstöður í ${SILENT_MS / 1000} s"
            // Android-skönnunin „dofnar“ stundum niður í örfáar auglýsingar án villu; endurræsing
            // lagar það (sást 13:59 og 14:07). Algjör þögn grípur það ekki ef 2–3 tæki berast áfram.
            now - startedAtMs > FADE_GRACE_MS && window.size == 5 && peakPer10s >= 100 &&
                per10s < peakPer10s * 0.1 -> "skönnun dofnaði ($per10s á 10 s, venjulega ~${peakPer10s.toInt()})"
            else -> return
        }
        DebugLog.w("Endurræsi skönnun: $reason")
        stop(reason)
        start()
    }

    private fun start() {
        if (scanning) return
        val msg = tryStart()
        // Sama villa kemur á 2 s fresti meðan beðið er; skrá bara breytingar
        if (msg != null && msg != error) DebugLog.w("Skönnun ekki ræst: $msg")
        error = msg
    }

    @SuppressLint("MissingPermission")
    private fun tryStart(): String? {
        val a = adapter ?: return "Síminn hefur ekkert Bluetooth"
        if (!a.isEnabled) return "Kveiktu á Bluetooth"
        // Án kveiktrar staðsetningar skilar BLE skönnun engu, þó hún „takist“
        if (location?.isLocationEnabled == false) return "Kveiktu á staðsetningu (Location)"
        val scanner = a.bluetoothLeScanner ?: return "BLE skanni ekki tiltækur"
        // Android hunsar hljóðlaust skannanir sem eru ræstar oftar en 5× á 30 s
        val now = SystemClock.elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > 30_000) startTimes.removeFirst()
        if (startTimes.size >= 4) return "Bíð eftir að Android leyfi nýja skönnun"
        val extended = a.isLeExtendedAdvertisingSupported
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .apply {
                if (extended) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        return try {
            scanner.startScan(null, settings, callback)
            startTimes.addLast(now)
            startedAtMs = now
            scanning = true
            window.clear(); recent.set(0)
            DebugLog.i("Skönnun ræst (extended=$extended, ${startTimes.size} ræsingar á 30 s)")
            null
        } catch (e: SecurityException) {
            DebugLog.w("startScan hafnað", e)
            "Vantar Bluetooth heimild"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stop(reason: String) {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (e: Exception) { DebugLog.w("stopScan", e) }
        scanning = false
        DebugLog.i("Skönnun stöðvuð: $reason")
    }

    companion object {
        private const val RESTART_AFTER_MS = 20 * 60_000L
        private const val SILENT_MS = 30_000L
        private const val FADE_GRACE_MS = 20_000L

        fun failName(code: Int) = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            5 -> "OUT_OF_HARDWARE_RESOURCES"
            6 -> "SCANNING_TOO_FREQUENTLY"
            else -> "óþekkt"
        }
    }
}
