package dev.beck.bleradar

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid

/**
 * Gróf tegund tækis. Byggt á getgátu út frá auglýsingunni, ekki staðreynd.
 * [height] og [heightSd]: dæmigerð hæð yfir gólfi (m) og óvissa hennar, notuð sem forsenda í mati.
 */
enum class DeviceKind(val icon: String, val label: String, val height: Double, val heightSd: Double) {
    PHONE("📱", "Sími", 0.9, 0.45),            // á borði, í vasa eða hendi
    TABLET("📱", "Spjaldtölva", 0.85, 0.4),
    COMPUTER("💻", "Tölva", 0.8, 0.35),        // á borði; turn undir borði rúmast innan óvissu
    WATCH("⌚", "Úr", 1.1, 0.45),               // á fólki
    HEADPHONES("🎧", "Heyrnartól", 1.1, 0.5),
    SPEAKER("🔊", "Hátalari", 1.0, 0.6),
    TV("📺", "Sjónvarp", 1.1, 0.6),
    TRACKER("🏷️", "Leitarmerki", 0.8, 0.5),
    BEACON("📡", "Beacon", 1.5, 0.9),
    SENSOR("🌡️", "Skynjari", 1.2, 0.8),
    HEALTH("❤️", "Heilsutæki", 1.0, 0.5),
    INPUT("🖱️", "Mús o.fl.", 0.75, 0.25),     // á borði
    CONSOLE("🎮", "Leikjatæki", 0.8, 0.5),
    UNKNOWN("❔", "Óþekkt", 1.0, 0.8),
}

/**
 * [score]: 0 = ekkert vitað, 1 = ágiskun (t.d. „Apple tæki er líklega sími“),
 * 2 = nafn eða sterk vísbending, 3–4 = tækið segir það sjálft.
 */
data class KindGuess(val kind: DeviceKind, val score: Int, val vendor: String?)

/**
 * Flokkar BLE tæki út frá Appearance reit, framleiðandagögnum, þjónustu-UUID og nafni.
 * Hreinu föllin taka einföld gögn svo hægt sé að prófa þau án Android.
 */
object Classifier {
    private val VENDORS = mapOf(
        0x004C to "Apple", 0x0006 to "Microsoft", 0x0075 to "Samsung", 0x00E0 to "Google",
        0x0087 to "Garmin", 0x009E to "Bose", 0x01DA to "Logitech", 0x006B to "Polar",
        0x038F to "Xiaomi", 0x027D to "Huawei", 0x012D to "Sony", 0x00C4 to "LG",
        0x0059 to "Nordic", 0x0499 to "Ruuvi",
    )

    /** Framleiðendur sem framleiða nánast bara eina tegund BLE tækja. */
    private val VENDOR_KIND = mapOf(
        0x0087 to (DeviceKind.WATCH to 1),
        0x006B to (DeviceKind.WATCH to 1),
        0x009E to (DeviceKind.HEADPHONES to 1),
        0x01DA to (DeviceKind.INPUT to 1),
        0x0499 to (DeviceKind.SENSOR to 3),
    )

    /** 16 bita þjónustu-UUID. */
    private val SERVICE_KIND = mapOf(
        0xFD6F to (DeviceKind.PHONE to 3),       // Exposure Notification (bara símar)
        0xFEED to (DeviceKind.TRACKER to 3),     // Tile
        0xFEEC to (DeviceKind.TRACKER to 3),     // Tile
        0xFD84 to (DeviceKind.TRACKER to 3),     // Tile
        0xFD5A to (DeviceKind.TRACKER to 3),     // Samsung SmartTag
        0xFE2C to (DeviceKind.HEADPHONES to 2),  // Google Fast Pair (oftast heyrnartól)
        0x184E to (DeviceKind.HEADPHONES to 2),  // LE Audio: Audio Stream Control
        0x1850 to (DeviceKind.HEADPHONES to 2),  // LE Audio: Published Audio Capabilities
        0x180D to (DeviceKind.WATCH to 2),       // Heart Rate
        0x1812 to (DeviceKind.INPUT to 3),       // HID
        0x181A to (DeviceKind.SENSOR to 3),      // Environmental Sensing
        0x1809 to (DeviceKind.SENSOR to 3),      // Health Thermometer
        0xFE95 to (DeviceKind.SENSOR to 1),      // Xiaomi MiBeacon (oftast skynjarar)
        0x1810 to (DeviceKind.HEALTH to 3),      // Blood Pressure
        0x1808 to (DeviceKind.HEALTH to 3),      // Glucose
        0x181D to (DeviceKind.HEALTH to 3),      // Weight Scale
        0x1822 to (DeviceKind.HEALTH to 3),      // Pulse Oximeter
        0x1814 to (DeviceKind.HEALTH to 2),      // Running Speed and Cadence
        0x1816 to (DeviceKind.HEALTH to 2),      // Cycling Speed and Cadence
    )

    /** Leitarorð í nafni, í forgangsröð (t.d. „pixel buds“ á undan „pixel“). */
    private val NAME_RULES = listOf(
        DeviceKind.HEADPHONES to listOf("airpods", "buds", "beats", "wh-", "wf-", "headphone", "headset",
            "earbud", "jabra", "bose qc", "soundcore life", "pixel buds"),
        DeviceKind.CONSOLE to listOf("xbox", "dualsense", "dualshock", "pro controller", "joy-con", "wireless controller"),
        DeviceKind.TABLET to listOf("ipad", "galaxy tab", "tablet"),
        DeviceKind.COMPUTER to listOf("macbook", "imac", "mac mini", "mac studio", "laptop", "desktop-", "thinkpad",
            "surface", "-pc", "pc-"),
        DeviceKind.WATCH to listOf("watch", "band", "fitbit", "forerunner", "fenix", "amazfit", "whoop", "oura"),
        DeviceKind.TV to listOf("[tv]", " tv", "tv ", "bravia", "chromecast", "roku", "fire tv", "shield"),
        DeviceKind.SPEAKER to listOf("speaker", "jbl", "sonos", "homepod", "echo", "soundlink", "boom", "nest audio",
            "nest mini"),
        DeviceKind.TRACKER to listOf("tile", "smarttag", "airtag", "chipolo"),
        DeviceKind.SENSOR to listOf("aranet", "govee", "gvh5", "lywsd", "mi temp", "ruuvi", "sensor", "thermo",
            "hygro", "atc_", "ibs-th", "sensorpush", "inkbird"),
        DeviceKind.INPUT to listOf("mouse", "keyboard", "mx ", "keys", "trackpad"),
        DeviceKind.HEALTH to listOf("scale", "blood", "glucose", "oximeter"),
        DeviceKind.PHONE to listOf("iphone", "galaxy", "pixel", "oneplus", "redmi", "xperia", "phone"),
    )

    /** Les gögnin úr Android ScanRecord og flokkar. */
    fun classify(r: ScanRecord?, name: String?): KindGuess {
        if (r == null) return classify(name, emptyMap(), emptySet(), emptyMap(), null)
        val mfg = HashMap<Int, ByteArray>()
        r.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) mfg[sa.keyAt(i)] = sa.valueAt(i) }
        val services = r.serviceUuids.orEmpty().mapNotNull(::uuid16).toSet()
        val serviceData = r.serviceData.orEmpty().entries
            .mapNotNull { (u, d) -> uuid16(u)?.let { it to d } }.toMap()
        return classify(name, mfg, services, serviceData, appearance(r.bytes))
    }

    fun classify(
        name: String?,
        mfg: Map<Int, ByteArray>,
        services: Set<Int>,
        serviceData: Map<Int, ByteArray>,
        appearance: Int?,
    ): KindGuess {
        val guesses = ArrayList<KindGuess>()
        appearance?.let(::fromAppearance)?.let(guesses::add)
        for ((id, d) in mfg) {
            val g = when (id) {
                0x004C -> apple(d)
                0x0006 -> microsoft(d)
                else -> VENDOR_KIND[id]?.let { (k, s) -> KindGuess(k, s, VENDORS[id]) }
            }
            g?.let(guesses::add)
        }
        for (u in services + serviceData.keys) {
            SERVICE_KIND[u]?.let { (k, s) -> guesses += KindGuess(k, s, null) }
        }
        serviceData[0xFEAA]?.let { d ->
            // Eddystone; ramma-tegund 0x40/0x41 er Google Find Hub staðsetningarmerki
            val frame = d.firstOrNull()?.toInt()?.and(0xFF)
            guesses += if (frame == 0x40 || frame == 0x41) KindGuess(DeviceKind.TRACKER, 3, "Google")
            else KindGuess(DeviceKind.BEACON, 3, null)
        }
        name?.let(::fromName)?.let(guesses::add)

        val vendor = mfg.keys.firstNotNullOfOrNull { VENDORS[it] }
        val best = guesses.maxByOrNull { it.score } ?: return KindGuess(DeviceKind.UNKNOWN, 0, vendor)
        return best.copy(vendor = best.vendor ?: vendor)
    }

    fun fromName(name: String): KindGuess? {
        val n = name.lowercase()
        for ((kind, words) in NAME_RULES) if (words.any { n.contains(it) }) return KindGuess(kind, 2, null)
        return null
    }

    /** Bluetooth Appearance: efri 10 bitarnir eru flokkurinn. */
    fun fromAppearance(a: Int): KindGuess? {
        val kind = when (a shr 6) {
            1 -> DeviceKind.PHONE
            2 -> DeviceKind.COMPUTER
            3 -> DeviceKind.WATCH
            5 -> DeviceKind.TV
            6, 15 -> DeviceKind.INPUT          // fjarstýring, HID
            8, 9 -> DeviceKind.TRACKER         // tag, lyklakippa
            10, 33 -> DeviceKind.SPEAKER       // spilari, audio sink
            12 -> DeviceKind.SENSOR            // hitamælir
            13, 14, 16, 17, 18, 49, 50 -> DeviceKind.HEALTH
            37 -> DeviceKind.HEADPHONES        // wearable audio device
            else -> return null
        }
        return KindGuess(kind, 4, null)
    }

    /** Apple Continuity: röð af (tegund, lengd, gögn) færslum. */
    fun apple(d: ByteArray): KindGuess {
        var best: KindGuess? = null
        var i = 0
        while (i + 1 < d.size) {
            val type = d[i].toInt() and 0xFF
            val len = d[i + 1].toInt() and 0xFF
            val g = when (type) {
                0x02 -> KindGuess(DeviceKind.BEACON, 3, "Apple")      // iBeacon
                0x07 -> KindGuess(DeviceKind.HEADPHONES, 3, "Apple")  // AirPods/Beats pörun
                0x09 -> KindGuess(DeviceKind.TV, 2, "Apple")          // AirPlay: Apple TV/HomePod
                0x0B -> KindGuess(DeviceKind.WATCH, 2, "Apple")       // Watch (Magic Switch)
                0x12 -> KindGuess(DeviceKind.TRACKER, 2, "Apple")     // Find My: AirTag eða týnt tæki
                // Nearby Info/Handoff/AirDrop: iPhone, iPad eða Mac; oftast sími
                0x05, 0x0C, 0x0F, 0x10 -> KindGuess(DeviceKind.PHONE, 1, "Apple")
                else -> null
            }
            if (g != null && (best == null || g.score > best.score)) best = g
            i += 2 + len
        }
        return best ?: KindGuess(DeviceKind.UNKNOWN, 0, "Apple")
    }

    /** Microsoft CDP beacon: bæti 1 inniheldur tegund tækis í neðri 5 bitunum. */
    fun microsoft(d: ByteArray): KindGuess? {
        if (d.size < 2 || d[0].toInt() != 0x01) return null
        val (kind, vendor) = when (d[1].toInt() and 0x1F) {
            1 -> DeviceKind.CONSOLE to "Xbox"
            6 -> DeviceKind.PHONE to "Apple"
            7 -> DeviceKind.TABLET to "Apple"
            8 -> DeviceKind.PHONE to "Android"
            9, 13, 15 -> DeviceKind.COMPUTER to "Windows"
            11 -> DeviceKind.PHONE to "Windows"
            12 -> DeviceKind.COMPUTER to "Linux"
            14 -> DeviceKind.TV to "Surface Hub"
            16 -> DeviceKind.TABLET to "Windows"
            else -> return null
        }
        return KindGuess(kind, 3, vendor)
    }

    /** Appearance reitur (AD tegund 0x19) úr hráum auglýsingagögnum. */
    fun appearance(b: ByteArray?): Int? {
        if (b == null) return null
        var i = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0 || i + len >= b.size) break
            if ((b[i + 1].toInt() and 0xFF) == 0x19 && len >= 3) {
                return (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
            }
            i += len + 1
        }
        return null
    }

    /** 16 bita stuttgerð ef UUID er á Bluetooth grunnforminu, annars null. */
    private fun uuid16(u: ParcelUuid): Int? {
        val msb = u.uuid.mostSignificantBits
        val base = u.uuid.leastSignificantBits == -0x7fffff7fa064cb05L && (msb and 0xFFFFFFFFL) == 0x1000L
        return if (base && (msb ushr 48) == 0L) ((msb ushr 32) and 0xFFFF).toInt() else null
    }
}
