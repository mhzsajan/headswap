package com.yegorkapomidorka.headswap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseKeepalivePolicyTest {

    @Test
    fun failedPauseIsLearned() {
        assertTrue(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = 4_000L,
                usedHeartbeat = false,
                userStop = false,
                channel = "20082120/usb",
                desayUsb = false,
            )
        )
    }

    @Test
    fun boundaryIsLearned() {
        assertTrue(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = PauseKeepalivePolicy.LEARN_MIN_PAUSE_MS,
                usedHeartbeat = false,
                userStop = false,
                channel = "20772121/air",
                desayUsb = false,
            )
        )
    }

    /** Закрылись раньше 3,5 с: скорее выдернули кабель/вышли из CarLife, чем ГУ не пережило keepalive. */
    @Test
    fun shortPauseIsNotLearned() {
        assertFalse(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = 2_000L,
                usedHeartbeat = false,
                userStop = false,
                channel = "20082120/usb",
                desayUsb = false,
            )
        )
    }

    @Test
    fun userStopIsNotLearned() {
        assertFalse(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = 30_000L,
                usedHeartbeat = false,
                userStop = true,
                channel = "20082120/usb",
                desayUsb = false,
            )
        )
    }

    /** Heartbeat уже слали, значит причина обрыва не в пустом VIDEO_DATA: менять стратегию нечего. */
    @Test
    fun heartbeatAlreadyUsedIsNotLearned() {
        assertFalse(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = 6_000L,
                usedHeartbeat = true,
                userStop = false,
                channel = "20082122/usb",
                desayUsb = false,
            )
        )
    }

    @Test
    fun unknownChannelIsNotLearned() {
        assertFalse(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = 6_000L,
                usedHeartbeat = false,
                userStop = false,
                channel = null,
                desayUsb = false,
            )
        )
        assertFalse(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = 6_000L,
                usedHeartbeat = false,
                userStop = false,
                channel = "   ",
                desayUsb = false,
            )
        )
    }

    /** Desay G6S по USB держит пустой VIDEO_DATA (h211): heartbeat там не проверен, не учим. */
    @Test
    fun desayUsbIsNotLearned() {
        assertFalse(
            PauseKeepalivePolicy.shouldLearn(
                closedWhilePausedMs = 20_000L,
                usedHeartbeat = false,
                userStop = false,
                channel = "desay/usb",
                desayUsb = true,
            )
        )
    }

    // ---------------- выбор keepalive ----------------

    @Test
    fun knownVwNeedsHeartbeatOnUsb() {
        assertTrue(PauseKeepalivePolicy.needsHeartbeat(knownVw = true, air = false, vwBt1420 = false, desayG6S = false, learned = false))
    }

    @Test
    fun learnedChannelNeedsHeartbeatOnUsb() {
        assertTrue(PauseKeepalivePolicy.needsHeartbeat(knownVw = false, air = false, vwBt1420 = false, desayG6S = false, learned = true))
    }

    @Test
    fun unknownUsbKeepsEmptyData() {
        assertFalse(PauseKeepalivePolicy.needsHeartbeat(knownVw = false, air = false, vwBt1420 = false, desayG6S = false, learned = false))
    }

    @Test
    fun desayUsbKeepsEmptyData() {
        assertFalse(PauseKeepalivePolicy.needsHeartbeat(knownVw = false, air = false, vwBt1420 = false, desayG6S = true, learned = false))
    }

    @Test
    fun desayOnAirNeedsHeartbeat() {
        assertTrue(PauseKeepalivePolicy.needsHeartbeat(knownVw = false, air = true, vwBt1420 = false, desayG6S = true, learned = false))
    }

    @Test
    fun vwBt1420OnAirNeedsHeartbeat() {
        assertTrue(PauseKeepalivePolicy.needsHeartbeat(knownVw = false, air = true, vwBt1420 = true, desayG6S = false, learned = false))
    }
}
