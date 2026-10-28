package dev.beck.bleradar

import java.io.Writer
import java.util.Locale

/**
 * Upptaka af lotunni til endurspilunar á tölvu (SessionReplayTest): staðsetning símans,
 * gólf, mat appsins og „raunstaðir“ sem notandinn merkir. Hráar RSSI mælingar eru í CSV.
 *
 * Snið: ein færsla í línu, kommuaðskilin, fyrsti reitur er tegund:
 *   pose,t_ns,frame,x,y,z
 *   floor,t_ns,frame,y
 *   truth,t_ns,frame,address,x,y,z
 *   est,t_ns,frame,address,x,y,z,sx,sy,sz
 *   scan,t_ns,adverts,active
 *   device,address,kind,kindScore,vendor,name,label,avgRssi,adverts   (skrifað við vistun)
 */
class Recorder {
    private val lines = ArrayList<String>()
    private var lastPoseT = 0L
    private val lastEst = HashMap<String, Estimate>()

    @Synchronized private fun add(s: String) { if (lines.size < MAX_LINES) lines += s }

    /** Staðsetning símans, mest 10× á sekúndu. */
    fun pose(t: Long, frame: Int, p: Vec3) {
        if (t - lastPoseT < 100_000_000L) return
        lastPoseT = t
        add(fmt("pose,%d,%d,%.3f,%.3f,%.3f", t, frame, p.x, p.y, p.z))
    }

    fun floor(t: Long, frame: Int, y: Double) = add(fmt("floor,%d,%d,%.3f", t, frame, y))

    fun truth(t: Long, frame: Int, address: String, p: Vec3) =
        add(fmt("truth,%d,%d,%s,%.3f,%.3f,%.3f", t, frame, address, p.x, p.y, p.z))

    /** Skráir mat appsins þegar það breytist. */
    @Synchronized
    fun estimate(t: Long, frame: Int, address: String, e: Estimate) {
        if (lastEst[address] === e) return
        lastEst[address] = e
        add(fmt("est,%d,%d,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f", t, frame, address,
            e.pos.x, e.pos.y, e.pos.z, e.sigma.x, e.sigma.y, e.sigma.z))
    }

    fun scan(t: Long, adverts: Int, active: Int) = add(fmt("scan,%d,%d,%d", t, adverts, active))

    @Synchronized
    fun clear() { lines.clear(); lastEst.clear(); lastPoseT = 0 }

    @Synchronized
    fun write(w: Writer, tracks: List<Track>) {
        w.write("# ble3d session v1\n")
        for (t in tracks) {
            fun clean(s: String?) = (s ?: "").replace(",", " ").replace("\n", " ")
            w.write("device,${t.address},${t.kind.name},${t.kindScore},${clean(t.vendor)},${clean(t.name)},${clean(t.label())}," +
                String.format(Locale.ROOT, "%.1f,%d\n", t.avgRssi, t.totalAdverts))
        }
        for (l in lines) { w.write(l); w.write("\n") }
    }

    private fun fmt(f: String, vararg a: Any) = String.format(Locale.ROOT, f, *a)

    companion object {
        const val MAX_LINES = 400_000
    }
}
