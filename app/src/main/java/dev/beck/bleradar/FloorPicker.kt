package dev.beck.bleradar

/**
 * Velur gólf úr láréttum flötum ARCore. Flötur er bara samþykktur ef síminn hefur að jafnaði
 * verið í eðlilegri hæð yfir honum (síma haldið í hendi: 1.0–1.9 m). Það hafnar bæði borðum
 * (of nálægt) og fölskum flötum (of langt niður): í lotu 15:06 greindi ARCore flöt við
 * y = −1.75 sem hefði þýtt að síminn væri að jafnaði í 2.2 m hæð; rétta gólfið var við −1.0.
 */
class FloorPicker(private val window: Int = 600) {
    private val phoneYs = ArrayDeque<Double>()

    /** Hæð símans, kallað reglulega (t.d. í hverjum ramma sem ARCore rekur). */
    fun addPhoneY(y: Double) {
        phoneYs.addLast(y)
        while (phoneYs.size > window) phoneYs.removeFirst()
    }

    fun phoneMedianY(): Double? = phoneYs.sorted().let { if (it.size < 30) null else it[it.size / 2] }

    /** Lægsti flötur sem stenst hæðarprófið, eða null. [planeYs]: hæðir láréttra flata sem snúa upp. */
    fun pick(planeYs: List<Double>): Double? {
        val med = phoneMedianY() ?: return null
        return planeYs.filter { med - it in MIN_ABOVE..MAX_ABOVE }.minOrNull()
    }

    companion object {
        const val MIN_ABOVE = 1.0
        const val MAX_ABOVE = 1.9
    }
}
