package dev.beck.bleradar

import android.Manifest
import android.bluetooth.le.ScanRecord
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class MainActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var ble: BleCollector
    private lateinit var settings: SettingsPanel

    // Lifa af endurgerð Activity (sjá AppState)
    private val store = AppState.store
    private val poses = AppState.poses
    private val bg = BackgroundRenderer()
    private val scene = SceneRenderer()
    private val depth = DepthMap()
    private val viewProj = FloatArray(16)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    // Sér þráður fyrir skönnunarvaktina svo þungt mat tefji hana ekki
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    private var session: Session? = null
    private var textureBoundTo: Session? = null
    private var installRequested = false
    private var permissionsAsked = false
    private var viewW = 0
    private var viewH = 0
    @Volatile private var geometryChanged = false
    @Volatile private var filter = Filter()
    private val prefs by lazy { getSharedPreferences("ble3d", MODE_PRIVATE) }

    // Talning fyrir stöðulínuna, uppfærð í solveAll
    @Volatile private var countSeen = 0
    @Volatile private var countShown = 0
    @Volatile private var countLocated = 0

    // Tölfræði fyrir dagbókina
    private val droppedNoPose = AtomicInteger()
    private val solveErrors = AtomicInteger()
    @Volatile private var solveMaxMs = 0L
    /** Töf myndavélarramma (veldisvísis-meðaltal, ms). */
    @Volatile private var lagMs = 0.0
    private val firstAdvert = AtomicBoolean(false)
    @Volatile private var trackingState = "ekki ræst"
    private var lastUpdateError: String? = null
    private var lastStatsMs = 0L

    // Gólf (GL þráður): lægsti lárétti flötur sem ARCore hefur fundið
    @Volatile private var floorY: Double? = null
    private var frameCount = 0

    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DebugLog.i("Ræsi: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")

        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        filter = Filter.load(prefs)
        overlay = OverlayView(this, store).apply {
            onStatusTap = { if (!hasPermissions()) openAppSettings() }
            onMarkTap = { x, y -> pendingMark = floatArrayOf(x, y) }
            filter = this@MainActivity.filter
        }
        settings = SettingsPanel(this, filter,
            onChange = { f ->
                filter = f
                overlay.filter = f
                f.save(prefs)
                overlay.invalidate()
            },
            onClose = { f -> DebugLog.i("Sía: ${f.describe()}") },
        ).apply { visibility = android.view.View.GONE }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Vista") { exportCsv() })
            addView(button("Hreinsa") {
                store.clear(); poses.clear(); this@MainActivity.overlay.selected = null; AppState.selected = null
                AppState.recorder.clear()
                DebugLog.i("Gögn hreinsuð")
                toast("Öllum gögnum eytt")
            })
            addView(button("Log") { this@MainActivity.overlay.showLog = !this@MainActivity.overlay.showLog })
            addView(button("Sía") {
                if (settings.visibility == android.view.View.VISIBLE) settings.close()
                else settings.visibility = android.view.View.VISIBLE
            })
            addView(button("📍") { startMarking() })
        }
        val root = FrameLayout(this).apply {
            addView(glView)
            addView(this@MainActivity.overlay)
            addView(buttons, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = (70 * resources.displayMetrics.density).toInt() })
            addView(settings, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP,
            ).apply { topMargin = (120 * resources.displayMetrics.density).toInt() })
        }
        setContentView(root)

        ble = BleCollector(this) { addr, name, rssi, tNanos, rec -> onAdvert(addr, name, rssi, tNanos, rec) }

        // Endurmat staðsetninga ~1.4× á sekúndu, utan GL- og UI-þráða.
        // Óveidd undantekning myndi stöðva endurtekninguna hljóðlaust, því er allt grípið.
        worker.scheduleWithFixedDelay(guarded("solveAll") { solveAll() }, 1000, 700, TimeUnit.MILLISECONDS)
        watchdog.scheduleWithFixedDelay(guarded("healthCheck") { healthCheck() }, 2000, 2000, TimeUnit.MILLISECONDS)
        overlay.selected = AppState.selected
        if (store.all().isNotEmpty()) DebugLog.i("Held áfram með ${store.all().size} tæki úr fyrri skjá")
    }

    // ---------- Raunstaður („Hér er tækið“) ----------

    /** Skjápunktur sem notandinn pikkaði á; GL þráðurinn gerir hit test á næsta ramma. */
    @Volatile private var pendingMark: FloatArray? = null

    private fun startMarking() {
        val sel = overlay.selected?.let { store.find(it) }
        if (sel == null) { toast("Veldu fyrst tæki í listanum"); return }
        overlay.markingFor = sel.address
        DebugLog.i("Merking hafin: ${sel.label()} (${sel.address})")
    }

    /** Finnur raunverulegan 3D punkt þar sem pikkað var (flötur eða dýpt) og skráir hann. */
    private fun handleMark(frame: com.google.ar.core.Frame, tap: FloatArray) {
        val addr = overlay.markingFor ?: return
        overlay.markingFor = null
        val t = store.find(addr) ?: return
        val hit = frame.hitTest(tap[0], tap[1]).firstOrNull { h ->
            when (val tr = h.trackable) {
                is Plane -> tr.isPoseInPolygon(h.hitPose)
                is com.google.ar.core.DepthPoint -> true
                is com.google.ar.core.Point -> tr.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }
        if (hit == null) {
            DebugLog.w("Merking: enginn flötur fannst þar sem pikkað var")
            runOnUiThread { toast("Fann ekki staðinn — beindu nær og reyndu aftur") }
            return
        }
        val p = hit.hitPose
        val truth = Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble())
        val stronger = strongerNear(t, truth)
        if (stronger != null) {
            val (other, mine, theirs) = stronger
            DebugLog.w(String.format(Locale.ROOT, "Merking: %s er miklu sterkari en %s nálægt staðnum (%d vs %d dBm)",
                other.label(), t.label(), theirs, mine))
            runOnUiThread { askWhichDevice(t, other, mine, theirs, truth, hit.trackable.javaClass.simpleName) }
            return
        }
        setTruth(t, truth, hit.trackable.javaClass.simpleName)
    }

    /**
     * Miðgildi RSSI hvers tækis þegar síminn var innan 1.5 m (lárétt) frá merkta staðnum.
     * Skilar (sterkara tæki, RSSI valda, RSSI hins) ef annað tæki er ≥ 15 dB sterkara. Í lotu
     * 14:49 var „Tölva 11“ merkt en hún var −90 dBm á staðnum; „Tölva 5“ var −50 dBm.
     */
    private fun strongerNear(t: Track, truth: Vec3): Triple<Track, Int, Int>? {
        fun medianNear(tr: Track): Int? {
            val v = tr.rawCopy().filter { it.frame == AppState.frame && (it.pos - truth).horizNorm() < 1.5 }
                .map { it.rssi }.sorted()
            return if (v.size < 5) null else v[v.size / 2]
        }
        val mine = medianNear(t) ?: return null
        val best = store.all().filter { it !== t }.mapNotNull { o -> medianNear(o)?.let { o to it } }
            .maxByOrNull { it.second } ?: return null
        return if (best.second - mine >= 15) Triple(best.first, mine, best.second) else null
    }

    private fun askWhichDevice(t: Track, other: Track, mine: Int, theirs: Int, truth: Vec3, how: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Er þetta rétt tæki?")
            .setMessage("${other.kind.icon} ${other.label()} er miklu sterkari hér ($theirs dBm) en " +
                "${t.kind.icon} ${t.label()} ($mine dBm). Líklega er ${t.label()} lengra í burtu.")
            .setPositiveButton("Merkja ${other.label()}") { _, _ ->
                overlay.selected = other.address; AppState.selected = other.address
                setTruth(other, truth, how)
            }
            .setNegativeButton("Halda ${t.label()}") { _, _ -> setTruth(t, truth, how) }
            .show()
    }

    private fun setTruth(t: Track, truth: Vec3, how: String) {
        t.truth = truth
        AppState.recorder.truth(SystemClock.elapsedRealtimeNanos(), AppState.frame, t.address, truth)
        val e = t.estimate
        val err = e?.let { String.format(Locale.ROOT, " · skekkja %.2f m (lárétt %.2f, hæð %+.2f)",
            (it.pos - truth).norm(), (it.pos - truth).horizNorm(), it.pos.y - truth.y) } ?: " · ekkert mat enn"
        DebugLog.i(String.format(Locale.ROOT, "Raunstaður %s: (%.2f, %.2f, %.2f) [%s]%s",
            t.label(), truth.x, truth.y, truth.z, how, err))
        runOnUiThread { toast("Merkt: ${t.label()}") }
    }

    private fun guarded(name: String, f: () -> Unit) = Runnable {
        try { f() } catch (e: Exception) { DebugLog.w("$name mistókst", e) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minWidth = 0; minimumWidth = 0
        setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = 12 }
    }

    // ---------- Heimildir ----------

    private fun neededPermissions(): Array<String> {
        // Staðsetningarheimild þarf á öllum útgáfum: BLUETOOTH_SCAN er ekki merkt
        // neverForLocation (við reiknum jú staðsetningu), og án hennar skilar skönnun engu.
        val p = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p += Manifest.permission.BLUETOOTH_SCAN
        }
        return p.toTypedArray()
    }

    private fun missingPermissions() = neededPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun hasPermissions() = missingPermissions().isEmpty()

    private fun shortName(perm: String) = perm.substringAfterLast('.')

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        DebugLog.i("Heimildir: " + permissions.indices.joinToString {
            "${shortName(permissions[it])}=${if (results.getOrNull(it) == PackageManager.PERMISSION_GRANTED) "já" else "nei"}"
        })
        if (!hasPermissions()) toast("Forritið þarf heimild fyrir myndavél, Bluetooth og staðsetningu")
    }

    private fun openAppSettings() {
        DebugLog.i("Opna stillingar appsins")
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ---------- Lífsferill ----------

    override fun onResume() {
        super.onResume()
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            // Bara spurt einu sinni; annars lendum við í hringrás ef notandinn hafnar
            if (!permissionsAsked) {
                permissionsAsked = true
                DebugLog.i("Bið um heimildir: ${missing.joinToString { shortName(it) }}")
                requestPermissions(missing.toTypedArray(), 1)
            } else {
                val onlyCoarse = missing == listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                overlay.notice = if (onlyCoarse) "Veldu „Nákvæm“ staðsetningu — pikkaðu hér"
                else "Vantar heimild (${missing.joinToString { shortName(it) }}) — pikkaðu hér"
                DebugLog.w("Vantar heimildir: ${missing.joinToString { shortName(it) }}")
            }
            return
        }
        overlay.notice = null
        // BLE er ræst óháð ARCore, svo tækjalistinn virki þó AR klikki
        ble.resume()
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        DebugLog.i("Bið um uppsetningu ARCore")
                        installRequested = true; return
                    }
                    else -> {}
                }
                session = Session(this).also { s ->
                    val cfg = Config(s).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        // Láréttir fletir: notað til að finna gólfið og sýna hæð frá því
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        lightEstimationMode = Config.LightEstimationMode.DISABLED
                        focusMode = Config.FocusMode.AUTO
                        // Dýpt: til að sýna þegar tæki er á bak við raunverulega hluti
                        depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC
                        else Config.DepthMode.DISABLED
                        DebugLog.i("ARCore dýpt: $depthMode")
                    }
                    s.configure(cfg)
                }
                DebugLog.i("ARCore lota búin til")
                if (AppState.onNewArSession()) {
                    DebugLog.w("Nýtt ARCore hnitakerfi (#${AppState.frame}): staðsetningarmat byrjar upp á nýtt, " +
                        "eldri mælingar eru áfram í CSV")
                }
            } catch (e: Exception) {
                DebugLog.w("ARCore lota ræstist ekki", e)
                overlay.notice = "ARCore villa: ${e.javaClass.simpleName}"
                toast("ARCore villa: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            DebugLog.w("Myndavél ekki tiltæk", e)
            overlay.notice = "Myndavél ekki tiltæk"
            toast("Myndavél ekki tiltæk"); session = null; return
        }
        glView.onResume()
        geometryChanged = true
    }

    override fun onPause() {
        super.onPause()
        AppState.selected = overlay.selected
        DebugLog.i("Skjár: onPause")
        ble.pause()
        if (session != null) {
            glView.onPause()
            session?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        DebugLog.i("Skjár: onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.w("Skjár: onDestroy (finishing=$isFinishing, config=$isChangingConfigurations) — gögnin haldast í AppState")
        worker.shutdownNow()
        watchdog.shutdownNow()
        session?.close()
        session = null
    }

    // ---------- BLE ----------

    private fun onAdvert(addr: String, name: String?, rssi: Int, tNanos: Long, rec: ScanRecord?) {
        if (firstAdvert.compareAndSet(false, true)) DebugLog.i("Fyrsta auglýsing barst: $addr ${name ?: ""} $rssi dBm")
        val t = store.get(addr)
        if (name != null) t.name = name
        // Nafnið kemur oft í öðrum pakka (scan response) en framleiðandagögnin, svo flokkað er
        // í hvert sinn og öruggasta niðurstaðan geymd
        try { t.applyGuess(Classifier.classify(rec, t.name)) } catch (_: Exception) {}
        t.lastRssi = rssi
        t.avgRssi = if (t.totalAdverts == 0) rssi.toDouble() else 0.85 * t.avgRssi + 0.15 * rssi
        t.lastSeenNanos = SystemClock.elapsedRealtimeNanos()
        t.totalAdverts++
        val pos = poses.at(tNanos)
        if (pos == null) { droppedNoPose.incrementAndGet(); return }
        val s = Sample(tNanos, pos, rssi, AppState.frame, poses.forwardAt(tNanos))
        t.localizer.add(s)
        t.addRaw(s)
    }

    private fun solveAll() {
        val now = SystemClock.elapsedRealtimeNanos()
        val sel = overlay.selected
        val f = filter
        // Aðeins tæki sem standast síuna eru metin, svo 20 sætin fari í það sem leitað er að
        // Valið tæki í hverri umferð, og þau 6 sem lengst er síðan voru metin (mat tók > 1 s
        // þegar öll 20 voru metin í hverri umferð)
        val eligible = store.all()
            .filter { it.address == sel || (now - it.lastSeenNanos < 30_000_000_000L && f.matches(it)) }
            .sortedByDescending { it.localizer.binCount() }
            .take(20)
        val candidates = eligible.filter { it.address == sel } +
            eligible.filter { it.address != sel }.sortedBy { it.lastSolvedNanos }.take(6)
        val t0 = SystemClock.elapsedRealtime()
        for (t in candidates) {
            try {
                t.lastSolvedNanos = now
                t.updateEstimate(t.localizer.solve(floorY, t.kind.height to t.kind.heightSd))
                t.estimate?.let { AppState.recorder.estimate(now, AppState.frame, t.address, it) }
            } catch (e: Exception) {
                // Skrá bara fyrstu villuna í hverju 10 s tímabili, fjöldinn kemur í stöðulínu dagbókar
                if (solveErrors.getAndIncrement() == 0) DebugLog.w("Mat mistókst fyrir ${t.label()}", e)
            }
        }
        solveMaxMs = maxOf(solveMaxMs, SystemClock.elapsedRealtime() - t0)
        val seen = store.all().filter { now - it.lastSeenNanos < 15_000_000_000L }
        val shown = seen.filter(f::matches)
        countSeen = seen.size
        countShown = shown.size
        countLocated = shown.count { it.estimate?.isLocated() == true }
        overlay.postInvalidate()
    }

    /** Á 2 s fresti: heldur skönnun gangandi. Á 10 s fresti: stöðulína í dagbók. */
    private fun healthCheck() {
        ble.ensure()
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsMs < 10_000) return
        lastStatsMs = now
        val adverts = ble.adverts.getAndSet(0)
        val dropped = droppedNoPose.getAndSet(0)
        val errs = solveErrors.getAndSet(0)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val tracks = store.all()
        val active = tracks.count { nowNs - it.lastSeenNanos < 10_000_000_000L }
        AppState.recorder.scan(nowNs, adverts, active)
        val scan = if (ble.isScanning) "skannar" else "skannar EKKI (${ble.error})"
        // Android stöðvar ósíaða BLE skönnun þegar slökkt er á skjánum (t.d. AOD/hleðsluskjár)
        val screen = if (getSystemService(PowerManager::class.java)?.isInteractive == false) " · SKJÁR AF" else ""
        val solveMs = solveMaxMs.also { solveMaxMs = 0 }
        DebugLog.i("Staða: $scan · $adverts auglýsingar ($dropped án staðsetningar) · " +
            "$active virk tæki / ${tracks.size} alls · $countShown eftir síu · $countLocated staðsett · " +
            "ARCore: $trackingState (töf ${lagMs.toInt()} ms) · mat ≤ $solveMs ms$screen" + if (errs > 0) " · $errs villur í mati" else "")
        val kinds = tracks.filter { nowNs - it.lastSeenNanos < 10_000_000_000L }
            .groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }
        DebugLog.i("Tegundir: " + kinds.joinToString(" ") { "${it.key.icon}${it.value}" })

        // Slembin MAC vistföng hlaðast upp; fleygja gömlum tækjum þegar þau eru orðin mörg
        if (tracks.size > MAX_TRACKS) {
            val sel = overlay.selected
            val stale = tracks.filter { it.address != sel && nowNs - it.lastSeenNanos > 10 * 60_000_000_000L }
            stale.forEach { store.remove(it.address) }
            if (stale.isNotEmpty()) DebugLog.i("Fleygði ${stale.size} tækjum sem hafa ekki sést í 10 mín")
        }
    }

    // ---------- GL / ARCore ----------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.init()
        scene.init()
        depth.init()
        textureBoundTo = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width; viewH = height
        geometryChanged = true
    }

    @Suppress("DEPRECATION")
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (textureBoundTo !== s) { s.setCameraTextureName(bg.textureId); textureBoundTo = s }
        if (geometryChanged && viewW > 0) {
            s.setDisplayGeometry(windowManager.defaultDisplay.rotation, viewW, viewH)
            geometryChanged = false
        }
        val frame = try {
            s.update().also { lastUpdateError = null }
        } catch (e: Exception) {
            // Gerist í hverjum ramma ef eitthvað er að; skrá bara þegar villan breytist
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            if (msg != lastUpdateError) { DebugLog.w("session.update() mistókst", e); lastUpdateError = msg }
            return
        }
        bg.draw(frame)

        val camera = frame.camera
        // Staðan á við þegar myndin var tekin (frame.timestamp). Tímagrunnurinn er ekki
        // tryggður á öllum símum, svo ef hann er langt frá elapsedRealtime er notaður „núna“.
        val wall = SystemClock.elapsedRealtimeNanos()
        val now = frame.timestamp.takeIf { abs(it - wall) < 500_000_000L } ?: wall
        val tracking = camera.trackingState == TrackingState.TRACKING
        val ts = if (tracking) "TRACKING" else "${camera.trackingState} (${camera.trackingFailureReason})"
        if (ts != trackingState) { DebugLog.i("ARCore rakning: $ts"); trackingState = ts }
        val status: String
        val pos: Vec3
        var occluded: Set<String> = emptySet()
        if (tracking) {
            val p = camera.pose
            pos = Vec3(p.tx().toDouble(), p.ty().toDouble(), p.tz().toDouble())
            // Myndavélin horfir eftir −Z ás sínum
            val z = p.zAxis
            poses.add(now, pos, Vec3(-z[0].toDouble(), -z[1].toDouble(), -z[2].toDouble()))
            // Töf bara mæld meðan ARCore rekur; í pásu uppfærist tími rammans ekki
            lagMs = 0.9 * lagMs + 0.1 * (wall - frame.timestamp) / 1e6
            AppState.recorder.pose(now, AppState.frame, pos)
            pendingMark?.let { pendingMark = null; handleMark(frame, it) }
            camera.getViewMatrix(viewM, 0)
            camera.getProjectionMatrix(projM, 0, 0.05f, 200f)
            if (frameCount % 3 == 0) floorPicker.addPhoneY(pos.y)
            if (++frameCount % 30 == 0) detectFloor(s)
            depth.update(frame)
            occluded = drawScene(frame, pos)
            val devices = if (filter.isActive) "$countShown af $countSeen tækjum" else "$countSeen tæki"
            val fy = floorY
            status = ble.error ?: if (fy != null) String.format(Locale.ROOT, "%s · %d staðsett · sími %.1f m frá gólfi",
                devices, countLocated, pos.y - fy)
            else String.format(Locale.ROOT, "%s · %d staðsett · beindu símanum að gólfinu", devices, countLocated)
        } else {
            pos = Vec3(0.0, 0.0, 0.0)
            status = ble.error ?: "Myndavél: " + when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "of dimmt"
                TrackingFailureReason.EXCESSIVE_MOTION -> "hreyfðu hægar"
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "beindu að hlutum með áferð"
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "ekki tiltæk"
                else -> "ræsi rakningu…"
            }
        }
        overlay.update(OverlayView.CamState(viewM.copyOf(), projM.copyOf(), pos, tracking, status, floorY, occluded))
    }

    /**
     * Tækin í þrívídd: sama val og síun og 2D merkingarnar í OverlayView.
     * Skilar vistföngum tækja sem eru á bak við raunverulega hluti (samkvæmt dýptarkorti).
     */
    private fun drawScene(frame: com.google.ar.core.Frame, phone: Vec3): Set<String> {
        val sel = overlay.selected?.let { store.find(it) }
        val targets = OverlayView.visibleTargets(store, overlay.filter, sel, phone)
        val items = targets.map { (t, e) ->
            val rgb = if (t === sel) OverlayView.SELECTED_RGB else OverlayView.OTHER_RGB
            SceneRenderer.Item(e.pos, e.sigma, floatArrayOf(
                Color.red(rgb) / 255f, Color.green(rgb) / 255f, Color.blue(rgb) / 255f, 1f), t === sel,
                e.confidence().toFloat())
        }
        android.opengl.Matrix.multiplyMM(viewProj, 0, projM, 0, viewM, 0)
        val ring = sel?.estimate?.let { e -> e.sectors?.let { SceneRenderer.Ring(e.pos, e.ringR, it) } }
        scene.draw(viewProj, viewM, viewW, viewH, depth, phone, items, floorY, sel?.estimate?.suggest, sel?.truth, ring)

        val hidden = HashSet<String>()
        if (depth.available) for ((t, e) in targets) {
            val v = floatArrayOf(e.pos.x.toFloat(), e.pos.y.toFloat(), e.pos.z.toFloat(), 1f)
            val clip = FloatArray(4); val eye = FloatArray(4)
            android.opengl.Matrix.multiplyMV(clip, 0, viewProj, 0, v, 0)
            android.opengl.Matrix.multiplyMV(eye, 0, viewM, 0, v, 0)
            if (clip[3] <= 0.05f) continue
            val sx = (clip[0] / clip[3] + 1f) * 0.5f * viewW
            val sy = (1f - clip[1] / clip[3]) * 0.5f * viewH
            val real = depth.depthAt(frame, sx, sy) ?: continue
            if (-eye[2] > real + maxOf(0.3, 0.1 * real)) hidden += t.address
        }
        return hidden
    }

    private val floorPicker = FloorPicker()

    /** Gólf: sjá [FloorPicker]. Má færast í báðar áttir, en bara á flöt sem stenst hæðarprófið. */
    private fun detectFloor(s: Session) {
        val planes = s.getAllTrackables(Plane::class.java)
            .filter {
                it.trackingState == TrackingState.TRACKING && it.subsumedBy == null &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }
            .map { it.centerPose.ty().toDouble() }
        val floor = floorPicker.pick(planes) ?: return
        val old = floorY
        if (old == null || abs(floor - old) > 0.05) {
            DebugLog.i(String.format(Locale.ROOT, "Gólf: y = %.2f m (sími að jafnaði %.2f m yfir)",
                floor, (floorPicker.phoneMedianY() ?: floor) - floor))
            floorY = floor
            AppState.recorder.floor(SystemClock.elapsedRealtimeNanos(), AppState.frame, floor)
        }
    }

    // ---------- Útflutningur ----------

    /** Vistar CSV gögnin og dagbókina saman í Downloads/BLE3D/. */
    private fun exportCsv() {
        worker.execute {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                var rows = 0
                saveDownload("ble3d_$ts.csv", "text/csv") { w ->
                    w.write("t_ns,address,name,x,y,z,rssi,frame,fx,fy,fz\n")
                    for (t in store.all()) {
                        val nm = (t.name ?: "").replace(",", " ").replace("\n", " ").replace("%", "")
                        for (s in t.rawCopy()) {
                            val fw = s.fwd?.let { String.format(Locale.ROOT, "%.3f,%.3f,%.3f", it.x, it.y, it.z) } ?: ",,"
                            w.write("${s.tNanos},${t.address},$nm,%.3f,%.3f,%.3f,${s.rssi},${s.frame},$fw\n".format(
                                Locale.ROOT, s.pos.x, s.pos.y, s.pos.z))
                            rows++
                        }
                    }
                }
                DebugLog.i("CSV vistað: $rows línur")
                saveDownload("ble3d_${ts}_log.txt", "text/plain") { w ->
                    for (l in DebugLog.all()) { w.write(l); w.write("\n") }
                }
                saveDownload("ble3d_${ts}_session.txt", "text/plain") { w -> AppState.recorder.write(w, store.all()) }
                DebugLog.i("Upptaka vistuð: ble3d_${ts}_session.txt")
                runOnUiThread { toast("Vistað: Downloads/BLE3D/ble3d_$ts (gögn, upptaka og log)") }
            } catch (e: Exception) {
                DebugLog.w("Vistun mistókst", e)
                runOnUiThread { toast("Vistun mistókst: ${e.message}") }
            }
        }
    }

    private fun saveDownload(name: String, mime: String, write: (Writer) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BLE3D")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert skilaði null")
        contentResolver.openOutputStream(uri)!!.bufferedWriter().use(write)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val MAX_TRACKS = 150
    }
}
