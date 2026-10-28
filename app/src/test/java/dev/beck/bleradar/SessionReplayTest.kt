package dev.beck.bleradar

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Endurspilar upptöku úr símanum (Vista → ble3d_<tími>.csv + _session.txt) í gegnum sama
 * Localizer og appið notar, og ber saman við raunstaði sem merktir voru með 📍.
 *
 *   ./gradlew testDebugUnitTest --tests "*SessionReplay*" --rerun -Psession=/slóð/á/ble3d_..._session.txt
 *
 * Skrifar skýrslu í stdout og myndir ofan frá + frá hlið í app/build/replay/.
 * Sleppt ef engin upptaka er gefin.
 */
class SessionReplayTest {

    private class Row(val t: Long, val addr: String, val pos: Vec3, val rssi: Int, val frame: Int, val fwd: Vec3?)
    private class Device(val addr: String, val kind: DeviceKind, val label: String)
    private class Est(val t: Long, val pos: Vec3, val sigma: Vec3)

    @Test
    fun replay() {
        val path = System.getProperty("ble3d.session").orEmpty()
        assumeTrue("engin upptaka gefin (-Psession=...)", path.isNotEmpty())
        val session = File(path)
        val csv = File(session.path.replace("_session.txt", ".csv"))

        val rows = csv.readLines().drop(1).mapNotNull { l ->
            val c = l.split(',')
            if (c.size < 8) null else Row(c[0].toLong(), c[1], Vec3(c[3].toDouble(), c[4].toDouble(), c[5].toDouble()),
                c[6].toInt(), c[7].toInt(),
                if (c.size >= 11 && c[8].isNotEmpty()) Vec3(c[8].toDouble(), c[9].toDouble(), c[10].toDouble()) else null)
        }
        val devices = HashMap<String, Device>()
        val floors = HashMap<Int, Double>()
        val truths = HashMap<String, Pair<Int, Vec3>>()
        val appEst = HashMap<String, MutableList<Est>>()
        val poses = HashMap<Int, MutableList<Vec3>>()
        for (l in session.readLines()) {
            val c = l.split(',')
            when (c[0]) {
                "device" -> devices[c[1]] = Device(c[1], runCatching { DeviceKind.valueOf(c[2]) }
                    .getOrDefault(DeviceKind.UNKNOWN), c.getOrElse(6) { c[1] }.ifBlank { c[1] })
                "floor" -> floors.merge(c[2].toInt(), c[3].toDouble(), ::min)
                "truth" -> truths[c[3]] = c[2].toInt() to Vec3(c[4].toDouble(), c[5].toDouble(), c[6].toDouble())
                "est" -> appEst.getOrPut(c[3]) { ArrayList() } += Est(c[1].toLong(),
                    Vec3(c[4].toDouble(), c[5].toDouble(), c[6].toDouble()), Vec3(c[7].toDouble(), c[8].toDouble(), c[9].toDouble()))
                "pose" -> poses.getOrPut(c[2].toInt()) { ArrayList() } += Vec3(c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
            }
        }
        println("Upptaka: ${rows.size} mælingar, ${devices.size} tæki, ${truths.size} raunstaðir, gólf: $floors")

        // Tæki með raunstað; annars þau 5 sem appið mat með flestar mælingar
        val targets = truths.keys.toList().ifEmpty {
            appEst.keys.sortedByDescending { a -> rows.count { it.addr == a } }.take(10)
        }
        val outDir = File("build/replay").apply { mkdirs() }
        println(String.format(Locale.ROOT, "%-22s %-11s %6s | %22s | %8s %8s %8s | %8s",
            "tæki", "tegund", "mæl.", "skekkja 25/50/75/100%", "án hæðar", "án gólfs", "appið", "σ lár."))
        val summary = ArrayList<Double>()
        for (addr in targets) {
            val dev = devices[addr] ?: Device(addr, DeviceKind.UNKNOWN, addr)
            val frame = truths[addr]?.first ?: rows.lastOrNull { it.addr == addr }?.frame ?: 0
            val samples = rows.filter { it.addr == addr && it.frame == frame }.sortedBy { it.t }
            if (samples.size < 50) continue
            val floor = floors[frame]
            val truth = truths[addr]?.second
            val prior = dev.kind.height to dev.kind.heightSd

            fun solveUpTo(frac: Double, f: Double? = floor, h: Pair<Double, Double>? = prior): Estimate? {
                val loc = Localizer()
                for (s in samples.take((samples.size * frac).toInt())) loc.add(Sample(s.t, s.pos, s.rssi, s.frame))
                return loc.solve(f, h)
            }
            val full = solveUpTo(1.0)
            fun err(e: Estimate?) = if (e == null || truth == null) "    –   " else
                String.format(Locale.ROOT, "%5.2f m ", (e.pos - truth).norm())
            val prog = listOf(0.25, 0.5, 0.75, 1.0).joinToString("/") { fr ->
                val e = if (fr == 1.0) full else solveUpTo(fr)
                if (e == null || truth == null) "–" else String.format(Locale.ROOT, "%.2f", (e.pos - truth).norm())
            }
            val app = appEst[addr]?.lastOrNull()
            val appErr = if (app == null || truth == null) "    –   " else
                String.format(Locale.ROOT, "%5.2f m ", (app.pos - truth).norm())
            println(String.format(Locale.ROOT, "%-22s %-11s %6d | %22s | %s %s %s | %6.2f m",
                dev.label.take(22), dev.kind.label.take(11), samples.size, prog,
                err(solveUpTo(1.0, h = null)), err(solveUpTo(1.0, f = null, h = null)), appErr, full?.horizSigma() ?: Double.NaN))
            if (full != null && truth != null) {
                summary += (full.pos - truth).norm()
                val inside = (full.pos - truth).horizNorm() <= full.horizSigma()
                println(String.format(Locale.ROOT, "    mat (%.2f, %.2f, %.2f)  raun (%.2f, %.2f, %.2f)  lárétt %.2f m  hæð %+.2f m  " +
                    "raunstaður innan ±σ: %s", full.pos.x, full.pos.y, full.pos.z, truth.x, truth.y, truth.z,
                    (full.pos - truth).horizNorm(), full.pos.y - truth.y, if (inside) "já" else "NEI"))
            }
            if (truth != null) {
                // Afbrigði formúlunnar á sömu gögnum
                for ((name, make) in listOf<Pair<String, () -> Localizer>>(
                    "án sendistyrksforsendu" to { Localizer(txPrior = null) },
                    "tvíhliða −59 ± 8, vægi ×9" to { Localizer(txPrior = -59.0 to 8.0, txPriorScale = 9.0) },
                    "einhliða > −50, vægi ×9" to { Localizer(txPrior = -50.0 to 8.0, txPriorScale = 9.0, txPriorUpperOnly = true) },
                )) {
                    val loc = make()
                    for (s in samples) loc.add(Sample(s.t, s.pos, s.rssi, s.frame))
                    val e = loc.solve(floor, prior)
                    println(String.format(Locale.ROOT, "    afbrigði %-22s %s", name,
                        e?.let { String.format(Locale.ROOT, "%.2f m (lárétt %.2f, hæð %+.2f)",
                            (it.pos - truth).norm(), (it.pos - truth).horizNorm(), it.pos.y - truth.y) } ?: "–"))
                }
                orientationReport(samples, truth)
            } else {
                // Enginn raunstaður: hve langt er matið frá staðnum þar sem merkið var sterkast?
                val top = samples.sortedByDescending { it.rssi }.take((samples.size / 20).coerceAtLeast(5))
                val hot = Vec3(top.map { it.pos.x }.average(), top.map { it.pos.y }.average(), top.map { it.pos.z }.average())
                val strongest = top.map { it.rssi }.sorted()[top.size / 2]
                for ((name, make) in listOf<Pair<String, () -> Localizer>>(
                    "án sendistyrksforsendu" to { Localizer(txPrior = null) },
                    "tvíhliða −59 ± 8, vægi ×9" to { Localizer(txPrior = -59.0 to 8.0, txPriorScale = 9.0) },
                    "einhliða > −50, vægi ×9" to { Localizer(txPrior = -50.0 to 8.0, txPriorScale = 9.0, txPriorUpperOnly = true) },
                )) {
                    val loc = make()
                    for (s in samples) loc.add(Sample(s.t, s.pos, s.rssi, s.frame))
                    val e = loc.solve(floor, prior)
                    println(String.format(Locale.ROOT, "    %-22s sterkasta 5%%: %d dBm · mat %s frá sterkasta staðnum, A = %s",
                        name, strongest, e?.let { String.format(Locale.ROOT, "%.1f m", (it.pos - hot).horizNorm()) } ?: "–",
                        e?.let { String.format(Locale.ROOT, "%.0f dBm", it.txPower) } ?: "–"))
                }
            }
            plot(File(outDir, "${dev.label.replace(Regex("[^A-Za-z0-9ÁÐÉÍÓÚÝÞÆÖáðéíóúýþæö_-]"), "_")}.png"),
                dev.label, samples, poses[frame].orEmpty(), floor, full, app, truth)
        }
        if (summary.isNotEmpty()) println(String.format(Locale.ROOT, "Miðgildi 3D skekkju: %.2f m (%d tæki)",
            summary.sorted()[summary.size / 2], summary.size))
        println("Myndir: ${outDir.absolutePath}")
    }

    /**
     * Er merkið kerfisbundið veikara þegar tækið er fyrir aftan notandann (líkaminn á milli)?
     * Notar RÉTTAR fjarlægðir frá raunstað: leif = RSSI − (A − 10·n·log10(d)), flokkað eftir
     * horni milli stefnu myndavélar og stefnu að tækinu (lárétt). Sýnir hve mikið hávaðinn
     * myndi minnka ef leiðrétt væri fyrir þessu, áður en það er sett í appið.
     */
    private fun orientationReport(samples: List<Row>, truth: Vec3) {
        val withFwd = samples.filter { it.fwd != null }
        if (withFwd.size < 50) { println("    (engin stefna myndavélar í þessari upptöku)"); return }
        val xs = withFwd.map { -10 * kotlin.math.log10((truth - it.pos).norm().coerceAtLeast(0.1)) }
        val ys = withFwd.map { it.rssi.toDouble() }
        val mx = xs.average(); val my = ys.average()
        val n = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx) * (it - mx) }
        val a = my - n * mx
        val res = xs.indices.map { ys[it] - (a + n * xs[it]) }
        // cos horns (lárétt): 1 = tækið beint fyrir framan símann, −1 = beint fyrir aftan (líkaminn á milli)
        val cos = withFwd.map { r ->
            val to = truth - r.pos; val fw = r.fwd!!
            val h1 = kotlin.math.hypot(to.x, to.z); val h2 = kotlin.math.hypot(fw.x, fw.z)
            if (h1 < 1e-6 || h2 < 1e-6) 0.0 else (to.x * fw.x + to.z * fw.z) / (h1 * h2)
        }
        val buckets = listOf(-1.0 to -0.5, -0.5 to 0.0, 0.0 to 0.5, 0.5 to 1.01)
        val names = listOf("fyrir aftan", "aftan/hlið", "framan/hlið", "fyrir framan")
        val means = buckets.map { (lo, hi) -> res.indices.filter { cos[it] >= lo && cos[it] < hi }.map { res[it] } }
        val rms0 = kotlin.math.sqrt(res.sumOf { it * it } / res.size)
        val corrected = res.indices.map { i ->
            val b = buckets.indexOfFirst { (lo, hi) -> cos[i] >= lo && cos[i] < hi }
            res[i] - (means[b].takeIf { it.isNotEmpty() }?.average() ?: 0.0)
        }
        val rms1 = kotlin.math.sqrt(corrected.sumOf { it * it } / corrected.size)
        println(String.format(Locale.ROOT, "    stefna símans (n=%.2f, hávaði %.1f dB):", n, rms0))
        for (i in buckets.indices) if (means[i].isNotEmpty()) println(String.format(Locale.ROOT,
            "      tækið %-13s n=%4d  meðalfrávik %+5.1f dB", names[i], means[i].size, means[i].average()))
        println(String.format(Locale.ROOT, "      → hávaði eftir leiðréttingu: %.1f dB (var %.1f)", rms1, rms0))
    }

    /**
     * Efri hluti: ofan frá (x, z). Neðri hluti: frá hlið (lárétt fjarlægð frá raunstað/mati, hæð).
     * Grá lína = gönguleið, punktar = mælingar litaðar eftir RSSI (blátt veikt → rautt sterkt),
     * appelsínugult = mat appsins, rautt + hringur = endurspilun ±σ, grænt × = raunstaður,
     * grænn hringur = „gakktu hingað“. Neðri hluti: grá lína = gólf, efri strik = 1 m bil.
     */
    private fun plot(
        f: File, @Suppress("UNUSED_PARAMETER") title: String, samples: List<Row>, poses: List<Vec3>, floor: Double?,
        est: Estimate?, app: Est?, truth: Vec3?,
    ) {
        val pts = samples.map { it.pos } + poses + listOfNotNull(est?.pos, app?.pos, truth)
        val minX = pts.minOf { it.x } - 0.5; val maxX = pts.maxOf { it.x } + 0.5
        val minZ = pts.minOf { it.z } - 0.5; val maxZ = pts.maxOf { it.z } + 0.5
        val size = 700; val side = 260
        val scale = min(size / (maxX - minX), size / (maxZ - minZ))
        val img = SimplePng(size + 20, size + side + 40)
        fun px(x: Double) = (10 + (x - minX) * scale).toInt()
        fun pz(z: Double) = (10 + (z - minZ) * scale).toInt()
        val grey = SimplePng.rgb(200, 200, 200)
        val orange = SimplePng.rgb(255, 150, 0)
        val red = SimplePng.rgb(220, 40, 40)
        val green = SimplePng.rgb(0, 150, 60)

        // 1 m kvarði efst til vinstri
        img.line(10, 4, 10 + scale.toInt(), 4, SimplePng.rgb(0, 0, 0), 3)
        for (i in 1 until poses.size) img.line(px(poses[i - 1].x), pz(poses[i - 1].z), px(poses[i].x), pz(poses[i].z), grey)
        for (s in samples) img.fillCircle(px(s.pos.x), pz(s.pos.z), 2, SimplePng.rssiColor(s.rssi))
        app?.let { img.fillCircle(px(it.pos.x), pz(it.pos.z), 8, orange) }
        est?.let { e ->
            img.circle(px(e.pos.x), pz(e.pos.z), (e.horizSigma() * scale).toInt().coerceAtLeast(4), red)
            img.fillCircle(px(e.pos.x), pz(e.pos.z), 7, red)
            e.suggest?.let { g -> img.circle(px(g.x), pz(g.z), 10, SimplePng.rgb(60, 180, 90), 3) }
        }
        truth?.let { img.cross(px(it.x), pz(it.z), 10, green) }

        val ref = truth ?: est?.pos
        if (ref != null) {
            val top = size + 30
            img.fillRect(0, top - 6, img.width - 1, top - 5, SimplePng.rgb(0, 0, 0))
            val baseY = floor ?: pts.minOf { it.y }
            val maxD = samples.maxOf { (it.pos - ref).horizNorm() } + 0.5
            val maxH = 3.0
            fun sx(dist: Double) = (10 + dist / maxD * size).toInt()
            fun sy(y: Double) = (top + side - 10 - (y - baseY) / maxH * (side - 30)).toInt()
            for (m in 0..3) img.line(sx(0.0), sy(baseY + m), sx(maxD), sy(baseY + m), if (m == 0) grey else SimplePng.rgb(235, 235, 235))
            for (s in samples) img.fillCircle(sx((s.pos - ref).horizNorm()), sy(s.pos.y), 2, SimplePng.rssiColor(s.rssi))
            app?.let { img.fillCircle(sx((it.pos - ref).horizNorm()), sy(it.pos.y), 8, orange) }
            est?.let { img.fillCircle(sx((it.pos - ref).horizNorm()), sy(it.pos.y), 7, red) }
            truth?.let { img.cross(sx(0.0), sy(it.y), 10, green) }
        }
        img.write(f)
    }
}
