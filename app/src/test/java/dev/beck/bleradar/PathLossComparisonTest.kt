package dev.beck.bleradar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Ber saman fast n = 2.2 og metið n í raunsærri hermun: fylgnandi skuggadeyfing
 * (summa slembinna bylgna, ~4 dB), 3 dB hvítur hávaði, og raun-n 2.0–3.2.
 * Niðurstaðan (okt. 2026): munurinn er innan ±10% og ekki stöðugur, svo fast n er sjálfgefið.
 */
class PathLossComparisonTest {

    private class Shadow(rnd: Random, val sd: Double) {
        private val k = Array(12) { DoubleArray(3) { rnd.nextGaussian() * 2.0 } }   // ~0.5 m bylgjulengd
        private val ph = DoubleArray(12) { rnd.nextDouble() * 2 * PI }
        fun at(p: Vec3): Double {
            var s = 0.0
            for (i in k.indices) s += sin(k[i][0] * p.x + k[i][1] * p.y + k[i][2] * p.z + ph[i])
            return s * sd / kotlin.math.sqrt(k.size / 2.0)
        }
    }

    private fun run(trueN: Double, fitN: Boolean, seed: Long): Double {
        val rnd = Random(seed)
        val shadow = Shadow(rnd, 4.0)
        // Tæki á slembnum stað nálægt gönguleiðinni, 0–2 m hæð
        val target = Vec3(rnd.nextDouble() * 6 - 3, rnd.nextDouble() * 2.0, rnd.nextDouble() * 5 - 3.5)
        val loc = Localizer(fitN = fitN, priorWeight = 100.0)
        for (i in 0 until 3000) {
            val s = i / 3000.0 * 2 * PI * 3
            val p = Vec3(
                2.5 * cos(s) + 0.4 * sin(5 * s),
                1.1 + 0.8 * sin(i / 3000.0 * 2 * PI * 17),
                2.0 * sin(s) - 1.0,
            )
            val d = (target - p).norm().coerceAtLeast(0.1)
            val rssi = -60.0 - 10 * trueN * log10(d) + shadow.at(p) + rnd.nextGaussian() * 3.0
            loc.add(Sample(i * 30_000_000L, p, Math.round(rssi).toInt()))
        }
        val e = loc.solve() ?: return 99.0
        return (e.pos - target).norm()
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    @Test
    fun estimatingNIsComparableToFixedN() {
        val seeds = 1L..30L
        val results = mutableMapOf<Double, Pair<Double, Double>>()
        for (trueN in listOf(2.0, 2.2, 2.7, 3.2)) {
            val fixed = median(seeds.map { run(trueN, false, it) })
            val fitted = median(seeds.map { run(trueN, true, it) })
            results[trueN] = fixed to fitted
            println("n=%.1f  fast n: miðgildi %.2f m   metið n: miðgildi %.2f m".format(trueN, fixed, fitted))
        }
        // Einstakar línur eru hávaðasamar (30 keyrslur); berum saman meðaltal yfir öll n
        val fixedAvg = results.values.map { it.first }.average()
        val fittedAvg = results.values.map { it.second }.average()
        println("meðaltal: fast %.2f m, metið %.2f m".format(fixedAvg, fittedAvg))
        // Niðurstaða: enginn áreiðanlegur munur. Prófið gætir þess að fitN valkosturinn sé ekki bilaður.
        assertTrue("metið n ($fittedAvg) vs fast ($fixedAvg)", fittedAvg <= fixedAvg * 1.10)
    }
}
