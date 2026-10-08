package br.dev.nexus.gravadorocr.core

enum class HealthEvent {
    RECORDING_STALLED,
    OCR_STALLED,
    OCR_RESUMED,
    CAMERA_COVERED,
    CAMERA_UNCOVERED,
    LOW_STORAGE,
    CRITICAL_STORAGE,
    LOW_BATTERY,
    CRITICAL_BATTERY,
}

/**
 * "Vigia" da gravação. Recebe sinais (progresso do vídeo, quadros analisados, bateria, espaço)
 * e devolve eventos uma única vez por problema, para não ficar repetindo alarme.
 */
class HealthMonitor(
    private val stallMs: Long = 8_000,
    private val ocrStallMs: Long = 12_000,
    private val darkMs: Long = 10_000,
    private val darkRepeatMs: Long = 60_000,
    private val darkLuma: Int = 22,
    private val lowStorageBytes: Long = 1_000_000_000L,
    private val criticalStorageBytes: Long = 250_000_000L,
    private val lowBatteryPct: Int = 15,
    private val criticalBatteryPct: Int = 4,
) {
    private var recording = false
    private var lastProgress = 0L
    private var stallReported = false

    private var lastFrame = 0L
    private var ocrStallReported = false

    private var darkSince = -1L
    private var covered = false
    private var lastCoveredAlert = 0L

    private var lowStorageReported = false
    private var criticalStorageReported = false
    private var lowBatteryReported = false
    private var criticalBatteryReported = false

    val isRecording: Boolean get() = recording

    fun onRecordingStarted(now: Long) {
        recording = true
        lastProgress = now
        stallReported = false
        if (lastFrame < now) lastFrame = now
        ocrStallReported = false
    }

    fun onRecordingStopped() {
        recording = false
    }

    fun onProgress(now: Long) {
        lastProgress = now
        stallReported = false
    }

    fun onFrame(now: Long, luma: Int): List<HealthEvent> {
        val out = ArrayList<HealthEvent>(1)
        lastFrame = now
        if (ocrStallReported) {
            ocrStallReported = false
            out.add(HealthEvent.OCR_RESUMED)
        }
        if (luma < darkLuma) {
            if (darkSince < 0) darkSince = now
            if (now - darkSince >= darkMs && (!covered || now - lastCoveredAlert >= darkRepeatMs)) {
                covered = true
                lastCoveredAlert = now
                out.add(HealthEvent.CAMERA_COVERED)
            }
        } else {
            darkSince = -1
            if (covered) {
                covered = false
                out.add(HealthEvent.CAMERA_UNCOVERED)
            }
        }
        return out
    }

    fun tick(now: Long): List<HealthEvent> {
        if (!recording) return emptyList()
        val out = ArrayList<HealthEvent>(1)
        if (!stallReported && now - lastProgress >= stallMs) {
            stallReported = true
            out.add(HealthEvent.RECORDING_STALLED)
        }
        if (!ocrStallReported && now - lastFrame >= ocrStallMs) {
            ocrStallReported = true
            out.add(HealthEvent.OCR_STALLED)
        }
        return out
    }

    fun onStorage(freeBytes: Long): HealthEvent? {
        if (freeBytes < 0) return null
        if (freeBytes < criticalStorageBytes && !criticalStorageReported) {
            criticalStorageReported = true
            lowStorageReported = true
            return HealthEvent.CRITICAL_STORAGE
        }
        if (freeBytes < lowStorageBytes && !lowStorageReported) {
            lowStorageReported = true
            return HealthEvent.LOW_STORAGE
        }
        return null
    }

    fun onBattery(pct: Int, charging: Boolean): HealthEvent? {
        if (pct < 0) return null
        if (charging) {
            lowBatteryReported = false
            criticalBatteryReported = false
            return null
        }
        if (pct <= criticalBatteryPct && !criticalBatteryReported) {
            criticalBatteryReported = true
            lowBatteryReported = true
            return HealthEvent.CRITICAL_BATTERY
        }
        if (pct <= lowBatteryPct && !lowBatteryReported) {
            lowBatteryReported = true
            return HealthEvent.LOW_BATTERY
        }
        return null
    }
}
