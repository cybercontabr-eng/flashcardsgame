package br.dev.nexus.gravadorocr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTrackerTest {
    private val spec = KeywordSpec.parse("botijão de gás")!!
    private fun full(d: Int = 0) = MatchResult(spec, MatchKind.FULL, "BOTIJÃO DE GÁS", d, emptyList())
    private fun partial() = MatchResult(spec, MatchKind.PARTIAL, "BOTIJÃO", 3, emptyList())

    @Test
    fun exactMatchAlertsImmediatelyOnlyOnceWhileVisible() {
        val t = DetectionTracker(cooldownMs = 8_000, repeatMs = 60_000)
        assertTrue(t.update(listOf(full()), 0).single() is TrackerEvent.Found)
        for (s in 1..59) assertTrue("t=$s", t.update(listOf(full()), s * 1000L).isEmpty())
        assertTrue(t.update(listOf(full()), 60_000).single() is TrackerEvent.Found)
    }

    @Test
    fun alertsAgainAfterDisappearing() {
        val t = DetectionTracker(cooldownMs = 8_000)
        assertEquals(1, t.update(listOf(full()), 0).size)
        assertTrue(t.update(listOf(full()), 2_000).isEmpty())
        assertTrue(t.update(emptyList(), 5_000).isEmpty())
        assertTrue(t.update(listOf(full()), 12_000).single() is TrackerEvent.Found)
    }

    @Test
    fun fuzzyNeedsConfirmationAndAsksToHold() {
        val t = DetectionTracker()
        assertTrue(t.update(listOf(full(1)), 0).single() is TrackerEvent.HoldSteady)
        assertTrue(t.update(listOf(full(1)), 800).single() is TrackerEvent.Found)
    }

    @Test
    fun fuzzyTooFarApartIsNotConfirmed() {
        val t = DetectionTracker()
        t.update(listOf(full(1)), 0)
        val ev = t.update(listOf(full(1)), 5_000)
        assertTrue(ev.none { it is TrackerEvent.Found })
    }

    @Test
    fun partialAsksToHoldWithCooldown() {
        val t = DetectionTracker()
        assertTrue(t.update(listOf(partial()), 0).single() is TrackerEvent.HoldSteady)
        assertTrue(t.update(listOf(partial()), 1_000).isEmpty())
        assertTrue(t.update(listOf(partial()), 3_000).single() is TrackerEvent.HoldSteady)
    }

    @Test
    fun noHoldRightAfterFound() {
        val t = DetectionTracker(cooldownMs = 8_000)
        t.update(listOf(full()), 0)
        assertTrue(t.update(listOf(partial()), 1_000).isEmpty())
    }
}

class HealthMonitorTest {
    @Test
    fun detectsStalledRecordingOnce() {
        val h = HealthMonitor(stallMs = 8_000, ocrStallMs = 100_000)
        h.onRecordingStarted(0)
        h.onProgress(1_000)
        assertTrue(h.tick(5_000).isEmpty())
        assertEquals(listOf(HealthEvent.RECORDING_STALLED), h.tick(9_500))
        assertTrue(h.tick(10_000).isEmpty())
        h.onProgress(11_000)
        assertTrue(h.tick(12_000).isEmpty())
    }

    @Test
    fun noAlarmsWhenNotRecording() {
        val h = HealthMonitor()
        assertTrue(h.tick(1_000_000).isEmpty())
        h.onRecordingStarted(0)
        h.onRecordingStopped()
        assertTrue(h.tick(1_000_000).isEmpty())
    }

    @Test
    fun ocrStallAndResume() {
        val h = HealthMonitor(stallMs = 100_000, ocrStallMs = 12_000)
        h.onRecordingStarted(0)
        h.onFrame(0, 128)
        assertEquals(listOf(HealthEvent.OCR_STALLED), h.tick(13_000))
        assertEquals(listOf(HealthEvent.OCR_RESUMED), h.onFrame(14_000, 128))
    }

    @Test
    fun coveredCamera() {
        val h = HealthMonitor(darkMs = 10_000, darkRepeatMs = 60_000)
        for (t in 0..9) assertTrue(h.onFrame(t * 1000L, 5).isEmpty())
        assertEquals(listOf(HealthEvent.CAMERA_COVERED), h.onFrame(10_000, 5))
        assertTrue(h.onFrame(11_000, 5).isEmpty())
        assertEquals(listOf(HealthEvent.CAMERA_COVERED), h.onFrame(70_000, 5))
        assertEquals(listOf(HealthEvent.CAMERA_UNCOVERED), h.onFrame(71_000, 120))
    }

    @Test
    fun batteryWarnings() {
        val h = HealthMonitor(lowBatteryPct = 15, criticalBatteryPct = 4)
        assertEquals(null, h.onBattery(50, false))
        assertEquals(HealthEvent.LOW_BATTERY, h.onBattery(15, false))
        assertEquals(null, h.onBattery(14, false))
        assertEquals(HealthEvent.CRITICAL_BATTERY, h.onBattery(4, false))
        assertEquals(null, h.onBattery(3, false))
        assertEquals(null, h.onBattery(3, true))
        assertEquals(HealthEvent.CRITICAL_BATTERY, h.onBattery(3, false))
    }

    @Test
    fun storageWarnings() {
        val h = HealthMonitor(lowStorageBytes = 1_000, criticalStorageBytes = 100)
        assertEquals(null, h.onStorage(5_000))
        assertEquals(HealthEvent.LOW_STORAGE, h.onStorage(900))
        assertEquals(null, h.onStorage(800))
        assertEquals(HealthEvent.CRITICAL_STORAGE, h.onStorage(50))
        assertEquals(null, h.onStorage(40))
    }
}

class SrtAndLogTest {
    @Test
    fun srtTimeFormat() {
        assertEquals("01:02:03,456", SrtWriter.time(3_723_456))
        assertEquals("00:00:00,000", SrtWriter.time(-5))
    }

    @Test
    fun srtForSegment() {
        val d = listOf(
            Detection(1, Detection.Kind.FOUND, "botijão de gás", "BOTIJÃO DE GÁS", 0, 61_000, 2, 1_500),
            Detection(2, Detection.Kind.MARK, "Marcação", "", 0, 70_000, 2, 10_000),
            Detection(3, Detection.Kind.FOUND, "outra", "x", 0, 5_000, 1, 5_000),
        )
        val srt = SrtWriter.forSegment(d, 2)!!
        assertTrue(srt.startsWith("1\n00:00:01,500 --> 00:00:04,500\nENCONTRADO: botijão de gás"))
        assertTrue(srt.contains("2\n00:00:10,000 --> 00:00:13,000\nMARCAÇÃO"))
        assertEquals(null, SrtWriter.forSegment(d, 5))
    }

    @Test
    fun clockFormat() {
        assertEquals("00:05", TimeFmt.clock(5_400))
        assertEquals("1:01:01", TimeFmt.clock(3_661_000))
    }

    @Test
    fun sessionLogContainsEverything() {
        val log = SessionLog.build(
            startWall = 0,
            keywords = listOf("botijão de gás", "#cpf"),
            segments = listOf(SessionLog.Segment(1, "GravadorOCR_x_p01", 600_000, 0)),
            detections = listOf(Detection(1, Detection.Kind.FOUND, "botijão de gás", "BOTIJÃO DE GÁS", 1_000, 1_000, 1, 1_000)),
            zone = java.util.TimeZone.getTimeZone("UTC"),
        )
        assertTrue(log.contains("botijão de gás, Qualquer CPF"))
        assertTrue(log.contains("GravadorOCR_x_p01 — 10:00"))
        assertTrue(log.contains("ENCONTRADO: botijão de gás"))
    }
}
