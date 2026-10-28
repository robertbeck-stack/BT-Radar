package dev.beck.bleradar

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lítill teiknari sem skrifar PNG án java.awt (sem er ekki á classpath Android eininga-prófa).
 * Bara það sem endurspilunin þarf: punktar, línur, hringir og ferhyrningar.
 */
class SimplePng(val width: Int, val height: Int, background: Int = 0xFFFFFF) {
    private val px = IntArray(width * height) { background }

    fun set(x: Int, y: Int, rgb: Int) { if (x in 0 until width && y in 0 until height) px[y * width + x] = rgb }

    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int) {
        for (y in y0..y1) for (x in x0..x1) set(x, y, rgb)
    }

    fun fillCircle(cx: Int, cy: Int, r: Int, rgb: Int) {
        for (y in -r..r) for (x in -r..r) if (x * x + y * y <= r * r) set(cx + x, cy + y, rgb)
    }

    fun circle(cx: Int, cy: Int, r: Int, rgb: Int, thick: Int = 2) {
        val inner = (r - thick).coerceAtLeast(0)
        for (y in -r..r) for (x in -r..r) {
            val d = x * x + y * y
            if (d <= r * r && d >= inner * inner) set(cx + x, cy + y, rgb)
        }
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int, thick: Int = 1) {
        val n = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (i in 0..n) {
            val x = x0 + (x1 - x0) * i / n; val y = y0 + (y1 - y0) * i / n
            if (thick <= 1) set(x, y, rgb) else fillCircle(x, y, thick / 2, rgb)
        }
    }

    /** Krossmerki (t.d. raunstaður). */
    fun cross(cx: Int, cy: Int, r: Int, rgb: Int) {
        line(cx - r, cy - r, cx + r, cy + r, rgb, 4); line(cx - r, cy + r, cx + r, cy - r, rgb, 4)
    }

    fun write(f: File) {
        val raw = ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val c = px[y * width + x]
                raw.write(c shr 16 and 0xFF); raw.write(c shr 8 and 0xFF); raw.write(c and 0xFF)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray()); deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        val out = DataOutputStream(f.outputStream())
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.writeInt(data.size)
            val td = type.toByteArray() + data
            out.write(td)
            out.writeInt(CRC32().apply { update(td) }.value.toInt())
        }
        val hdr = ByteArrayOutputStream().also { DataOutputStream(it).apply {
            writeInt(width); writeInt(height); writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0)
        } }.toByteArray()
        chunk("IHDR", hdr); chunk("IDAT", z.toByteArray()); chunk("IEND", ByteArray(0))
        out.close()
    }

    companion object {
        fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

        /** -95 dBm → blátt, -40 dBm → rautt (sama og í appinu). */
        fun rssiColor(rssi: Int): Int {
            val f = ((rssi + 95.0) / 55.0).coerceIn(0.0, 1.0)
            // HSV(240·(1−f), 0.9, 0.95) → RGB
            val h = 240.0 * (1 - f) / 60.0
            val c = 0.95 * 0.9; val xx = c * (1 - abs(h % 2 - 1)); val m = 0.95 - c
            val (r, g, b) = when (h.toInt()) {
                0 -> Triple(c, xx, 0.0); 1 -> Triple(xx, c, 0.0); 2 -> Triple(0.0, c, xx)
                3 -> Triple(0.0, xx, c); else -> Triple(xx, 0.0, c)
            }
            return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
        }
    }
}
