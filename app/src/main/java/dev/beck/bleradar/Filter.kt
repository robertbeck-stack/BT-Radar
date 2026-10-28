package dev.beck.bleradar

import android.content.SharedPreferences
import kotlin.math.sqrt

/**
 * Hvaða tæki eru sýnd. Gildir um listann, punktana í myndavélinni og hvaða tæki fá mat
 * (aðeins 20 tæki eru metin í einu, svo sía beinir reikningnum að því sem skiptir máli).
 */
data class Filter(
    /** Orð aðskilin með kommu; tæki sést ef nafn, MAC, tegund eða framleiðandi inniheldur eitthvert þeirra. Tómt = öll. */
    val include: String = "",
    /** Sama snið og [include]; tæki er falið ef nafn, MAC, tegund eða framleiðandi inniheldur eitthvert þeirra. */
    val exclude: String = "",
    val namedOnly: Boolean = false,
    /** Fela tæki með veikara meðalmerki en þetta (dBm). [RSSI_OFF] = ekkert falið. */
    val minRssi: Int = RSSI_OFF,
    /** Fela mat lengra frá símanum en þetta (m). 0 = ótakmarkað. */
    val maxDistM: Int = 0,
    /** Fela mat með meiri láréttri óvissu en þetta (± m). 0 = ótakmarkað. */
    val maxSigmaM: Int = 0,
    /** Sýna bara þessa flokka. Tómt = alla. */
    val kinds: Set<DeviceKind> = emptySet(),
) {
    private val inc = terms(include)
    private val exc = terms(exclude)

    /** Allir flokkar valdir jafngildir engum valdum. */
    private val kindsEff = if (kinds.size >= DeviceKind.entries.size) emptySet() else kinds

    val isActive get() = include.isNotBlank() || exclude.isNotBlank() || namedOnly || minRssi > RSSI_OFF ||
        maxDistM > 0 || maxSigmaM > 0 || kindsEff.isNotEmpty()

    fun matches(t: Track): Boolean {
        val name = t.name?.lowercase() ?: ""
        val addr = t.address.lowercase()
        val kind = if (t.kind == DeviceKind.UNKNOWN) "" else t.kind.label.lowercase()
        val vendor = t.vendor?.lowercase() ?: ""
        val label = t.label().lowercase()
        fun hit(w: String) = name.contains(w) || label.contains(w) || addr.contains(w) || kind.contains(w) ||
            vendor.contains(w)
        if (namedOnly && name.isBlank()) return false
        if (kindsEff.isNotEmpty() && t.kind !in kindsEff) return false
        if (inc.isNotEmpty() && inc.none(::hit)) return false
        if (exc.any(::hit)) return false
        return minRssi <= RSSI_OFF || t.avgRssi >= minRssi
    }

    fun showEstimate(e: Estimate, phone: Vec3): Boolean {
        if (maxDistM > 0 && (e.pos - phone).norm() > maxDistM) return false
        if (maxSigmaM > 0 && e.horizSigma() > maxSigmaM) return false
        return true
    }

    fun describe(): String = buildList {
        if (kindsEff.isNotEmpty()) add(kindsEff.joinToString("") { it.icon })
        if (inc.isNotEmpty()) add("leit: ${inc.joinToString(",")}")
        if (exc.isNotEmpty()) add("fela: ${exc.joinToString(",")}")
        if (namedOnly) add("bara með nafni")
        if (minRssi > RSSI_OFF) add("≥ $minRssi dBm")
        if (maxDistM > 0) add("≤ $maxDistM m")
        if (maxSigmaM > 0) add("± ≤ $maxSigmaM m")
    }.joinToString(" · ").ifEmpty { "engin" }

    fun save(p: SharedPreferences) = p.edit()
        .putString("include", include).putString("exclude", exclude)
        .putBoolean("namedOnly", namedOnly).putInt("minRssi", minRssi)
        .putInt("maxDistM", maxDistM).putInt("maxSigmaM", maxSigmaM)
        .putString("kinds", kinds.joinToString(",") { it.name })
        .apply()

    companion object {
        const val RSSI_OFF = -100

        private fun terms(s: String) = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        fun load(p: SharedPreferences) = Filter(
            include = p.getString("include", "") ?: "",
            exclude = p.getString("exclude", "") ?: "",
            namedOnly = p.getBoolean("namedOnly", false),
            minRssi = p.getInt("minRssi", RSSI_OFF),
            maxDistM = p.getInt("maxDistM", 0),
            maxSigmaM = p.getInt("maxSigmaM", 0),
            kinds = (p.getString("kinds", "") ?: "").split(',')
                .mapNotNull { n -> DeviceKind.entries.firstOrNull { it.name == n } }.toSet(),
        )
    }
}

/** Meðal lárétt óvissa (± m). */
fun Estimate.horizSigma() = sqrt((sigma.x * sigma.x + sigma.z * sigma.z) / 2)

/** Heildaróvissa í 3D (m), til að bera saman mat. */
fun Estimate.uncertainty() = sigma.norm()

/** 0 (± 2 m eða meira) til 1 (± 0.3 m eða minna): hve vel búið er að „núlla inn“ á tækið. */
fun Estimate.confidence() = ((2.0 - horizSigma()) / 1.7).coerceIn(0.0, 1.0)

/** Talið staðsett þegar lárétt óvissa er undir 1 m. */
fun Estimate.isLocated() = horizSigma() <= 1.0
