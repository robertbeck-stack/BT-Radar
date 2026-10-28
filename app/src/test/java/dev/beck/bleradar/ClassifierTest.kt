package dev.beck.bleradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifierTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun kindOf(
        name: String? = null,
        mfg: Map<Int, ByteArray> = emptyMap(),
        services: Set<Int> = emptySet(),
        serviceData: Map<Int, ByteArray> = emptyMap(),
        appearance: Int? = null,
    ) = Classifier.classify(name, mfg, services, serviceData, appearance)

    @Test fun nameRules() {
        assertEquals(DeviceKind.PHONE, kindOf("Robert's iPhone").kind)
        assertEquals(DeviceKind.HEADPHONES, kindOf("Pixel Buds Pro").kind)
        assertEquals(DeviceKind.WATCH, kindOf("Galaxy Watch6 (A1B2)").kind)
        assertEquals(DeviceKind.TABLET, kindOf("Galaxy Tab S9").kind)
        assertEquals(DeviceKind.SENSOR, kindOf("Aranet4 0971E").kind)
        assertEquals(DeviceKind.COMPUTER, kindOf("DESKTOP-4KQ1").kind)
        assertEquals(DeviceKind.UNKNOWN, kindOf("R6y beast").kind)
    }

    @Test fun appleContinuity() {
        // AirPods: Proximity Pairing (0x07)
        val airpods = kindOf(mfg = mapOf(0x004C to bytes(0x07, 0x19, 0x01, 0x0E, 0x20)))
        assertEquals(DeviceKind.HEADPHONES, airpods.kind)
        assertEquals("Apple", airpods.vendor)
        // Nearby Info (0x10) er bara ágiskun um síma
        val nearby = kindOf(mfg = mapOf(0x004C to bytes(0x10, 0x05, 0x01, 0x18, 0x00, 0x00, 0x00)))
        assertEquals(DeviceKind.PHONE, nearby.kind)
        assertEquals(1, nearby.score)
        // Nafn trompar ágiskunina
        assertEquals(DeviceKind.COMPUTER,
            kindOf("MacBook Pro", mfg = mapOf(0x004C to bytes(0x10, 0x01, 0x00))).kind)
    }

    @Test fun microsoftCdp() {
        // Útgáfa 1 (0x20) | Windows 10 desktop (9)
        val g = kindOf(mfg = mapOf(0x0006 to bytes(0x01, 0x29, 0x00, 0x00)))
        assertEquals(DeviceKind.COMPUTER, g.kind)
        assertEquals("Windows", g.vendor)
    }

    @Test fun servicesAndEddystone() {
        assertEquals(DeviceKind.TRACKER, kindOf(services = setOf(0xFEED)).kind)
        assertEquals(DeviceKind.INPUT, kindOf(services = setOf(0x1812)).kind)
        assertEquals(DeviceKind.TRACKER, kindOf(serviceData = mapOf(0xFEAA to bytes(0x41, 0x00))).kind)
        assertEquals(DeviceKind.BEACON, kindOf(serviceData = mapOf(0xFEAA to bytes(0x10, 0x00))).kind)
    }

    @Test fun appearanceWins() {
        // 0x00C1 = Watch: Sports Watch; vinnur nafn sem bendir á síma
        assertEquals(DeviceKind.WATCH, kindOf("Pixel", appearance = 0x00C1).kind)
        assertEquals(DeviceKind.HEADPHONES, kindOf(appearance = 0x0941).kind)
    }

    @Test fun parseAppearanceFromRaw() {
        // Flags (02 01 06), Appearance (03 19 40 00), nafn (04 09 41 42 43)
        val raw = bytes(0x02, 0x01, 0x06, 0x03, 0x19, 0x40, 0x00, 0x04, 0x09, 0x41, 0x42, 0x43, 0x00, 0x00)
        assertEquals(0x0040, Classifier.appearance(raw))
        assertNull(Classifier.appearance(bytes(0x02, 0x01, 0x06)))
    }
}
