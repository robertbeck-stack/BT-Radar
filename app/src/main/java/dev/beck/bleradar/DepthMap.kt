package dev.beck.bleradar

import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dýptarmynd ARCore (fjarlægð að raunverulegum hlutum, mm). Notuð til að sýna þegar tæki er
 * á bak við eitthvað (skjá, vegg): í shader sem gegnsæi og á CPU fyrir 2D merkingar.
 */
class DepthMap {
    var textureId = 0
        private set
    @Volatile var available = false
        private set

    /** 3×3 (dálkaröð) frá NDC skjás (x, y, 1) í hnit dýptaráferðar. */
    val uvTransform = FloatArray(9)

    private var buf: ByteBuffer? = null
    private var w = 0
    private var h = 0
    private var imgW = 0
    private var imgH = 0
    private var loggedFirst = false

    fun init() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Dýpt má ekki brúa milli punkta (lág- og hábæti eru aðskilin)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
    }

    /** Kallað á GL þræði í hverjum ramma. */
    fun update(frame: Frame) {
        val ndc = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val uv = FloatArray(6)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc,
            Coordinates2d.TEXTURE_NORMALIZED, uv)
        uvTransform[0] = uv[2] - uv[0]; uvTransform[1] = uv[3] - uv[1]; uvTransform[2] = 0f
        uvTransform[3] = uv[4] - uv[0]; uvTransform[4] = uv[5] - uv[1]; uvTransform[5] = 0f
        uvTransform[6] = uv[0]; uvTransform[7] = uv[1]; uvTransform[8] = 1f

        val dims = frame.camera.imageIntrinsics.imageDimensions
        imgW = dims[0]; imgH = dims[1]
        try {
            frame.acquireDepthImage16Bits().use { img ->
                val plane = img.planes[0]
                w = img.width; h = img.height
                val rowBytes = w * 2
                val out = buf?.takeIf { it.capacity() == rowBytes * h }
                    ?: ByteBuffer.allocateDirect(rowBytes * h).order(ByteOrder.LITTLE_ENDIAN).also { buf = it }
                val src = plane.buffer
                out.clear()
                for (row in 0 until h) {
                    src.limit(row * plane.rowStride + rowBytes).position(row * plane.rowStride)
                    out.put(src)
                }
                out.flip()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, w, h, 0,
                    GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, out)
                available = true
                if (!loggedFirst) { DebugLog.i("Dýptarkort: ${w}×$h (myndavél ${imgW}×$imgH)"); loggedFirst = true }
            }
        } catch (_: NotYetAvailableException) {
            // Fyrstu rammarnir eftir ræsingu
        }
    }

    /** Fjarlægð (m, eftir sjónás) að raunverulegum hlut á skjápunkti, eða null ef óþekkt. */
    fun depthAt(frame: Frame, sx: Float, sy: Float): Double? {
        val b = buf ?: return null
        if (!available || imgW == 0) return null
        val out = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW, floatArrayOf(sx, sy), Coordinates2d.IMAGE_PIXELS, out)
        val px = (out[0] * w / imgW).toInt()
        val py = (out[1] * h / imgH).toInt()
        if (px !in 0 until w || py !in 0 until h) return null
        val mm = b.getShort((py * w + px) * 2).toInt() and 0xFFFF
        return if (mm == 0) null else mm / 1000.0
    }
}
