package dev.beck.bleradar

/**
 * Gögn sem eiga að lifa af að MainActivity sé eyðilögð og búin til aftur, t.d. þegar annað
 * app (hleðsluskjár o.fl.) tekur yfir skjáinn. Áður týndist þá öll söfnunin.
 */
object AppState {
    val store = TrackStore()
    val poses = PoseHistory()
    val recorder = Recorder()
    @Volatile var selected: String? = null

    /**
     * Hnitakerfi ARCore. Hver ný ARCore lota hefur nýjan upphafspunkt, svo staðsetningar úr
     * mismunandi lotum má ekki blanda saman. Hækkar þegar ný lota byrjar og gögn eru til.
     */
    @Volatile var frame = 0
        private set

    /** Kallað þegar ný ARCore lota hefst. Skilar true ef eldri staðsetningargögn voru núllstillt. */
    fun onNewArSession(): Boolean {
        val tracks = store.all()
        if (tracks.none { it.localizer.binCount() > 0 }) return false
        frame++
        poses.clear()
        // Raunstaðir eru líka í gamla hnitakerfinu
        for (t in tracks) { t.localizer.clear(); t.estimate = null; t.truth = null }
        return true
    }
}
