package dev.beck.bleradar

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import java.util.Random

class LocalizerTest {

    /** Hermir göngu um ~4×3 m svæði. sweepHeight=true: síminn sveiflast upp/niður 0.2–2.0 m. */
    private fun simulate(target: Vec3, sweepHeight: Boolean, noiseDb: Double, seed: Long): Localizer {
        val rnd = Random(seed)
        val loc = Localizer()
        val txAt1m = -62.0
        val n = 2.2
        var t = 0L
        for (i in 0 until 3000) {           // ~3000 auglýsingar ≈ 1–2 mín
            val s = i / 3000.0 * 2 * Math.PI * 3
            val x = 2.0 * cos(s) + 0.4 * sin(5 * s)
            val z = 1.5 * sin(s) - 1.0
            val y = if (sweepHeight) 1.1 + 0.9 * sin(i / 3000.0 * 2 * Math.PI * 17) else 1.3
            val p = Vec3(x, y, z)
            val d = (target - p).norm().coerceAtLeast(0.1)
            val rssi = txAt1m - 10 * n * log10(d) + rnd.nextGaussian() * noiseDb
            loc.add(Sample(t, p, Math.round(rssi).toInt()))
            t += 30_000_000L
        }
        return loc
    }

    @Test
    fun recovers3dPositionWithHeightSweep() {
        val target = Vec3(3.0, 0.3, -2.5)
        var worst = 0.0
        for (seed in 1L..10L) {
            val e = simulate(target, sweepHeight = true, noiseDb = 4.0, seed = seed).solve()!!
            val err = (e.pos - target).norm()
            println("seed=$seed pos=${e.pos} err=%.2f σ=${e.sigma} rms=%.1f amb=${e.ambiguous}".format(err, e.rmsDb))
            worst = maxOf(worst, err)
        }
        assertTrue("worst error $worst", worst < 1.5)
    }

    @Test
    fun flagsHeightAmbiguityWhenPhoneStaysAtOneHeight() {
        val target = Vec3(3.0, 0.3, -2.5)   // 1 m undir símanum
        var flagged = 0
        for (seed in 1L..10L) {
            val e = simulate(target, sweepHeight = false, noiseDb = 4.0, seed = seed).solve()!!
            println("planar seed=$seed pos=${e.pos} mirror=${e.mirror} σy=%.2f".format(e.sigma.y))
            if (e.ambiguous || e.sigma.y > 1.0) flagged++
        }
        assertTrue("flagged $flagged/10", flagged >= 8)
    }

    @Test
    fun suggestsWalkingToTheOtherSide() {
        // Gengið fram og til baka eftir línu z = 0; tækið er 3 m „fyrir framan“ (z = -3)
        val target = Vec3(0.0, 1.0, -3.0)
        val rnd = Random(7)
        val loc = Localizer()
        for (i in 0 until 2000) {
            val p = Vec3(2.0 * sin(i / 2000.0 * 2 * Math.PI * 3), 1.1 + 0.8 * sin(i * 0.05), 0.0)
            val rssi = -60.0 - 22 * log10((target - p).norm()) + rnd.nextGaussian() * 3.0
            loc.add(Sample(i * 30_000_000L, p, Math.round(rssi).toInt()))
        }
        val e = loc.solve()!!
        println("þekja=%.0f° tillaga=${e.suggest}".format(e.coverageDeg))
        assertTrue("þekja ${e.coverageDeg}", e.coverageDeg < 210)
        // Hringurinn á gólfinu: sumir hlutar mældir (gönguhliðin), aðrir ekki
        val sec = e.sectors!!
        assertTrue("hlutar ${sec.toList()}", sec.any { it } && sec.any { !it })
        // Úr einni línu sést ekki hvort tækið er fyrir framan eða aftan hana (spegilmynd), en
        // tillagan á alltaf að vera handan metna staðarins, séð frá gönguleiðinni (z = 0)
        val s = e.suggest!!
        assertTrue("tillagan ætti að vera handan tækisins: mat=${e.pos} tillaga=$s",
            kotlin.math.sign(s.z - e.pos.z) == kotlin.math.sign(e.pos.z))
    }

    private fun est(sigma: Double, x: Double = 0.0) = Estimate(
        pos = Vec3(x, 1.0, 0.0), sigma = Vec3(sigma, sigma, sigma), txPower = -60.0, rmsDb = 3.0, nBins = 50,
        spread = Vec3(1.0, 0.5, 1.0), ambiguous = false, mirror = null,
    )

    @Test
    fun estimateIsNeverLostOrWorsened() {
        val t = Track("AA:BB", 1)
        t.updateEstimate(est(0.5))
        t.updateEstimate(null)                  // of fá gögn í bili: halda gamla
        assertTrue(t.estimate!!.sigma.x == 0.5)
        t.updateEstimate(est(2.0, x = 5.0))     // miklu óvissara (t.d. gengið frá tækinu): halda gamla
        assertTrue(t.estimate!!.pos.x == 0.0)
        t.updateEstimate(est(0.3, x = 0.2))     // betra: taka nýja
        assertTrue(t.estimate!!.pos.x == 0.2)
    }

    @Test
    fun floorConstraintKeepsDeviceAboveFloor() {
        // Tæki á gólfinu (y = 0), síminn alltaf í sömu hæð: án gólfs er hæðin tvíræð
        val floor = 0.0
        val target = Vec3(3.0, 0.05, -2.5)
        for (seed in 1L..5L) {
            val loc = simulate(target, sweepHeight = false, noiseDb = 4.0, seed = seed)
            val e = loc.solve(floorY = floor)!!
            assertTrue("seed=$seed y=${e.pos.y}", e.pos.y >= floor - 1e-9)
        }
    }

    @Test
    fun heightPriorStopsDevicesFloating() {
        // Tölva á borði (0.75 m yfir gólfi y = 0); síminn alltaf í 1.3 m hæð svo RSSI segir
        // nánast ekkert um hæðina. Án forsendu flýtur matið; með henni lendir það nálægt borðhæð.
        val floor = 0.0
        val target = Vec3(3.0, 0.75, -2.5)
        val errNo = ArrayList<Double>(); val errPrior = ArrayList<Double>()
        for (seed in 1L..10L) {
            val loc = simulate(target, sweepHeight = false, noiseDb = 4.0, seed = seed)
            errNo += kotlin.math.abs(loc.solve(floorY = floor)!!.pos.y - target.y)
            val e = loc.solve(floorY = floor, height = DeviceKind.COMPUTER.height to DeviceKind.COMPUTER.heightSd)!!
            errPrior += kotlin.math.abs(e.pos.y - target.y)
            assertTrue("seed=$seed y=${e.pos.y}", e.pos.y in floor..floor + Localizer.CEILING_M)
        }
        val mNo = errNo.sorted()[5]; val mPrior = errPrior.sorted()[5]
        println("hæðarskekkja miðgildi: án forsendu %.2f m, með %.2f m".format(mNo, mPrior))
        assertTrue("með forsendu $mPrior vs án $mNo", mPrior < 0.4 && mPrior < mNo)
    }

    @Test
    fun clearDataOverridesHeightPrior() {
        // Tölva á hillu í 1.6 m (langt frá forsendunni 0.8 m) og síminn sveiflaður upp og niður:
        // gögnin eiga að ráða, forsendan má ekki draga matið niður á borðhæð
        val floor = 0.0
        val target = Vec3(3.0, 1.6, -2.5)
        val err = (1L..10L).map { seed ->
            val e = simulate(target, sweepHeight = true, noiseDb = 4.0, seed = seed)
                .solve(floorY = floor, height = DeviceKind.COMPUTER.height to DeviceKind.COMPUTER.heightSd)!!
            kotlin.math.abs(e.pos.y - target.y)
        }.sorted()
        println("hilla: hæðarskekkja miðgildi %.2f m, versta %.2f m".format(err[5], err.last()))
        assertTrue("miðgildi ${err[5]}", err[5] < 0.4)
    }

    @Test
    fun floorPickerRejectsTablesAndFalsePlanes() {
        // Tölur úr lotu 15:04–15:08: síminn að jafnaði í y ≈ 0.46; flatir við −0.92, −1.00, −1.75
        val fp = FloorPicker()
        repeat(100) { fp.addPhoneY(0.46 + (it % 10 - 5) * 0.03) }
        // −1.75 þýddi að síminn væri í 2.2 m hæð → hafnað; −0.10 er borð → hafnað
        assertTrue(fp.pick(listOf(-0.10, -0.92, -1.00, -1.75)) == -1.00)
        assertTrue(fp.pick(listOf(-1.75)) == null)
    }

    @Test
    fun poseHistoryInterpolates() {
        val h = PoseHistory()
        h.add(0L, Vec3(0.0, 0.0, 0.0))
        h.add(100_000_000L, Vec3(1.0, 2.0, 0.0))
        val p = h.at(50_000_000L)!!
        assertTrue(kotlin.math.abs(p.x - 0.5) < 1e-9 && kotlin.math.abs(p.y - 1.0) < 1e-9)
        assertTrue(h.at(900_000_000L) == null)
        // Mæling sem berst 200 ms eftir síðasta ramma (myndavélartöf): framreiknað, ekki hent
        val ahead = h.at(300_000_000L)!!
        assertTrue("x=${ahead.x}", kotlin.math.abs(ahead.x - 3.0) < 1e-9)
    }
}
