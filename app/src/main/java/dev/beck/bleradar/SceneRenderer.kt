package dev.beck.bleradar

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Teiknar tækin í þrívídd inn í ARCore senuna: hálfgegnsær sporvölur (stærð = óvissa á
 * hverjum ás), heill kjarni í miðju, strik niður á gólf og skuggi á gólfinu. Allt er sama
 * kúlunetið, skalað: strikið er mjór sporvölur og gólfskugginn flatur.
 */
class SceneRenderer {

    /** [confidence] 0–1: hve vel búið er að staðsetja tækið (sjá Estimate.confidence). */
    /** Hringur á gólfinu í kringum valið tæki: grænt = mæld átt, appelsínugult = vantar. */
    class Ring(val center: Vec3, val r: Double, val sectors: BooleanArray)

    class Item(val pos: Vec3, val sigma: Vec3, val color: FloatArray, val selected: Boolean, val confidence: Float)

    private var program = 0
    private var aPos = 0
    private var uViewProj = 0
    private var uCenter = 0
    private var uScale = 0
    private var uColor = 0
    private var uCamPos = 0
    private var uRim = 0
    private var uView = 0
    private var uViewport = 0
    private var uDepthTex = 0
    private var uDepthUv = 0
    private var uHasDepth = 0

    private lateinit var verts: FloatBuffer
    private lateinit var indices: ShortBuffer
    private var indexCount = 0

    fun init() {
        // Fínt net svo bólan sé slétt og fletir sjáist ekki
        buildSphere(32, 48)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VS))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FS))
            GLES20.glLinkProgram(it)
        }
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            DebugLog.w("3D shader tengdist ekki: ${GLES20.glGetProgramInfoLog(program)}")
            program = 0
            return
        }
        DebugLog.i("3D sena tilbúin ($indexCount vísar)")
        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        uViewProj = GLES20.glGetUniformLocation(program, "u_ViewProj")
        uCenter = GLES20.glGetUniformLocation(program, "u_Center")
        uScale = GLES20.glGetUniformLocation(program, "u_Scale")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
        uCamPos = GLES20.glGetUniformLocation(program, "u_CamPos")
        uRim = GLES20.glGetUniformLocation(program, "u_Rim")
        uView = GLES20.glGetUniformLocation(program, "u_View")
        uViewport = GLES20.glGetUniformLocation(program, "u_Viewport")
        uDepthTex = GLES20.glGetUniformLocation(program, "u_DepthTex")
        uDepthUv = GLES20.glGetUniformLocation(program, "u_DepthUv")
        uHasDepth = GLES20.glGetUniformLocation(program, "u_HasDepth")
    }

    fun draw(
        viewProj: FloatArray, view: FloatArray, viewW: Int, viewH: Int, depth: DepthMap,
        cam: Vec3, items: List<Item>, floorY: Double?, suggest: Vec3?, truth: Vec3? = null,
        ring: Ring? = null,
    ) {
        if (program == 0) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES20.glUniformMatrix4fv(uView, 1, false, view, 0)
        GLES20.glUniform2f(uViewport, viewW.toFloat(), viewH.toFloat())
        // Raunveruleg dýpt: það sem er á bak við raunverulega hluti verður dauft
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depth.textureId)
        GLES20.glUniform1i(uDepthTex, 1)
        GLES20.glUniformMatrix3fv(uDepthUv, 1, false, depth.uvTransform, 0)
        GLES20.glUniform1f(uHasDepth, if (depth.available) 1f else 0f)
        GLES20.glUniform3f(uCamPos, cam.x.toFloat(), cam.y.toFloat(), cam.z.toFloat())
        verts.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, verts)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)

        // Strik og skuggi bara fyrir tæki sem eru nokkurn veginn staðsett (eða valin)
        val anchored = items.filter { it.selected || it.confidence > 0.3f }

        // 1) Heilir hlutir með dýptarskrift: kjarni og strik niður á gólf
        GLES20.glDepthMask(true)
        for (it in items) {
            val c = it.color
            val core = if (it.selected) 0.06 else 0.04
            mesh(it.pos, Vec3(core, core, core), c[0], c[1], c[2], 0.5f + 0.5f * it.confidence, rim = 0f)
        }
        if (floorY != null) for (it in anchored) {
            val h = it.pos.y - floorY
            if (h < 0.1) continue
            val c = it.color
            mesh(Vec3(it.pos.x, floorY + h / 2, it.pos.z), Vec3(0.008, h / 2, 0.008), c[0], c[1], c[2],
                0.4f + 0.5f * it.confidence, rim = 0f)
        }

        // Raunstaður sem notandinn merkti: grænn heill punktur
        truth?.let {
            mesh(it, Vec3(0.05, 0.05, 0.05), 0.24f, 0.86f, 0.35f, 1f, rim = 0f)
            if (floorY != null && it.y - floorY > 0.1) {
                val h = it.y - floorY
                mesh(Vec3(it.x, floorY + h / 2, it.z), Vec3(0.006, h / 2, 0.006), 0.24f, 0.86f, 0.35f, 0.9f, rim = 0f)
            }
        }

        // 2) Gegnsætt, aftast fyrst: gólfskuggar, tillaga og bólur
        GLES20.glDepthMask(false)
        if (floorY != null) {
            for (it in anchored) {
                val r = sqrt((it.sigma.x * it.sigma.x + it.sigma.z * it.sigma.z) / 2).coerceIn(0.08, 1.5)
                val c = it.color
                mesh(Vec3(it.pos.x, floorY + 0.005, it.pos.z), Vec3(r, 0.002, r), c[0], c[1], c[2],
                    0.10f + 0.25f * it.confidence, rim = 0f)
            }
            suggest?.let {
                mesh(Vec3(it.x, floorY + 0.008, it.z), Vec3(0.35, 0.002, 0.35), 0.24f, 0.86f, 0.35f, 0.5f, rim = 0f)
            }
            ring?.let { rg ->
                val n = rg.sectors.size
                for (i in 0 until n) {
                    // Sama hornakerfi og Localizer.coverage: −π + (i + ½)·2π/n
                    val a = -PI + (i + 0.5) * 2 * PI / n
                    val p = Vec3(rg.center.x + rg.r * cos(a), floorY + 0.01, rg.center.z + rg.r * sin(a))
                    val disc = (rg.r * PI / n * 0.8).coerceIn(0.08, 0.35)
                    if (rg.sectors[i]) mesh(p, Vec3(disc, 0.002, disc), 0.24f, 0.86f, 0.35f, 0.55f, rim = 0f)
                    else mesh(p, Vec3(disc, 0.002, disc), 1.0f, 0.6f, 0.1f, 0.6f, rim = 0f)
                }
            }
        }
        for (it in items.sortedByDescending { (it.pos - cam).norm() }) {
            // Bólan er óvissan (1σ), en aldrei stærri en 1 m svo hún gleypi ekki herbergið
            val sc = Vec3(
                it.sigma.x.coerceIn(0.08, 1.0),
                it.sigma.y.coerceIn(0.08, 1.0),
                it.sigma.z.coerceIn(0.08, 1.0),
            )
            val rel = it.pos - cam
            val inside = (rel.x / sc.x).let { x -> x * x } + (rel.y / sc.y).let { y -> y * y } +
                (rel.z / sc.z).let { z -> z * z } < 1.0
            // Sterkari litur eftir því sem búið er að núlla betur inn á tækið
            // Dofnar þegar bólan fyllir mikið af skjánum (tæki nálægt), annars hylur hún myndina
            val angular = maxOf(sc.x, sc.y, sc.z) / rel.norm().coerceAtLeast(0.1)
            val fade = (0.3 / angular).toFloat().coerceIn(if (it.selected) 0.4f else 0.2f, 1f)
            val a = ((if (it.selected) 0.20f else 0.08f) + 0.45f * it.confidence) * fade
            val c = it.color
            // Innra borðið fyrst (daufara), svo ytra borðið: lítur út eins og sápukúla
            GLES20.glCullFace(GLES20.GL_FRONT)
            mesh(it.pos, sc, c[0], c[1], c[2], a * 0.45f, rim = 1f)
            if (!inside) {
                GLES20.glCullFace(GLES20.GL_BACK)
                mesh(it.pos, sc, c[0], c[1], c[2], a, rim = 1f)
            }
        }

        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun mesh(center: Vec3, scale: Vec3, r: Float, g: Float, b: Float, a: Float, rim: Float) {
        GLES20.glUniform3f(uCenter, center.x.toFloat(), center.y.toFloat(), center.z.toFloat())
        GLES20.glUniform3f(uScale, scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat())
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glUniform1f(uRim, rim)
        indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices)
    }

    /** Einingarkúla; staðsetning hvers hnúts er líka þvervigur hans. */
    private fun buildSphere(rings: Int, segments: Int) {
        val v = ArrayList<Float>()
        for (i in 0..rings) {
            val phi = PI * i / rings
            for (j in 0..segments) {
                val th = 2 * PI * j / segments
                v += (sin(phi) * cos(th)).toFloat(); v += cos(phi).toFloat(); v += (sin(phi) * sin(th)).toFloat()
            }
        }
        val idx = ArrayList<Short>()
        val row = segments + 1
        for (i in 0 until rings) for (j in 0 until segments) {
            val a = i * row + j; val b = a + row
            // Rangsælis séð utan frá
            idx += a.toShort(); idx += (a + 1).toShort(); idx += b.toShort()
            idx += b.toShort(); idx += (a + 1).toShort(); idx += (b + 1).toShort()
        }
        verts = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(v.toFloatArray()); position(0) }
        indices = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            .apply { put(idx.toShortArray()); position(0) }
        indexCount = idx.size
    }

    private fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src)
        GLES20.glCompileShader(it)
    }

    companion object {
        private const val VS = """
            uniform mat4 u_ViewProj;
            uniform mat4 u_View;
            uniform vec3 u_Center;
            uniform vec3 u_Scale;
            attribute vec3 a_Position;
            varying vec3 v_Normal;
            varying vec3 v_World;
            varying float v_ViewDepth;
            void main() {
                v_World = u_Center + a_Position * u_Scale;
                v_ViewDepth = -(u_View * vec4(v_World, 1.0)).z;
                // Þvervigur sporvalar: deilt með skala (andhverfa ummyndunar)
                v_Normal = normalize(a_Position / u_Scale);
                gl_Position = u_ViewProj * vec4(v_World, 1.0);
            }
        """
        private const val FS = """
            precision mediump float;
            uniform vec4 u_Color;
            uniform vec3 u_CamPos;
            uniform float u_Rim;
            uniform sampler2D u_DepthTex;
            uniform mat3 u_DepthUv;
            uniform vec2 u_Viewport;
            uniform float u_HasDepth;
            varying vec3 v_Normal;
            varying vec3 v_World;
            varying float v_ViewDepth;

            // Hlutfall sýnileika: 1 = fyrir framan raunverulega hluti, 0.2 = á bak við þá
            float visibility() {
                if (u_HasDepth < 0.5) return 1.0;
                vec2 ndc = gl_FragCoord.xy / u_Viewport * 2.0 - 1.0;
                vec2 uv = (u_DepthUv * vec3(ndc, 1.0)).xy;
                vec2 la = texture2D(u_DepthTex, uv).ra;
                // Lágbæti í L, hábæti í A; reiknað beint í metrum svo mediump dugi
                float real = la.x * 0.255 + la.y * 65.28;
                if (real <= 0.0) return 1.0;
                float margin = max(0.15, 0.08 * real);
                return mix(1.0, 0.2, smoothstep(real, real + margin, v_ViewDepth));
            }

            void main() {
                vec3 n = normalize(v_Normal);
                vec3 view = normalize(u_CamPos - v_World);
                // Snúa þvervigrinum að myndavél svo innra borðið skyggist líka rétt
                if (dot(n, view) < 0.0) n = -n;
                vec3 light = normalize(vec3(0.3, 1.0, 0.4));
                float diffuse = 0.7 + 0.3 * max(dot(n, light), 0.0);
                // Sápukúla: gegnsæ í miðju, sterkari brún (Fresnel) og glansblettur
                float fres = pow(1.0 - max(dot(n, view), 0.0), 2.5);
                float spec = pow(max(dot(n, normalize(light + view)), 0.0), 60.0);
                float alpha = u_Color.a * mix(1.0, 0.25 + 2.2 * fres, u_Rim) + u_Rim * 0.8 * spec;
                vec3 col = u_Color.rgb * diffuse + u_Rim * spec * vec3(1.0);
                gl_FragColor = vec4(col, clamp(alpha * visibility(), 0.0, 1.0));
            }
        """
    }
}
