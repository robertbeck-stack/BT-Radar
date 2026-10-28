package dev.beck.bleradar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Teiknar áætlaðar staðsetningar ofan á myndavélarmyndina + lista yfir tæki. */
class OverlayView(ctx: Context, private val store: TrackStore) : View(ctx) {

    class CamState(
        val view: FloatArray,
        val proj: FloatArray,
        val pos: Vec3,
        val tracking: Boolean,
        val status: String,
        /** Hæð gólfs (y) ef ARCore hefur fundið það, annars null. */
        val floorY: Double? = null,
        /** Tæki sem eru á bak við raunverulega hluti (skjá, vegg) samkvæmt dýptarkorti. */
        val occluded: Set<String> = emptySet(),
    )

    @Volatile private var cam: CamState? = null
    @Volatile var selected: String? = null
    /** Skilaboð sem hafa forgang í stöðulínunni (heimildir vantar, ARCore villa o.s.frv.). */
    @Volatile var notice: String? = null
    @Volatile var showLog = false
    var onStatusTap: (() -> Unit)? = null
    @Volatile var filter = Filter()
    /** Vistfang tækis sem verið er að merkja raunstað fyrir; næsta pikk fer í hit test. */
    @Volatile var markingFor: String? = null
    var onMarkTap: ((Float, Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val rowRects = ArrayList<Pair<RectF, String>>()
    /** Skjástaðsetningar punkta sem voru teiknaðir, svo hægt sé að pikka á þá. */
    private val targetHits = ArrayList<Pair<PointF, String>>()

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14 * d; setShadowLayer(3 * d, 0f, 0f, Color.BLACK)
    }
    private val small = Paint(text).apply { textSize = 12 * d }
    private val big = Paint(text).apply { textSize = 16 * d; isFakeBoldText = true }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val panel = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; typeface = Typeface.MONOSPACE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * d; textAlign = Paint.Align.CENTER }
    private val green = Color.rgb(60, 220, 90)
    private val dim = Paint(small).apply { color = Color.rgb(190, 190, 190) }

    fun update(c: CamState) {
        cam = c
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val cs = cam
        val sel = selected?.let { store.find(it) }
        targetHits.clear()

        if (cs != null && cs.tracking) {
            val vp = FloatArray(16)
            Matrix.multiplyMM(vp, 0, cs.proj, 0, cs.view, 0)

            if (sel != null) drawBins(c, vp, sel)
            val targets = visibleTargets(store, filter, sel, cs.pos)
            for ((t, e) in targets) drawTarget(c, cs, vp, t, e, t === sel)
            sel?.estimate?.suggest?.let { drawSuggestion(c, cs, vp, it) }
        }

        // Stöðulína efst, og leiðbeiningar ef tæki er valið
        val warn = small.withColor(Color.YELLOW)
        val info = ArrayList<Pair<String, Paint>>()
        if (markingFor != null) {
            info += "📍 Pikkaðu nákvæmlega þar sem ${store.find(markingFor!!)?.label() ?: "tækið"} er" to warn
        }
        if (sel != null) {
            val e = sel.estimate
            info += "Valið: ${sel.kind.icon} ${sel.label()}${sel.detailText()?.let { " · $it" } ?: ""}" to small
            val truth = sel.truth
            if (truth != null && e != null) info += String.format(Locale.ROOT,
                "📍 Skekkja %.2f m (lárétt %.2f · hæð %+.2f)", (e.pos - truth).norm(), (e.pos - truth).horizNorm(),
                e.pos.y - truth.y) to small.withColor(green)
            when {
                e == null -> info += "Safna gögnum — gakktu um og sveiflaðu símanum" to small
                else -> {
                    val done = e.sectors?.count { it } ?: 0
                    if (e.suggest != null) info += "⚠ Hringur á gólfi: $done/${Localizer.SECTORS} mældir — gakktu að appelsínugulu hlutunum" to warn
                    if (e.spread.y < 0.3) info += "⚠ Færðu símann upp og niður (gólf ↔ yfir höfuð) til að fá hæð" to warn
                    if (e.ambiguous) info += "⚠ Tvíræð lausn — hvítur hringur sýnir hina mögulegu staðsetninguna" to warn
                    if (e.suggest == null && e.spread.y >= 0.3 && !e.ambiguous) info +=
                        "✓ Góð gögn: $done/${Localizer.SECTORS} hlutar hringsins mældir" to small
                }
            }
        } else {
            info += "Pikkaðu á tæki í listanum eða punkt til að velja það" to small
        }
        fun line(l: String, p: Paint, y: Float) = c.drawText(l.take(p.breakText(l, true, width - 24 * d, null)), 12 * d, y, p)
        // Efst: stöðulína og ein upplýsingalína (passar ofan við hnappana sem byrja í 70 dp)
        c.drawRect(0f, 0f, width.toFloat(), 64 * d, panel)
        c.drawText(notice ?: cs?.status ?: "Ræsi…", 12 * d, 34 * d, big)
        info.firstOrNull()?.let { (l, p) -> line(l, p, 54 * d) }
        // Viðvaranir o.fl. fyrir neðan hnappana, annars lenda þær undir þeim
        val rest = info.drop(1)
        var below = BUTTONS_BOTTOM * d
        if (rest.isNotEmpty()) {
            c.drawRect(0f, below, width.toFloat(), below + 8 * d + 17 * d * rest.size, panel)
            var y = below + 16 * d
            for ((l, p) in rest) { line(l, p, y); y += 17 * d }
            below = y
        }

        val listTop = drawList(c, cs)
        if (showLog) drawLog(c, below + 8 * d, listTop - 8 * d)
    }

    /** Síðustu línur dagbókarinnar, brotnar niður í skjábreidd. Viðvaranir gular. */
    private fun drawLog(c: Canvas, top: Float, bottom: Float) {
        val lineH = 13 * d
        val n = ((bottom - top - 6 * d) / lineH).toInt()
        if (n <= 0) return
        val maxW = width - 12 * d
        val wrapped = ArrayList<Pair<String, Boolean>>()
        for (l in DebugLog.tail(n)) {
            val warn = l.contains(" W ")
            var rest = l
            while (rest.isNotEmpty()) {
                val k = mono.breakText(rest, true, maxW, null).coerceAtLeast(1)
                wrapped += rest.substring(0, k) to warn
                rest = rest.substring(k)
            }
        }
        c.drawRect(0f, top, width.toFloat(), bottom, panel)
        var y = top + lineH
        for ((l, warn) in wrapped.takeLast(n)) {
            mono.color = if (warn) Color.YELLOW else Color.WHITE
            c.drawText(l, 6 * d, y, mono)
            y += lineH
        }
    }

    private fun Paint.withColor(col: Int) = Paint(this).apply { color = col }

    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val labelRect = RectF()

    /**
     * Texti með dökkum, ávölum bakgrunni svo hann lesist á ljósum flötum (gluggar, hvítir veggir),
     * færður inn fyrir skjábrúnir ef hann myndi annars skerast (t.d. „Gakktu hingað“).
     */
    private fun label(c: Canvas, l: String, x: Float, y: Float, p: Paint) {
        val w = p.measureText(l)
        val pad = 4 * d
        val lx = x.coerceIn(pad * 2, (width - w - pad * 2).coerceAtLeast(pad * 2))
        labelRect.set(lx - pad, y + p.ascent() - pad / 2, lx + w + pad, y + p.descent() + pad / 2)
        c.drawRoundRect(labelRect, 4 * d, 4 * d, labelBg)
        c.drawText(l, lx, y, p)
    }

    /** Varpar heimshnitum á skjáhnit. Skilar (sx, sy, w) eða null. */
    private fun project(vp: FloatArray, p: Vec3): FloatArray {
        val v = floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f)
        val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, vp, 0, v, 0)
        val w = o[3]
        val sx = (o[0] / w + 1f) * 0.5f * width
        val sy = (1f - o[1] / w) * 0.5f * height
        return floatArrayOf(sx, sy, w)
    }

    private fun drawBins(c: Canvas, vp: FloatArray, t: Track) {
        val bins = t.localizer.binsSnapshot()
        val step = max(1, bins.size / 600)
        for (i in bins.indices step step) {
            val b = bins[i]
            val s = project(vp, b.pos)
            if (s[2] <= 0.05f || s[0] < 0 || s[0] > width || s[1] < 0 || s[1] > height) continue
            fill.color = rssiColor(b.rssi, 130)
            c.drawCircle(s[0], s[1], (6 * d / s[2]).coerceIn(2 * d, 4.5f * d), fill)
        }
    }

    private fun drawTarget(
        c: Canvas, cs: CamState, vp: FloatArray, t: Track, e: Estimate, highlight: Boolean,
    ) {
        val rel = e.pos - cs.pos
        val dist = rel.norm()
        val dy = rel.y
        val s = project(vp, e.pos)
        val onScreen = s[2] > 0.05f && s[0] in 0f..width.toFloat() && s[1] in 0f..height.toFloat()
        val col = if (highlight) SELECTED_RGB else OTHER_RGB

        // Hæð frá gólfi ef það er þekkt, annars miðað við símann
        val distLine = if (cs.floorY != null) String.format(Locale.ROOT, "%.1f m · %.1f m frá gólfi", dist, e.pos.y - cs.floorY)
        else String.format(Locale.ROOT, "%.1f m · %s %.1f m", dist, if (dy >= 0) "↑" else "↓", abs(dy))
        // Óvalin tæki: ein stutt lína. Valið tæki: allar upplýsingar.
        val behind = if (t.address in cs.occluded) " · bak við" else ""
        val lines = if (!highlight) listOf(String.format(Locale.ROOT, "%s · %.1f m%s", t.label().take(16), dist, behind)) else listOf(
            "${t.kind.icon} ${t.label()}",
            distLine,
            String.format(Locale.ROOT, "± %.1f m (hæð ± %.1f)", e.horizSigma(), e.sigma.y),
        )

        if (onScreen) {
            // Óvissa og strik niður á gólf eru teiknuð í þrívídd (SceneRenderer); hér bara tákn og texti
            if (t.kind == DeviceKind.UNKNOWN) {
                fill.color = col
                c.drawCircle(s[0], s[1], (if (highlight) 9 else 6) * d, fill)
            } else {
                // Tákn í dökkum hring, ramminn í lit (rautt = valið), daufari ef matið er óvisst
                fill.color = Color.argb((90 + 110 * e.confidence()).toInt(), 0, 0, 0)
                // Á bak við eitthvað: táknið daufara
                iconPaint.alpha = if (behind.isEmpty()) 255 else 110
                c.drawCircle(s[0], s[1], 14 * d, fill)
                stroke.color = col
                c.drawCircle(s[0], s[1], 14 * d, stroke)
                c.drawText(t.kind.icon, s[0], s[1] + 6 * d, iconPaint)
            }
            var ty = s[1] - 18 * d
            for (l in lines.reversed()) { label(c, l, s[0] + 18 * d, ty, if (l === lines[0] && highlight) text else small); ty -= 19 * d }
            targetHits += PointF(s[0], s[1]) to t.address
        } else if (highlight) {
            // Bara valið tæki fær ör á skjábrún; annars fyllist brúnin af örvum
            drawEdgeArrow(c, cs, e.pos, col, lines)
        }

        if (highlight && e.ambiguous && e.mirror != null) {
            val m = project(vp, e.mirror)
            if (m[2] > 0.05f) {
                stroke.color = Color.WHITE
                c.drawCircle(m[0], m[1], 10 * d, stroke)
                c.drawText("?", m[0] - 4 * d, m[1] + 5 * d, text)
            }
        }
    }

    /** Grænn hringur þar sem best er að ganga til að bæta mat valins tækis. */
    private fun drawSuggestion(c: Canvas, cs: CamState, vp: FloatArray, p: Vec3) {
        val s = project(vp, p)
        val onScreen = s[2] > 0.05f && s[0] in 0f..width.toFloat() && s[1] in 0f..height.toFloat()
        if (!onScreen) { drawEdgeArrow(c, cs, p, green, listOf("Gakktu hingað")); return }
        // Græni hringurinn sjálfur er á gólfinu í þrívídd (SceneRenderer)
        label(c, "Gakktu hingað", s[0] - 45 * d, s[1] - 14 * d, text.withColor(green))
    }

    /** Ör á skjábrún sem bendir í átt að hlut utan sjónsviðs (eða fyrir aftan). */
    private fun drawEdgeArrow(c: Canvas, cs: CamState, p: Vec3, col: Int, lines: List<String>) {
        val v = FloatArray(4)
        Matrix.multiplyMV(v, 0, cs.view, 0, floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f), 0)
        // Í myndavélarhnitum: x hægri, y upp, −z fram. Skjár: y niður.
        var dx = v[0]; var dy = -v[1]
        if (abs(dx) < 1e-3f && abs(dy) < 1e-3f) dx = 1f
        val ang = atan2(dy, dx)
        val cx = width / 2f; val cy = height / 2f
        val margin = 40 * d
        val hx = cx - margin; val hy = cy - margin * 3
        val k = min(hx / max(abs(cos(ang)), 1e-3f), hy / max(abs(sin(ang)), 1e-3f))
        val ax = cx + cos(ang) * k; val ay = cy + sin(ang) * k
        val path = Path().apply {
            moveTo(ax + cos(ang) * 18 * d, ay + sin(ang) * 18 * d)
            lineTo(ax + cos(ang + 2.5f) * 14 * d, ay + sin(ang + 2.5f) * 14 * d)
            lineTo(ax + cos(ang - 2.5f) * 14 * d, ay + sin(ang - 2.5f) * 14 * d)
            close()
        }
        fill.color = col
        c.drawPath(path, fill)
        val tx = (ax - 60 * d).coerceIn(4 * d, width - 160 * d)
        var ty = ay - 24 * d
        for (l in lines) { label(c, l, tx, ty, small); ty += 18 * d }
    }

    /** Teiknar tækjalistann neðst og skilar efri brún hans. */
    private fun drawList(c: Canvas, cs: CamState?): Float {
        rowRects.clear()
        val now = SystemClock.elapsedRealtimeNanos()
        val f = filter
        val seen = store.all().filter { now - it.lastSeenNanos < 15_000_000_000L || it.address == selected }
        val shown = seen.filter { it.address == selected || f.matches(it) }
        val recent = shown
            .sortedByDescending { if (it.address == selected) 999.0 else it.avgRssi }
            .take(6)
        // Ein lína á tæki svo listinn taki sem minnst af myndavélarmyndinni
        val rowH = 30 * d
        val header = if (f.isActive) 22 * d else 0f
        if (recent.isEmpty() && !f.isActive) return height.toFloat()
        val top = height - rowH * recent.size - 8 * d - header
        c.drawRect(0f, top - 4 * d, width.toFloat(), height.toFloat(), panel)
        if (f.isActive) {
            val line = "Sía: ${shown.size} af ${seen.size} tækjum · ${f.describe()}"
            val k = small.breakText(line, true, width - 24 * d, null)
            c.drawText(line.take(k), 12 * d, top + 14 * d, small.withColor(Color.YELLOW))
        }
        recent.forEachIndexed { i, t ->
            val y0 = top + header + i * rowH
            val rect = RectF(0f, y0, width.toFloat(), y0 + rowH)
            rowRects.add(rect to t.address)
            if (t.address == selected) {
                fill.color = Color.argb(90, 255, 80, 60); c.drawRect(rect, fill)
            }
            fill.color = rssiColor(t.avgRssi, 255)
            // Liturinn á punktinum sýnir merkisstyrk, svo hann er ekki skrifaður út
            c.drawCircle(16 * d, y0 + rowH / 2, 5 * d, fill)
            val baseline = y0 + rowH / 2 + 5 * d
            val name = "${t.kind.icon} ${t.label().take(24)}"
            c.drawText(name, 30 * d, baseline, text)
            val e = t.estimate
            val sub = buildList {
                t.detailText()?.let(::add)
                if (e != null && cs != null && cs.tracking) {
                    val sh = e.horizSigma()
                    add(if (sh > 5) "óviss staðsetning"
                    else String.format(Locale.ROOT, "%.1f m ± %.1f", (e.pos - cs.pos).norm(), sh))
                }
            }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                val x = 30 * d + text.measureText(name) + 8 * d
                val k = small.breakText(sub, true, width - x - 8 * d, null)
                if (k > 0) c.drawText(sub.take(k), x, baseline, dim)
            }
        }
        return top - 4 * d
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Merkingarhamur: pikk utan lista fer í hit test
        if (markingFor != null && rowRects.none { it.first.contains(ev.x, ev.y) } && ev.y > 66 * d) {
            if (ev.action == MotionEvent.ACTION_UP) onMarkTap?.invoke(ev.x, ev.y)
            return true
        }
        if (ev.y < 66 * d && onStatusTap != null) {
            if (ev.action == MotionEvent.ACTION_UP) onStatusTap?.invoke()
            return true
        }
        val addr = rowRects.firstOrNull { it.first.contains(ev.x, ev.y) }?.second
            ?: targetHits.map { it.second to hypot(it.first.x - ev.x, it.first.y - ev.y) }
                .filter { it.second < 32 * d }.minByOrNull { it.second }?.first
            ?: return false
        if (ev.action == MotionEvent.ACTION_UP) {
            selected = if (selected == addr) null else addr
            invalidate()
        }
        return true
    }

    companion object {
        /** Neðri brún hnappanna (dp): topMargin 70 + hæð ~50 í MainActivity. */
        const val BUTTONS_BOTTOM = 122f

        val SELECTED_RGB = Color.rgb(255, 80, 60)
        val OTHER_RGB = Color.rgb(80, 220, 255)

        /** Tæki sem eru sýnd í myndavélinni, fjarlægust fyrst. Sama listi í 2D og 3D. */
        /** Mest þetta mörg tæki í myndinni í einu (fyrir utan það valda), þau öruggustu. */
        const val MAX_SHOWN = 8

        /**
         * Tæki sem eru sýnd í myndavélinni, fjarlægust fyrst. Sama listi í 2D og 3D.
         * Staðsett tæki haldast inni þó þau hætti að senda; óvissari tæki þurfa að hafa
         * sést nýlega, og þau sem eru óvissari en ± 1.5 m eru ekki sýnd nema þau séu valin.
         */
        fun visibleTargets(store: TrackStore, f: Filter, sel: Track?, phone: Vec3): List<Pair<Track, Estimate>> {
            if (sel != null) return listOfNotNull(sel.estimate?.let { sel to it })
            val nowNs = SystemClock.elapsedRealtimeNanos()
            return store.all()
                .filter { f.matches(it) }
                .mapNotNull { t -> t.estimate?.let { e -> t to e } }
                .filter { (t, e) ->
                    e.horizSigma() <= 1.5 && f.showEstimate(e, phone) &&
                        (e.isLocated() || nowNs - t.lastSeenNanos < 60_000_000_000L)
                }
                .sortedBy { (_, e) -> e.uncertainty() }
                .take(MAX_SHOWN)
                .sortedByDescending { (_, e) -> (e.pos - phone).norm() }
        }

        /** -95 dBm → blátt, -40 dBm → rautt. */
        fun rssiColor(rssi: Double, alpha: Int): Int {
            val f = ((rssi + 95.0) / 55.0).coerceIn(0.0, 1.0)
            val hue = (240.0 * (1 - f)).toFloat()
            val rgb = Color.HSVToColor(floatArrayOf(hue, 0.9f, 1f))
            return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        }
    }
}
