package dev.beck.bleradar

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prófbekkur fyrir staðsetningarformúluna, í raunsærri hermun byggðri á raungögnum:
 *  - fjarlægðardeyfing A − 10·n·log10(d) með slembnu A (−65…−50) og n (1.8…3.0)
 *  - skuggar sem fylgja staðsetningu (~3 dB), líkamsdeyfing þegar tækið er fyrir aftan
 *    notandann (allt að 10 dB), Rayleigh útslökknun (einhliða: djúpar dýfur niður) og 1 dB suð
 *  - gönguleið um 6×5 m skrifstofu, síminn í 1.2–1.45 m og vísar fram í göngustefnu
 *  - tæki innan og utan gönguleiðar, á borði, gólfi eða hillu
 * Í raungögnum (Tölva 5/74) var hávaðinn 5.9–6.7 dB; hér er hann sambærilegur.
 *
 *   ./gradlew testDebugUnitTest --tests "*LocalizerBenchmark*" --rerun -Pbench=1
 */
class LocalizerBenchmarkTest {

    class Scenario(val samples: List<Sample>, val target: Vec3)

    companion object {
        /** Ein hermd lota; sama [seed] gefur sömu lotu fyrir öll afbrigði. */
        fun scenario(seed: Long): Scenario {
            val rnd = Random(seed)
            // Sendistyrkur: breitt bil, líka veikir sendar (turnar undir borði) — fyrri hermun miðaði
            // við −65…−50 og verðlaunaði því tvíhliða forsendu um −59 ranglega
            val a = -80.0 + rnd.nextDouble() * 30
            val n = 1.8 + rnd.nextDouble() * 1.2
            val k = Array(10) { DoubleArray(3) { rnd.nextGaussian() * 2.0 } }
            val ph = DoubleArray(10) { rnd.nextDouble() * 2 * PI }
            fun shadow(p: Vec3): Double {
                var s = 0.0
                for (i in k.indices) s += sin(k[i][0] * p.x + k[i][1] * p.y + k[i][2] * p.z + ph[i])
                return s * 3.0 / sqrt(k.size / 2.0)
            }
            // Tæki: helmingur innan gönguleiðar, helmingur allt að 2 m utan
            val inside = rnd.nextBoolean()
            val tx = if (inside) rnd.nextDouble() * 5 + 0.5 else (if (rnd.nextBoolean()) -rnd.nextDouble() * 2 else 6 + rnd.nextDouble() * 2)
            val tz = rnd.nextDouble() * 4 + 0.5
            val ty = when (rnd.nextInt(10)) { in 0..5 -> 0.75; in 6..7 -> 0.05; else -> 1.5 }
            val target = Vec3(tx, ty, tz)

            // Gönguleið milli slembinna punkta, ~0.8 m/s, 30 mælingar á sek
            val samples = ArrayList<Sample>()
            var pos = Vec3(rnd.nextDouble() * 6, 1.3, rnd.nextDouble() * 5)
            var goal = pos
            var t = 0L
            while (samples.size < 3000) {
                if ((goal - pos).horizNorm() < 0.2) goal = Vec3(rnd.nextDouble() * 6, 1.3, rnd.nextDouble() * 5)
                val dir = (goal - pos).let { Vec3(it.x, 0.0, it.z) * (1.0 / it.horizNorm()) }
                pos = pos + dir * (0.8 / 30)
                val y = 1.32 + 0.12 * sin(t / 4e9)
                val p = Vec3(pos.x, y, pos.z)
                val to = target - p
                val cosA = (to.x * dir.x + to.z * dir.z) / hypot(to.x, to.z).coerceAtLeast(1e-6)
                val body = 10.0 * (-cosA).coerceIn(0.0, 1.0)
                // Rayleigh: afl ~ Exp(1); í dB meðaltal −2.5, djúpar dýfur niður en lítið upp
                val fading = 10 * log10((-ln(1 - rnd.nextDouble())).coerceAtLeast(1e-6))
                val rssi = a - 10 * n * log10(to.norm().coerceAtLeast(0.1)) + shadow(p) - body + fading +
                    rnd.nextGaussian()
                samples += Sample(t, p, Math.round(rssi).toInt(), 0, dir)
                t += 33_000_000L
            }
            return Scenario(samples, target)
        }

        class Score(val median3d: Double, val p80: Double, val medianH: Double, val medianY: Double, val coverage: Double)

        fun score(make: () -> Localizer, seeds: LongRange, height: Pair<Double, Double>? = DeviceKind.UNKNOWN.let { it.height to it.heightSd }): Score {
            val e3 = ArrayList<Double>(); val eh = ArrayList<Double>(); val ey = ArrayList<Double>(); var covered = 0
            for (seed in seeds) {
                val sc = scenario(seed)
                val loc = make()
                sc.samples.forEach(loc::add)
                val e = loc.solve(floorY = 0.0, height = height)
                if (e == null) { e3 += 99.0; eh += 99.0; ey += 99.0; continue }
                e3 += (e.pos - sc.target).norm()
                eh += (e.pos - sc.target).horizNorm()
                ey += kotlin.math.abs(e.pos.y - sc.target.y)
                if ((e.pos - sc.target).horizNorm() <= e.horizSigma()) covered++
            }
            fun q(v: List<Double>, p: Double) = v.sorted()[((v.size - 1) * p).toInt()]
            return Score(q(e3, 0.5), q(e3, 0.8), q(eh, 0.5), q(ey, 0.5), covered.toDouble() / e3.size)
        }

        fun row(name: String, s: Score) = String.format(Locale.ROOT,
            "%-28s 3D miðg %.2f m  80%% %.2f m  lárétt %.2f  hæð %.2f  innan ±σ %3.0f%%",
            name, s.median3d, s.p80, s.medianH, s.medianY, 100 * s.coverage)
    }

    @Test
    fun benchmark() {
        assumeTrue("keyrt með -Pbench=1", System.getProperty("ble3d.bench") == "1")
        val seeds = 1L..150L
        println("Hermun: ${seeds.count()} lotur, sama gönguleið/tæki fyrir öll afbrigði")
        // Parað: sama lota með og án forsendu um sendistyrk
        fun errs(make: () -> Localizer) = seeds.map { seed ->
            val sc = scenario(seed); val loc = make(); sc.samples.forEach(loc::add)
            loc.solve(0.0, DeviceKind.UNKNOWN.height to DeviceKind.UNKNOWN.heightSd)?.let { (it.pos - sc.target).norm() } ?: 99.0
        }
        val base = errs { Localizer(txPrior = null) }
        println(row("án sendistyrksforsendu", score({ Localizer(txPrior = null) }, seeds)))
        for ((name, make) in listOf<Pair<String, () -> Localizer>>(
            "tvíhliða −59 ± 8 ×9" to { Localizer(txPrior = -59.0 to 8.0, txPriorScale = 9.0) },
            "einhliða > −50 ×9" to { Localizer(txPrior = -50.0 to 8.0, txPriorScale = 9.0, txPriorUpperOnly = true) },
        )) {
            println(row(name, score(make, seeds)))
            val v = errs(make)
            val d = v.indices.map { v[it] - base[it] }.filter { kotlin.math.abs(it) < 20 }
            val mean = d.average(); val se = sqrt(d.sumOf { (it - mean) * (it - mean) } / (d.size - 1) / d.size)
            println(String.format(Locale.ROOT, "   parað: betra í %3.0f%% lotna, meðalbreyting %+.3f ± %.3f m",
                100.0 * d.count { it < 0 } / d.size, mean, se))
        }
    }
}
