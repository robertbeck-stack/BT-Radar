package dev.beck.bleradar

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** [seq] er stutt raðnúmer sem kemur í stað MAC vistfangs í nafni tækja sem senda ekki nafn. */
class Track(val address: String, val seq: Int) {
    @Volatile var name: String? = null
    @Volatile var lastRssi: Int = -127
    /** Útjafnað RSSI (veldisvísis-meðaltal), stöðugra en [lastRssi] til röðunar og síu. */
    @Volatile var avgRssi: Double = -127.0
    @Volatile var lastSeenNanos: Long = 0L
    @Volatile var estimate: Estimate? = null
    /** Raunstaður sem notandinn merkti („Hér er tækið“), til að mæla skekkju. */
    @Volatile var truth: Vec3? = null
    @Volatile var kind: DeviceKind = DeviceKind.UNKNOWN
    @Volatile var kindScore: Int = 0
    @Volatile var vendor: String? = null
    @Volatile var totalAdverts: Int = 0
    @Volatile var lastSolvedNanos: Long = 0L
    val localizer = Localizer()
    private val raw = ArrayList<Sample>()

    @Synchronized fun addRaw(s: Sample) { if (raw.size < 50_000) raw.add(s) }
    @Synchronized fun rawCopy(): List<Sample> = ArrayList(raw)
    @Synchronized fun clear() { raw.clear(); localizer.clear(); estimate = null; totalAdverts = 0 }

    /** Nafn tækisins, eða t.d. „Tölva 3“ / „Tæki 7“ ef það sendir ekkert nafn. */
    fun label() = name?.takeIf { it.isNotBlank() } ?: when {
        kind == DeviceKind.UNKNOWN -> "Tæki $seq"
        kindScore <= 1 -> "${kind.label}? $seq"
        else -> "${kind.label} $seq"
    }

    /** Það sem [label] segir ekki nú þegar: tegund og/eða framleiðandi. */
    fun detailText(): String? = if (name.isNullOrBlank()) vendor else kindText()

    /**
     * Tekur við nýju mati án þess að missa það sem komið er: ef nýja matið er ekkert (t.d.
     * of fá gögn í bili) eða töluvert óvissara en það gamla, heldur það gamla. Þannig stækkar
     * bólan ekki þó þú gangir frá tækinu og hverfur ekki þó það hætti að senda í smá stund.
     */
    fun updateEstimate(fresh: Estimate?) {
        val old = estimate
        estimate = when {
            fresh == null -> old
            old == null -> fresh
            fresh.uncertainty() <= old.uncertainty() * 1.25 -> fresh
            else -> old
        }
    }

    /** Tekur við nýrri flokkun ef hún er öruggari en sú sem fyrir er. */
    fun applyGuess(g: KindGuess) {
        if (g.score > kindScore) { kind = g.kind; kindScore = g.score }
        if (vendor == null && g.vendor != null) vendor = g.vendor
    }

    /** T.d. „Heyrnartól (Apple)“ eða „Sími? (Apple)“ ef það er bara ágiskun. */
    fun kindText(): String {
        val v = vendor?.let { " ($it)" } ?: ""
        return when {
            kind == DeviceKind.UNKNOWN -> vendor ?: kind.label
            kindScore <= 1 -> "${kind.label}?$v"
            else -> kind.label + v
        }
    }
}

class TrackStore {
    private val map = ConcurrentHashMap<String, Track>()
    private val nextSeq = AtomicInteger(1)
    fun get(addr: String): Track = map.computeIfAbsent(addr) { Track(it, nextSeq.getAndIncrement()) }
    fun find(addr: String): Track? = map[addr]
    fun remove(addr: String) { map.remove(addr) }
    fun all(): List<Track> = map.values.toList()
    fun clear() { map.clear(); nextSeq.set(1) }
}

/**
 * Geymir síðustu ~3 sek af staðsetningum símans svo hægt sé að tengja hverja BLE
 * mælingu við staðsetninguna á þeirri stundu sem hún barst (ekki bara nýjasta ramma).
 */
class PoseHistory(private val capacity: Int = 200) {
    private val t = LongArray(capacity)
    private val p = arrayOfNulls<Vec3>(capacity)
    private val f = arrayOfNulls<Vec3>(capacity)
    private var head = 0
    private var size = 0

    @Synchronized fun add(tNanos: Long, pos: Vec3, fwd: Vec3? = null) {
        t[head] = tNanos; p[head] = pos; f[head] = fwd
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized fun clear() { size = 0; head = 0 }

    /** Stefna myndavélar á næstu staðsetningu á undan [tNanos] (eða þeirri fyrstu eftir). */
    @Synchronized fun forwardAt(tNanos: Long): Vec3? {
        var last: Int = -1
        for (k in 0 until size) {
            val i = (head - 1 - k + capacity) % capacity
            last = i
            if (t[i] <= tNanos) break
        }
        return if (last >= 0) f[last] else null
    }

    companion object {
        const val MAX_AHEAD = 500_000_000L
    }

    /**
     * Brúar á milli tveggja nálægustu staðsetninga. Ef mælingin er nýrri en síðasta staðsetning
     * (myndavélarrammar berast með töf, sérstaklega með dýptarkorti) er framreiknað með hraða
     * símans, mest [MAX_AHEAD] fram í tímann. Áður var slíkum mælingum hent (~50% í einni lotu).
     */
    @Synchronized fun at(tNanos: Long): Vec3? {
        if (size == 0) return null
        var before = -1; var after = -1
        for (k in 0 until size) {
            val i = (head - 1 - k + capacity) % capacity
            if (t[i] <= tNanos) { before = i; break }
            after = i
        }
        val maxGap = 150_000_000L
        return when {
            before >= 0 && after >= 0 -> {
                val span = (t[after] - t[before]).toDouble()
                if (span > 2 * maxGap) return null
                val f = if (span <= 0) 0.0 else (tNanos - t[before]) / span
                p[before]!! + (p[after]!! - p[before]!!) * f
            }
            before >= 0 -> {
                val ahead = tNanos - t[before]
                if (ahead > MAX_AHEAD) return null
                // Hraði út frá tveimur síðustu staðsetningum; framreiknað mest 200 ms
                val prev = (before - 1 + capacity) % capacity
                if (size < 2 || t[before] - t[prev] <= 0 || t[before] - t[prev] > 2 * maxGap) return p[before]
                val v = (p[before]!! - p[prev]!!) * (1.0 / (t[before] - t[prev]))
                p[before]!! + v * minOf(ahead, 200_000_000L).toDouble()
            }
            after >= 0 -> if (t[after] - tNanos <= maxGap) p[after] else null
            else -> null
        }
    }
}
