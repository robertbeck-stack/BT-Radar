package dev.beck.bleradar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Einföld 3D vigurtegund. ARCore hnit: x hægri, y UPP (þyngdarafl), z aftur. Metrar. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun horizNorm() = sqrt(x * x + z * z)
}

/**
 * Ein RSSI mæling, merkt með staðsetningu símans þegar hún barst og hnitakerfi ([AppState.frame]).
 * [fwd]: í hvaða átt myndavélin vísaði (einingarvigur). Notandinn er fyrir aftan símann, svo út frá
 * honum má sjá hvort líkaminn var á milli símans og tækisins.
 */
data class Sample(val tNanos: Long, val pos: Vec3, val rssi: Int, val frame: Int = 0, val fwd: Vec3? = null)

/** Hólf: miðgildi RSSI allra mælinga sem tekin voru innan sama ~30 cm teningsins. */
data class BinPoint(val pos: Vec3, val rssi: Double, val count: Int)

data class Estimate(
    val pos: Vec3,
    /** Staðalfrávik (m) á hverjum ás, úr samdreifnifylki lausnarinnar. */
    val sigma: Vec3,
    /** Metinn RSSI í 1 m fjarlægð (dBm). */
    val txPower: Double,
    /** RMS leif (dB) — hversu vel líkanið passar. */
    val rmsDb: Double,
    val nBins: Int,
    /** Staðalfrávik staðsetninga símans á hverjum ás — hversu mikið þú hefur hreyft þig. */
    val spread: Vec3,
    /** Önnur lausn með næstum sama kostnaði fannst langt frá — t.d. spegilmynd yfir/undir. */
    val ambiguous: Boolean,
    val mirror: Vec3?,
    /** Metinn dempunarstuðull n (eða fasta gildið ef það er ekki metið). */
    val pathLossN: Double = 2.2,
    /** Hve stórt horn (°) í kringum tækið, séð ofan frá, mælingarnar ná yfir. */
    val coverageDeg: Double = 360.0,
    /** Hvert er best að ganga til að bæta matið (hinum megin við tækið), eða null. */
    val suggest: Vec3? = null,
    /** Hvaða af [SECTORS] áttum (séð ofan frá tækinu) hafa mælingar; hringurinn á gólfinu. */
    val sectors: BooleanArray? = null,
    /** Radíus hringsins (m): dæmigerð fjarlægð mælinga frá tækinu. */
    val ringR: Double = 1.5,
)

/**
 * Metur 3D staðsetningu BLE sendis út frá RSSI mælingum teknum á mörgum stöðum.
 *
 * Líkan (log-distance path loss):  RSSI = A − 10·n·log10(d)
 * Óþekktar stærðir: x, y, z sendis og A. n er fast (sjálfgefið 2.2) því það og A
 * eru illa aðgreinanleg. Með [fitN] er n líka metið, dregið að [pathLossN] með forþekkingu.
 *
 * Lausn: Levenberg–Marquardt með Huber vigtun (dregur úr áhrifum endurkasts/skugga),
 * ræst frá mörgum upphafspunktum til að finna tvíræðni (t.d. hæðar-spegilmynd þegar
 * allar mælingar eru í sömu hæð).
 */
class Localizer(
    private val binSize: Double = 0.3,
    private val pathLossN: Double = 2.2,
    private val minBins: Int = 8,
    private val maxPerBin: Int = 12,
    private val maxBins: Int = 3000,
    /**
     * Hvaða hlutfallsmark RSSI í hverju hólfi er notað (0.5 = miðgildi). Deyfing (líkami,
     * veggir, útslökknun) veikir bara merkið, svo hærra mark er nær beinu leiðinni.
     */
    private val binQuantile: Double = BIN_QUANTILE,
    /** Huber mörk (dB): leifar stærri en þetta fá minna vægi. */
    private val huberK: Double = HUBER_K,
    /**
     * Skuggar fylgja staðsetningu, svo hólf sem liggja þétt saman bera sömu villuna. Ef > 0 fær
     * hvert svæði af þessari stærð (m) samanlagt sama vægi, hversu mörg hólf eru í því
     * (ódýr nálgun á líkindi með fylgnum skuggum; Vaghefi & Buehrer 2013).
     */
    private val decorrelateM: Double = DECORRELATE_M,
    /**
     * Forsenda um sendistyrk A (dBm á 1 m): meðaltal og staðalfrávik, eða null. Án hennar getur
     * formúlan skýrt sterkt merki sem „öflugt tæki langt í burtu“ (lota 15:30: sterk „Tölva 2“
     * sett 9.8 m í burtu úti á bílastæði, veik „Tölva 51“ sett 2.6 m frá).
     */
    private val txPrior: Pair<Double, Double>? = TX_PRIOR,
    /**
     * Einhliða: ef sett, refsar forsendan bara sendistyrk YFIR meðaltalinu. Veikt merki getur
     * þýtt fjarlægð EÐA veikan sendi (turnar undir borði), svo lágt A má standa óhreyft.
     */
    private val txPriorUpperOnly: Boolean = TX_PRIOR_UPPER_ONLY,
    /** Sjá [PRIOR_W]: hermun sýndi engan áreiðanlegan ávinning, svo sjálfgefið er fast n. */
    private val fitN: Boolean = false,
    private val priorWeight: Double = PRIOR_W,
    private val heightPriorScale: Double = HEIGHT_PRIOR_SCALE,
    private val txPriorScale: Double = TX_PRIOR_SCALE,
) {
    private data class Key(val ix: Int, val iy: Int, val iz: Int)

    private class Bin {
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        val rssi = ArrayDeque<Int>()
    }

    private val bins = LinkedHashMap<Key, Bin>()

    @Synchronized
    fun add(s: Sample) {
        val k = Key(
            floor(s.pos.x / binSize).toInt(),
            floor(s.pos.y / binSize).toInt(),
            floor(s.pos.z / binSize).toInt(),
        )
        val b = bins.getOrPut(k) { Bin() }
        b.sx += s.pos.x; b.sy += s.pos.y; b.sz += s.pos.z; b.n++
        b.rssi.addLast(s.rssi)
        while (b.rssi.size > maxPerBin) b.rssi.removeFirst()
        if (bins.size > maxBins) bins.remove(bins.keys.first())
    }

    @Synchronized
    fun clear() = bins.clear()

    @Synchronized
    fun binCount() = bins.size

    @Synchronized
    fun binsSnapshot(): List<BinPoint> = bins.values.map { b ->
        val sorted = b.rssi.sorted()
        // Hlutfallsmark með línulegri brúun (0.5 = miðgildi)
        val pos = binQuantile * (sorted.size - 1)
        val lo = sorted[pos.toInt()]; val hi = sorted[minOf(pos.toInt() + 1, sorted.size - 1)]
        val q = lo + (hi - lo) * (pos - pos.toInt())
        BinPoint(Vec3(b.sx / b.n, b.sy / b.n, b.sz / b.n), q, b.rssi.size)
    }

    private class Fit(val pos: Vec3, val a: Double, val n: Double, val cost: Double, val sigma: Vec3, val rms: Double)

    /**
     * [floorY]: ef gólfið er þekkt er lausnin takmörkuð við gólf ≤ y ≤ gólf + [CEILING_M].
     * [height]: dæmigerð hæð tækis yfir gólfi og óvissa hennar (m), notuð sem veik forsenda.
     * Úr fjarlægð segir RSSI lítið um hæð, og án forsendu getur matið „flotið“ upp undir loft.
     */
    fun solve(floorY: Double? = null, height: Pair<Double, Double>? = null): Estimate? {
        val pts = binsSnapshot()
        val m = pts.size
        if (m < minBins) return null

        // Hreyfisvið símans
        val mx = pts.sumOf { it.pos.x } / m
        val my = pts.sumOf { it.pos.y } / m
        val mz = pts.sumOf { it.pos.z } / m
        val spread = Vec3(
            sqrt(pts.sumOf { (it.pos.x - mx).pow(2) } / m),
            sqrt(pts.sumOf { (it.pos.y - my).pow(2) } / m),
            sqrt(pts.sumOf { (it.pos.z - mz).pow(2) } / m),
        )
        if (spread.horizNorm() < 0.25 && spread.y < 0.25) return null

        val strongest = pts.maxBy { it.rssi }
        val d0 = 10.0.pow((-59.0 - strongest.rssi) / (10.0 * pathLossN)).coerceIn(0.5, 8.0)

        val dirs = listOf(
            Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0),
            Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0),
            Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0),
        )
        // Vegin miðja (línulegt afl) sem viðbótar upphafspunktur
        var wsum = 0.0; var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (p in pts) {
            val w = 10.0.pow(p.rssi / 10.0)
            wsum += w; cx += w * p.pos.x; cy += w * p.pos.y; cz += w * p.pos.z
        }
        val starts = (dirs.map { strongest.pos + it * d0 } + Vec3(cx / wsum, cy / wsum, cz / wsum))
            .map { if (floorY != null && it.y < floorY) Vec3(it.x, floorY, it.z) else it }
        val centroid = Vec3(mx, my, mz)

        // Fyrst án hæðarforsendu, til að sjá hve vel líkanið passar gögnunum (leif í dB)
        val free = starts.mapNotNull { lm(pts, it, centroid, floorY, null, 0.0, null, 0.0) }
        val prior = if (floorY != null && height != null) (floorY + height.first) to height.second else null
        val fits = if ((prior == null && txPrior == null) || free.isEmpty()) free else {
            // Vægi forsendna miðað við raunverulega leif: hrein gögn ráða, en hávaðasöm eða
            // óljós gögn falla að dæmigerðri hæð og sendistyrk
            val rms = free.minBy { it.cost }.rms
            val wY = prior?.let { (rms / it.second).pow(2) * heightPriorScale } ?: 0.0
            val wA = txPrior?.let { (rms / it.second).pow(2) * txPriorScale } ?: 0.0
            // Aðeins frá lausnum sem koma til greina (hinar breytast ekki í bestu): ~3× hraðara
            val bestCost = free.minOf { it.cost }
            free.filter { it.cost <= bestCost * 1.5 }
                .mapNotNull { lm(pts, it.pos, centroid, floorY, prior, wY, txPrior?.first, wA) }
        }
        if (fits.isEmpty()) return null
        val best = fits.minBy { it.cost }

        val rival = fits.filter {
            it.cost <= best.cost * 1.05 + 1e-9 && (it.pos - best.pos).norm() > 1.0
        }.maxByOrNull { (it.pos - best.pos).norm() }

        val cov = coverage(pts, best.pos)
        return Estimate(
            pos = best.pos,
            sigma = best.sigma,
            txPower = best.a,
            rmsDb = best.rms,
            nBins = m,
            spread = spread,
            ambiguous = rival != null,
            mirror = rival?.pos,
            pathLossN = best.n,
            coverageDeg = cov.deg,
            suggest = cov.suggest,
            sectors = cov.sectors,
            ringR = cov.ringR,
        )
    }

    /**
     * Skekkjan er 3–4× minni þegar gengið hefur verið í kringum tækið en þegar allar mælingar
     * eru öðrum megin við það (hermun). Finnur stærsta óhulda hornbilið, séð ofan frá tækinu,
     * og stingur upp á stað í miðju þess.
     */
    private class Coverage(val deg: Double, val suggest: Vec3?, val sectors: BooleanArray?, val ringR: Double)

    private fun coverage(pts: List<BinPoint>, c: Vec3): Coverage {
        val near = pts.filter { (it.pos.x - c.x).pow(2) + (it.pos.z - c.z).pow(2) >= 0.09 }
        val angles = near.map { atan2(it.pos.z - c.z, it.pos.x - c.x) }.sorted()
        if (angles.size < 3) return Coverage(0.0, null, null, 1.5)
        val sectors = BooleanArray(SECTORS)
        for (a in angles) sectors[(((a + PI) / (2 * PI)) * SECTORS).toInt().coerceIn(0, SECTORS - 1)] = true
        val ringR = near.map { sqrt((it.pos.x - c.x).pow(2) + (it.pos.z - c.z).pow(2)) }.sorted()[near.size / 2]
            .coerceIn(1.0, 3.0)
        var gap = angles.first() + 2 * PI - angles.last()
        var gapStart = angles.last()
        for (i in 1 until angles.size) {
            val g = angles[i] - angles[i - 1]
            if (g > gap) { gap = g; gapStart = angles[i - 1] }
        }
        val coverageDeg = Math.toDegrees(2 * PI - gap)
        if (gap < GAP_SUGGEST) return Coverage(coverageDeg, null, sectors, ringR)
        val mid = gapStart + gap / 2
        val y = near.sumOf { it.pos.y } / near.size
        return Coverage(coverageDeg, Vec3(c.x + ringR * cos(mid), y, c.z + ringR * sin(mid)), sectors, ringR)
    }

    private fun lm(
        pts: List<BinPoint>, p0: Vec3, centroid: Vec3, floorY: Double?, yPrior: Pair<Double, Double>?, wY: Double,
        aPrior: Double?, wA: Double,
    ): Fit? {
        val m = pts.size
        val np = if (fitN) 5 else 4
        val k10 = 10.0 / ln(10.0)
        val w0 = DoubleArray(m) { min(pts[it].count, 6) / 6.0 }
        if (decorrelateM > 0) {
            val cell = { q: Vec3 -> Triple(floor(q.x / decorrelateM).toInt(), floor(q.y / decorrelateM).toInt(),
                floor(q.z / decorrelateM).toInt()) }
            val counts = HashMap<Triple<Int, Int, Int>, Int>()
            for (pt in pts) counts.merge(cell(pt.pos), 1, Int::plus)
            // Meðalvægi haldið óbreyttu svo Huber mörk og σ haldist sambærileg
            val raw = DoubleArray(m) { w0[it] / counts.getValue(cell(pts[it].pos)) }
            val scale = w0.sum() / raw.sum()
            for (i in 0 until m) w0[i] = raw[i] * scale
        }

        fun lnDist(px: Double, py: Double, pz: Double, i: Int): Double {
            val q = pts[i].pos
            val d2 = (px - q.x).pow(2) + (py - q.y).pow(2) + (pz - q.z).pow(2)
            return 0.5 * ln(max(d2, 0.04))
        }

        fun fitA(px: Double, py: Double, pz: Double, n: Double): Double {
            var s = 0.0; var ws = 0.0
            for (i in 0 until m) { s += w0[i] * (pts[i].rssi + k10 * n * lnDist(px, py, pz, i)); ws += w0[i] }
            return s / ws
        }

        fun robustCost(px: Double, py: Double, pz: Double, a: Double, n: Double): Double {
            var cost = 0.0
            for (i in 0 until m) {
                val e = abs(pts[i].rssi - (a - k10 * n * lnDist(px, py, pz, i)))
                cost += w0[i] * if (e <= huberK) 0.5 * e * e else huberK * e - 0.5 * huberK * huberK
            }
            if (fitN) cost += 0.5 * priorWeight * (n - pathLossN).pow(2)
            if (yPrior != null) cost += 0.5 * wY * (py - yPrior.first).pow(2)
            if (aPrior != null && (!txPriorUpperOnly || a > aPrior)) cost += 0.5 * wA * (a - aPrior).pow(2)
            return cost
        }

        var x = p0.x; var y = p0.y; var z = p0.z
        var n = pathLossN
        var a = fitA(x, y, z, n)
        var cost = robustCost(x, y, z, a, n)
        var lambda = 1e-2
        val h = DoubleArray(np * np)
        val g = DoubleArray(np)
        val jj = DoubleArray(5)

        fun buildNormal() {
            h.fill(0.0); g.fill(0.0)
            val c = k10 * n
            for (i in 0 until m) {
                val q = pts[i].pos
                val dx = x - q.x; val dy = y - q.y; val dz = z - q.z
                val d2 = max(dx * dx + dy * dy + dz * dz, 0.04)
                val lnd = 0.5 * ln(d2)
                val e = pts[i].rssi - (a - c * lnd)
                val w = w0[i] * if (abs(e) <= huberK) 1.0 else huberK / abs(e)
                jj[0] = -c * dx / d2; jj[1] = -c * dy / d2; jj[2] = -c * dz / d2; jj[3] = 1.0; jj[4] = -k10 * lnd
                for (r in 0 until np) {
                    g[r] += w * jj[r] * e
                    for (k in 0 until np) h[r * np + k] += w * jj[r] * jj[k]
                }
            }
            if (yPrior != null) {
                // Forsenda um hæð sem gervimæling á y
                h[1 * np + 1] += wY
                g[1] += wY * (yPrior.first - y)
            }
            if (aPrior != null && (!txPriorUpperOnly || a > aPrior)) {
                // Forsenda um sendistyrk sem gervimæling á A
                h[3 * np + 3] += wA
                g[3] += wA * (aPrior - a)
            }
            if (fitN) {
                // Forþekking á n sem gervimæling
                h[4 * np + 4] += priorWeight
                g[4] += priorWeight * (pathLossN - n)
            }
        }

        for (iter in 0 until 80) {
            buildNormal()
            val hd = h.copyOf()
            for (k in 0 until np) hd[k * np + k] = hd[k * np + k] * (1 + lambda) + 1e-9
            val delta = solveN(hd, g, np) ?: break
            val nx = x + delta[0]; val nz = z + delta[2]; val na = a + delta[3]
            // Varpað upp á gólfið ef skrefið fer niður fyrir það
            val ny = (y + delta[1]).let { if (floorY != null) it.coerceIn(floorY, floorY + CEILING_M) else it }
            val nn = if (fitN) (n + delta[4]).coerceIn(N_MIN, N_MAX) else n
            val nc = robustCost(nx, ny, nz, na, nn)
            if (nc < cost) {
                x = nx; y = ny; z = nz; a = na; n = nn; cost = nc
                lambda = max(lambda / 3, 1e-7)
                if (sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]) < 1e-4) break
            } else {
                lambda *= 4
                if (lambda > 1e7) break
            }
        }

        val pos = Vec3(x, y, z)
        if ((pos - centroid).norm() > 40.0) return null

        // Samdreifni: σ² (JᵀWJ)⁻¹
        buildNormal()
        var sse = 0.0; var ws = 0.0
        for (i in 0 until m) {
            val e = pts[i].rssi - (a - k10 * n * lnDist(x, y, z, i))
            sse += w0[i] * e * e; ws += w0[i]
        }
        val dof = max(1, m - np)
        // Leifarnar eru ekki óháðar (endurkast/skuggi fylgja staðsetningu), svo hrátt σ er of
        // bjartsýnt. Hermun með fylgnandi deyfingu sýndi raunskekkju ~3–5× hrátt σ.
        val s2 = sse / dof * SIGMA_INFLATION * SIGMA_INFLATION
        val inv = invertN(h, np)
        val sigma = if (inv == null) Vec3(99.0, 99.0, 99.0) else Vec3(
            sqrt(max(0.0, inv[0] * s2)).coerceAtMost(99.0),
            sqrt(max(0.0, inv[np + 1] * s2)).coerceAtMost(99.0),
            sqrt(max(0.0, inv[2 * np + 2] * s2)).coerceAtMost(99.0),
        )
        return Fit(pos, a, n, cost, sigma, sqrt(sse / ws))
    }

    companion object {
        const val SIGMA_INFLATION = 3.0

        /**
         * Styrkur forþekkingar á n þegar [fitN] er notað. Í hermun (PathLossComparisonTest,
         * fylgnandi skuggi, raun-n 2.0–3.2) var munurinn á metnu og föstu n innan ±10% og
         * breytilegur eftir uppsetningu: lögun gönguleiðar skiptir margfalt meira máli.
         */
        const val PRIOR_W = 100.0

        /**
         * Ef stærsta óhulda hornbil í kringum tækið er stærra en þetta er stungið upp á göngu.
         * 120° = þekja undir 240°: í hermun (300 lotur) var lárétt skekkja 1.80 m undir 90° þekju,
         * 0.75 m við 150–210° og 0.39–0.45 m yfir 210°.
         */
        val GAP_SUGGEST = 120.0 * PI / 180

        /** Fjöldi hluta í hringnum á gólfinu (30° hver). */
        const val SECTORS = 12
        const val N_MIN = 1.6

        /*
         * Prófað okt. 2026 (LocalizerBenchmarkTest, 150 hermdar lotur pöruð, + 2 raunlotur með
         * merktum raunstað): hlutfallsmark 0.9 (hámarkssía, IPIN 2022), svæðisvigtun 0.6 m
         * (fylgnir skuggar, Vaghefi & Buehrer 2013) og Huber 3/8 gáfu öll breytingu innan
         * skekkjumarka (−0.04…+0.01 ± 0.03 m) og hjálpuðu annarri raunlotunni en skemmdu hina.
         * Því óbreytt; prófa aftur þegar fleiri raunstaðir eru til.
         */
        const val BIN_QUANTILE = 0.5
        const val HUBER_K = 5.0
        const val DECORRELATE_M = 0.0

        /**
         * Einhliða mörk á sendistyrk A (dBm á 1 m): refsað bara fyrir A > −50. Án þeirra var sterk
         * „Tölva 2“ (lota 15:30) metin með A = −43 og sett 4 m frá sterkasta staðnum; nú 3.1 m.
         * Vægi ×9 = SIGMA_INFLATION² (hólfin bera fylgna skugga). Tvíhliða forsenda −59 ± 8 var
         * prófuð og hafnað: hún ýtti tölvum með veikt merki 2× lengra í burtu (turnar undir borði
         * senda veikt), og ávinningurinn í hermun hvarf þegar hermdur sendistyrkur var raunhæfur
         * (−80…−50): −0.03 ± 0.05 m. Raunstaðir óbreyttir með einhliða mörkum (0.67 / 0.65 m).
         */
        val TX_PRIOR: Pair<Double, Double>? = -50.0 to 8.0
        const val TX_PRIOR_SCALE = 9.0
        const val TX_PRIOR_UPPER_ONLY = true

        /** Margfaldari á vægi hæðarforsendu (1 = hreint Bayes miðað við leif). Stillt með hermun. */
        const val HEIGHT_PRIOR_SCALE = 1.0

        /** Tæki eru ekki hærra en þetta yfir gólfi (m). */
        const val CEILING_M = 3.0

        const val N_MAX = 4.5

        /** Leysir n×n jöfnuhneppi (raðað í röð) með Gauss-eyðingu og hlutvali. */
        fun solveN(aIn: DoubleArray, bIn: DoubleArray, n: Int): DoubleArray? {
            val a = aIn.copyOf(); val b = bIn.copyOf()
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[piv * n + col])) piv = r
                if (abs(a[piv * n + col]) < 1e-15) return null
                if (piv != col) {
                    for (k in 0 until n) { val t = a[col * n + k]; a[col * n + k] = a[piv * n + k]; a[piv * n + k] = t }
                    val t = b[col]; b[col] = b[piv]; b[piv] = t
                }
                for (r in col + 1 until n) {
                    val f = a[r * n + col] / a[col * n + col]
                    for (k in col until n) a[r * n + k] -= f * a[col * n + k]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (k in r + 1 until n) s -= a[r * n + k] * x[k]
                x[r] = s / a[r * n + r]
            }
            return x
        }

        fun invertN(a: DoubleArray, n: Int): DoubleArray? {
            val out = DoubleArray(n * n)
            for (c in 0 until n) {
                val e = DoubleArray(n); e[c] = 1.0
                val col = solveN(a, e, n) ?: return null
                for (r in 0 until n) out[r * n + c] = col[r]
            }
            return out
        }
    }
}
