package com.example.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host tests for the pure device-class heuristic — no Android runtime needed,
 * so the TV-vs-handheld decision is verified without an emulator.
 */
class DeviceClassTest {

    @Test
    fun leanbackDeviceIsTv() {
        assertEquals(DeviceClass.TV, DeviceClass.classify(isLeanback = true, isTelevisionUiMode = false, hasTouchscreen = false))
    }

    @Test
    fun televisionUiModeIsTv() {
        assertEquals(DeviceClass.TV, DeviceClass.classify(isLeanback = false, isTelevisionUiMode = true, hasTouchscreen = false))
    }

    @Test
    fun noTouchscreenIsTv() {
        assertEquals(DeviceClass.TV, DeviceClass.classify(isLeanback = false, isTelevisionUiMode = false, hasTouchscreen = false))
    }

    @Test
    fun touchDeviceWithoutTvSignalsIsHandheld() {
        assertEquals(DeviceClass.HANDHELD, DeviceClass.classify(isLeanback = false, isTelevisionUiMode = false, hasTouchscreen = true))
    }

    @Test
    fun leanbackWinsEvenWithTouchscreen() {
        // Some TV boxes report a touchscreen feature; leanback should still classify as TV.
        assertEquals(DeviceClass.TV, DeviceClass.classify(isLeanback = true, isTelevisionUiMode = false, hasTouchscreen = true))
    }

    @Test
    fun fromIdRoundTrips() {
        assertEquals(DeviceClass.TV, DeviceClass.fromId("tv"))
        assertEquals(DeviceClass.BOX, DeviceClass.fromId("box"))
        assertEquals(DeviceClass.HANDHELD, DeviceClass.fromId("handheld"))
        assertEquals(DeviceClass.BOX, DeviceClass.fromId(" BOX "))
    }

    @Test
    fun fromIdRejectsUnknownAndNull() {
        assertNull(DeviceClass.fromId(null))
        assertNull(DeviceClass.fromId(""))
        assertNull(DeviceClass.fromId("laptop"))
    }
}
