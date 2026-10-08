package br.dev.nexus.gravadorocr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.AudioStats
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import br.dev.nexus.gravadorocr.core.Detection
import br.dev.nexus.gravadorocr.core.HealthEvent
import br.dev.nexus.gravadorocr.core.HealthMonitor
import br.dev.nexus.gravadorocr.core.MatchResult
import br.dev.nexus.gravadorocr.core.SessionLog
import br.dev.nexus.gravadorocr.core.SrtWriter
import br.dev.nexus.gravadorocr.core.TextNormalizer
import br.dev.nexus.gravadorocr.core.TimeFmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serviço em primeiro plano que grava o vídeo e faz o OCR ao mesmo tempo.
 * Por ser um serviço, a gravação continua com a tela apagada ou com outro app aberto.
 */
class RecordingService : LifecycleService(), OcrPipeline.Callback {

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "br.dev.nexus.gravadorocr.START"
        const val ACTION_STOP = "br.dev.nexus.gravadorocr.STOP"
        const val ACTION_SILENCE = "br.dev.nexus.gravadorocr.SILENCE"
        const val ACTION_MARK = "br.dev.nexus.gravadorocr.MARK"
        const val ACTION_RETRY = "br.dev.nexus.gravadorocr.RETRY"

        private const val NOTIF_ONGOING = 42
        private const val NOTIF_ALARM = 43
        private const val NOTIF_DONE = 44
        private const val NOTIF_FOUND_BASE = 1000

        @Volatile
        var current: RecordingService? = null
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java).setAction(ACTION_START))
        }

        private fun send(ctx: Context, action: String) {
            try {
                ctx.startService(Intent(ctx, RecordingService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "Não consegui enviar $action: ${e.message}")
            }
        }

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun silence(ctx: Context) = send(ctx, ACTION_SILENCE)
        fun mark(ctx: Context) = send(ctx, ACTION_MARK)
        fun retry(ctx: Context) = send(ctx, ACTION_RETRY)
    }

    private enum class StopReason { USER, ROLLOVER, RESTART, LOW_BATTERY, LOW_STORAGE }

    private lateinit var prefs: Prefs
    private lateinit var alerts: AlertPlayer
    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var ocrAvailable = true
    @Volatile private var pipeline: OcrPipeline? = null

    private var recording: Recording? = null
    private var recordingSeq = 0
    private var activeRecordingId = -1
    private var stopReason: StopReason? = null

    private val health = HealthMonitor()
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionActive = false
    private var sessionStamp = ""
    private var sessionStartWall = 0L
    private var keywordsAtStart: List<String> = emptyList()
    private var segmentIndex = 0
    private var segmentStartElapsed = 0L
    private var recordedBeforeSegmentMs = 0L
    private var lastDurationNs = 0L
    private var lastSegmentDurMs = 0L
    private var lastLiveUpdate = 0L
    private var audioForSession = false
    private var audioFallbackUsed = false
    private val restartTimes = ArrayDeque<Long>()
    private var detectionSeq = 0L
    private var tickCount = 0
    private var thermalWarned = false
    private var receiverRegistered = false
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate() {
        super.onCreate()
        current = this
        prefs = Prefs(this)
        alerts = AlertPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!sessionActive) startSession() else goForeground()
            ACTION_STOP -> if (sessionActive) requestStop(StopReason.USER) else {
                alerts.stopAlarm()
                Live.update { it.copy(alarm = null) }
                stopSelf()
            }
            ACTION_SILENCE -> silenceAlarm()
            ACTION_MARK -> addMark()
            ACTION_RETRY -> if (sessionActive && recording == null) {
                silenceAlarm()
                restartTimes.clear()
                rebindAndStart()
            }
            else -> if (!sessionActive) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (sessionActive) {
            Log.w(TAG, "Serviço destruído durante a gravação; finalizando arquivo")
            sessionActive = false
            stopReason = StopReason.USER
            runCatching { recording?.stop() }
            writeSessionLog()
        }
        main.removeCallbacksAndMessages(null)
        runCatching { provider?.unbindAll() }
        pipeline?.close()
        pipeline = null
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
        alerts.release()
        releaseWakeLock()
        unregisterMonitors()
        Live.update { it.copy(sessionActive = false, recording = false) }
        Live.frame.value = null
        current = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sessão

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun fgsType(): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (audioForSession) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return t
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(this, NOTIF_ONGOING, buildOngoing(), fgsType())
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground falhou", e)
        false
    }

    private fun startSession() {
        audioForSession = prefs.audio && granted(Manifest.permission.RECORD_AUDIO)
        if (!goForeground() || !granted(Manifest.permission.CAMERA)) {
            Live.update { it.copy(warning = "Não foi possível iniciar: verifique a permissão de câmera.") }
            stopSelf()
            return
        }
        sessionActive = true
        stopReason = null
        segmentIndex = 0
        recordedBeforeSegmentMs = 0
        lastSegmentDurMs = 0
        sessionStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionStartWall = System.currentTimeMillis()
        keywordsAtStart = Keywords.flow.value
        restartTimes.clear()
        audioFallbackUsed = false
        detectionSeq = 0
        tickCount = 0
        thermalWarned = false
        Live.detections.value = emptyList()
        Live.update {
            LiveStatus(sessionActive = true, sessionStartWall = sessionStartWall, audioOn = audioForSession)
        }
        acquireWakeLock()
        registerMonitors()
        val pl = OcrPipeline(prefs, this)
        pipeline = pl
        analysisExecutor.execute { pl.warmUp() }
        bindCamera { startSegment() }
        main.removeCallbacks(tickRunnable)
        main.postDelayed(tickRunnable, 1000)
        alerts.ack()
    }

    private fun requestStop(reason: StopReason) {
        val rec = recording
        if (rec != null) {
            stopReason = reason
            rec.stop()
        } else {
            finishSession(messageFor(reason))
        }
    }

    private fun messageFor(reason: StopReason): String? = when (reason) {
        StopReason.LOW_BATTERY -> "Gravação encerrada: bateria muito baixa. O vídeo foi salvo."
        StopReason.LOW_STORAGE -> "Gravação encerrada: pouco espaço livre. O vídeo foi salvo."
        else -> null
    }

    private fun finishSession(message: String?) {
        if (!sessionActive) return
        sessionActive = false
        main.removeCallbacks(tickRunnable)
        main.removeCallbacks(rolloverRunnable)
        main.removeCallbacks(forceRestartRunnable)
        writeSessionLog()
        unregisterMonitors()
        runCatching { provider?.unbindAll() }
        videoCapture = null
        camera = null
        pipeline?.close()
        pipeline = null
        releaseWakeLock()
        alerts.stopAlarm()
        nm()?.cancel(NOTIF_ALARM)
        val parts = Live.status.value.savedSegments.size
        Live.update { it.copy(sessionActive = false, recording = false, alarm = null, warning = message ?: it.warning, hint = null) }
        Live.frame.value = null
        if (message != null) alerts.warning(message) else alerts.ack()
        notifyDone(message ?: "$parts parte(s) salva(s) em Movies/GravadorOCR")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // dá tempo da voz terminar antes de encerrar o serviço
        main.postDelayed({ if (!sessionActive) stopSelf() }, if (message != null) 6000 else 1500)
    }

    // ---------------------------------------------------------------- câmera

    private fun bindCamera(onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!sessionActive) return@addListener
            try {
                val p = future.get()
                provider = p
                bindUseCases(p)
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir câmera", e)
                onUnexpectedStop("não consegui abrir a câmera", noData = true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    /** Tenta a configuração ideal; se o aparelho não aguentar, cai para opções mais leves. */
    private fun bindUseCases(p: ProcessCameraProvider) {
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        val selector = if (prefs.useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val rotation = displayRotation()
        val wanted = when (prefs.quality) {
            0 -> Quality.SD
            2 -> Quality.FHD
            else -> Quality.HD
        }
        val bitrate = when (prefs.quality) {
            0 -> 1_500_000
            2 -> 8_000_000
            else -> 4_000_000
        }
        // Ordem: qualidade escolhida + OCR -> 720p + OCR -> 480p + OCR -> só vídeo (sem OCR).
        val attempts = listOf(
            Triple(wanted, Size(1280, 720), true),
            Triple(Quality.HD, Size(1280, 720), true),
            Triple(Quality.SD, Size(640, 480), true),
            Triple(wanted, Size(640, 480), false),
        ).distinct()
        var lastError: Exception? = null
        for ((quality, analysisSize, withOcr) in attempts) {
            try {
                p.unbindAll()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                    .setTargetVideoEncodingBitRate(bitrate)
                    .build()
                val vc = VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
                val cam = if (withOcr) {
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(analysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                                )
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetRotation(rotation)
                        .build()
                    analysis.setAnalyzer(analysisExecutor, FrameAnalyzer())
                    p.bindToLifecycle(this, selector, vc, analysis)
                } else {
                    p.bindToLifecycle(this, selector, vc)
                }
                videoCapture = vc
                camera = cam
                ocrAvailable = withOcr
                Log.i(TAG, "Câmera configurada: vídeo=$quality análise=$analysisSize ocr=$withOcr")
                cam.cameraInfo.cameraState.observe(this) { st -> onCameraState(st) }
                if (!withOcr) addWarning("Este aparelho não aguenta gravar e ler texto ao mesmo tempo: gravando sem OCR.")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Configuração $quality/$analysisSize/ocr=$withOcr falhou: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Sem configuração de câmera possível")
    }

    private var lastCameraErrorCode = -1

    private fun onCameraState(st: CameraState) {
        val err = st.error
        if (err == null) {
            lastCameraErrorCode = -1
            return
        }
        if (err.code == lastCameraErrorCode) return
        lastCameraErrorCode = err.code
        val msg = when (err.code) {
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Outro app está usando a câmera"
            CameraState.ERROR_CAMERA_DISABLED -> "A câmera foi desativada pelo sistema"
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "Modo 'Não perturbe' bloqueou a câmera"
            CameraState.ERROR_CAMERA_FATAL_ERROR -> "Erro grave na câmera"
            else -> "Erro na câmera (código ${err.code})"
        }
        Log.w(TAG, "CameraState erro: ${err.code}")
        addWarning(msg)
        if (sessionActive && !alerts.isAlarming) alerts.warning("Atenção. $msg.")
    }

    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        private var last = 0L
        override fun analyze(image: ImageProxy) {
            val now = SystemClock.elapsedRealtime()
            val p = pipeline
            if (p == null || TestHooks.ignoreCameraFrames || now - last < prefs.ocrIntervalMs) {
                image.close()
                return
            }
            last = now
            val rotation = image.imageInfo.rotationDegrees
            val bmp: Bitmap? = try {
                image.toBitmap()
            } catch (e: Exception) {
                Log.w(TAG, "toBitmap falhou: ${e.message}")
                null
            } finally {
                image.close()
            }
            if (bmp == null) return
            p.process(FrameUtils.upright(bmp, rotation), now)
        }
    }

    // ---------------------------------------------------------------- gravação

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (!sessionActive || recording != null) return
        val vc = videoCapture
        if (vc == null) {
            onUnexpectedStop("câmera não está pronta", noData = true)
            return
        }
        segmentIndex++
        val name = "GravadorOCR_${sessionStamp}_p" + segmentIndex.toString().padStart(2, '0')
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, MediaSaver.VIDEO_DIR)
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = vc.output.prepareRecording(this, options)
        val withAudio = audioForSession && !audioFallbackUsed && granted(Manifest.permission.RECORD_AUDIO)
        if (withAudio) pending = pending.withAudioEnabled()
        Live.update { it.copy(audioOn = withAudio) }
        segmentStartElapsed = SystemClock.elapsedRealtime()
        lastDurationNs = 0
        val id = ++recordingSeq
        activeRecordingId = id
        val segName = name
        try {
            recording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onVideoEvent(id, segName, ev) }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar gravação", e)
            recording = null
            onUnexpectedStop("falha ao iniciar (${e.message})", noData = true)
        }
    }

    private fun onVideoEvent(id: Int, name: String, ev: VideoRecordEvent) {
        val now = SystemClock.elapsedRealtime()
        val isActive = id == activeRecordingId
        when (ev) {
            is VideoRecordEvent.Start -> if (isActive) {
                segmentStartElapsed = now
                health.onRecordingStarted(now)
                Live.update { it.copy(recording = true, segmentIndex = segmentIndex) }
                scheduleRollover()
                updateOngoing()
            }
            is VideoRecordEvent.Status -> if (isActive) {
                val stats = ev.recordingStats
                if (stats.recordedDurationNanos > lastDurationNs) {
                    lastDurationNs = stats.recordedDurationNanos
                    health.onProgress(now)
                }
                if (lastDurationNs > 45_000_000_000L && restartTimes.isNotEmpty()) restartTimes.clear()
                if (now - lastLiveUpdate >= 250) {
                    lastLiveUpdate = now
                    val st = stats.audioStats.audioState
                    val audioOk = st != AudioStats.AUDIO_STATE_ENCODER_ERROR && st != AudioStats.AUDIO_STATE_SOURCE_ERROR
                    Live.update { it.copy(recordedMs = recordedBeforeSegmentMs + lastDurationNs / 1_000_000, audioOk = audioOk) }
                }
            }
            is VideoRecordEvent.Finalize -> onFinalize(isActive, name, ev)
            else -> Unit
        }
    }

    private fun onFinalize(isActive: Boolean, name: String, ev: VideoRecordEvent.Finalize) {
        val uri = ev.outputResults.outputUri
        val err = ev.error
        val durMs = ev.recordingStats.recordedDurationNanos / 1_000_000
        val saved = uri != Uri.EMPTY && err != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
        Log.i(TAG, "Finalize $name err=$err dur=${durMs}ms saved=$saved active=$isActive reason=$stopReason cause=${ev.cause?.message}")
        val segIdx = segmentFromName(name)
        if (saved) {
            Live.update { it.copy(savedSegments = it.savedSegments + SavedSegment(segIdx, uri.toString(), name, err, durMs)) }
            writeSegmentSrt(segIdx, name)
        }
        if (!isActive) return // gravação antiga (já tratada por timeout)

        recording = null
        activeRecordingId = -1
        main.removeCallbacks(rolloverRunnable)
        health.onRecordingStopped()
        recordedBeforeSegmentMs += durMs
        lastSegmentDurMs = durMs
        Live.update { it.copy(recording = false, recordedMs = recordedBeforeSegmentMs) }

        val reason = stopReason
        stopReason = null
        if (!sessionActive) return
        when (reason) {
            StopReason.USER -> finishSession(null)
            StopReason.ROLLOVER -> startSegment()
            StopReason.RESTART -> rebindAndStart()
            StopReason.LOW_BATTERY, StopReason.LOW_STORAGE -> finishSession(messageFor(reason))
            null -> onUnexpectedStop(describeError(err, ev.cause), noData = !saved || durMs < 1500)
        }
        updateOngoing()
    }

    private fun segmentFromName(name: String): Int = name.substringAfterLast("_p").toIntOrNull() ?: segmentIndex

    private fun describeError(err: Int, cause: Throwable?): String = when (err) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "sem espaço no celular"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "a câmera foi fechada"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "falha no codificador de vídeo"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "nenhum dado gravado"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "limite de tamanho do arquivo"
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "limite de duração"
        VideoRecordEvent.Finalize.ERROR_NONE -> "parou sem motivo aparente"
        else -> "erro $err" + (cause?.message?.let { " ($it)" } ?: "")
    }

    private val rolloverRunnable = Runnable {
        val r = recording
        if (r != null && sessionActive && stopReason == null) {
            stopReason = StopReason.ROLLOVER
            r.stop()
        }
    }

    private fun scheduleRollover() {
        main.removeCallbacks(rolloverRunnable)
        val ms = TestHooks.segmentMsOverride ?: (prefs.segmentMinutes * 60_000L)
        if (ms > 0) main.postDelayed(rolloverRunnable, ms)
    }

    /** Fecha e reabre a câmera e começa uma nova parte do vídeo. */
    private fun restartPipeline() {
        val rec = recording
        if (rec != null) {
            stopReason = StopReason.RESTART
            runCatching { rec.stop() }
            main.removeCallbacks(forceRestartRunnable)
            main.postDelayed(forceRestartRunnable, 6000)
        } else {
            rebindAndStart()
        }
    }

    private val forceRestartRunnable = Runnable {
        if (!sessionActive) return@Runnable
        Log.w(TAG, "Gravação não finalizou a tempo; forçando reinício")
        activeRecordingId = -1
        recording = null
        stopReason = null
        health.onRecordingStopped()
        rebindAndStart()
    }

    private fun rebindAndStart() {
        main.removeCallbacks(forceRestartRunnable)
        if (!sessionActive || recording != null) return
        val p = provider
        if (p == null) {
            bindCamera { startSegment() }
            return
        }
        try {
            bindUseCases(p)
            startSegment()
        } catch (e: Exception) {
            Log.e(TAG, "Rebind falhou", e)
            onUnexpectedStop("não consegui reabrir a câmera", noData = true)
        }
    }

    private fun onUnexpectedStop(msg: String, noData: Boolean) {
        if (!sessionActive) return
        val now = SystemClock.elapsedRealtime()
        // Se nem começou e o áudio estava ligado, tenta de novo sem áudio (microfone ocupado/quebrado).
        if (noData && audioForSession && !audioFallbackUsed) {
            audioFallbackUsed = true
            addWarning("Microfone falhou: continuando a gravação SEM áudio")
            alerts.warning("Atenção. Gravando sem áudio.")
            main.postDelayed({ rebindAndStart() }, 800)
            return
        }
        while (restartTimes.isNotEmpty() && now - restartTimes.first() > 120_000) restartTimes.removeFirst()
        if (prefs.autoRestart && restartTimes.size < 3) {
            restartTimes.addLast(now)
            Live.update { it.copy(restarts = it.restarts + 1) }
            addWarning("A gravação parou ($msg). Retomando automaticamente…")
            alerts.warning("Atenção. A gravação parou. Tentando retomar.")
            main.postDelayed({ rebindAndStart() }, 1200)
        } else {
            raiseAlarm("A GRAVAÇÃO PAROU: $msg")
        }
    }

    private fun raiseAlarm(msg: String) {
        Log.e(TAG, "ALARME: $msg")
        addWarning(msg)
        Live.update { it.copy(alarm = msg, recording = false) }
        alerts.startAlarm("Atenção! A gravação parou!")
        notifyAlarm(msg)
        updateOngoing()
    }

    private fun silenceAlarm() {
        alerts.stopAlarm()
        nm()?.cancel(NOTIF_ALARM)
        Live.update { it.copy(alarm = null) }
        if (!sessionActive) stopSelf()
    }

    // ---------------------------------------------------------------- vigia (a cada 1 s)

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            val now = SystemClock.elapsedRealtime()
            for (e in health.tick(now)) onHealth(e)
            tickCount++
            if (tickCount % 15 == 1) checkStorage()
            if (tickCount % 5 == 0) updateOngoing()
            main.postDelayed(this, 1000)
        }
    }

    private fun onHealth(e: HealthEvent) {
        if (!sessionActive) return
        when (e) {
            HealthEvent.RECORDING_STALLED -> {
                addWarning("A gravação travou — reiniciando a câmera")
                alerts.warning("Atenção. A gravação travou. Reiniciando.")
                restartPipeline()
            }
            HealthEvent.OCR_STALLED -> if (ocrAvailable) {
                addWarning("A leitura de texto (OCR) parou de receber imagens")
                alerts.warning("Atenção. A leitura de texto parou.")
            }
            HealthEvent.OCR_RESUMED -> Live.update { it.copy(warning = "Leitura de texto voltou ao normal") }
            HealthEvent.CAMERA_COVERED -> if (prefs.darkWarn) {
                addWarning("Câmera escura ou tapada")
                alerts.warning("Câmera tapada.")
            }
            HealthEvent.CAMERA_UNCOVERED -> Live.update { it.copy(warning = null) }
            HealthEvent.LOW_STORAGE -> {
                val gb = Live.status.value.freeBytes / 1e9
                addWarning(String.format(Locale.ROOT, "Pouco espaço livre (%.1f GB)", gb))
                alerts.warning("Atenção. Pouco espaço livre no celular.")
            }
            HealthEvent.CRITICAL_STORAGE -> requestStop(StopReason.LOW_STORAGE)
            HealthEvent.LOW_BATTERY -> {
                addWarning("Bateria baixa (${Live.status.value.batteryPct}%)")
                alerts.warning("Atenção. Bateria baixa.")
            }
            HealthEvent.CRITICAL_BATTERY -> requestStop(StopReason.LOW_BATTERY)
        }
    }

    private fun checkStorage() {
        val path = getExternalFilesDir(null)?.path ?: filesDir.path
        val free = runCatching { StatFs(path).availableBytes }.getOrDefault(-1L)
        Live.update { it.copy(freeBytes = free) }
        health.onStorage(free)?.let { onHealth(it) }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            Live.update { it.copy(batteryPct = pct) }
            health.onBattery(pct, plugged != 0)?.let { onHealth(it) }
        }
    }

    private fun registerMonitors() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && thermalListener == null) {
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE && !thermalWarned && sessionActive) {
                    thermalWarned = true
                    addWarning("Celular muito quente — a câmera pode ser desligada pelo sistema")
                    alerts.warning("Atenção. Celular muito quente.")
                } else if (status < PowerManager.THERMAL_STATUS_MODERATE) {
                    thermalWarned = false
                }
            }
            runCatching { pm.addThermalStatusListener(ContextCompat.getMainExecutor(this), l) }
            thermalListener = l
        }
    }

    private fun unregisterMonitors() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            receiverRegistered = false
        }
        val l = thermalListener
        if (l != null) {
            runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) }
            thermalListener = null
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GravadorOCR:rec").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------- OCR callbacks (thread de análise)

    override fun onFrameAnalyzed(now: Long, luma: Int) {
        main.post {
            if (!sessionActive) return@post
            for (e in health.onFrame(now, luma)) onHealth(e)
        }
    }

    override fun onFound(result: MatchResult, frame: Bitmap, now: Long) {
        val label = result.spec.label
        main.post {
            if (!sessionActive) return@post
            val det = newDetection(Detection.Kind.FOUND, label, result.matchedText, now)
            Live.detections.value = Live.detections.value + det
            Live.update { it.copy(hint = null) }
            alerts.found(label)
            if (prefs.snapshots) {
                val caption = "${TimeFmt.clock(det.sessionOffsetMs)}  $label"
                ioExecutor.execute {
                    val uri = runCatching {
                        val annotated = FrameUtils.annotate(frame, result, caption)
                        val slug = TextNormalizer.normalize(label).replace(' ', '-').take(30).ifEmpty { "palavra" }
                        MediaSaver.saveJpeg(this, annotated, "OCR_${sessionStamp}_${det.id}_$slug")
                    }.getOrNull()
                    if (uri != null) {
                        main.post {
                            Live.detections.value = Live.detections.value.map {
                                if (it.id == det.id) it.copy(snapshotUri = uri.toString()) else it
                            }
                        }
                    }
                    notifyFound(det, frame)
                }
            } else {
                ioExecutor.execute { notifyFound(det, frame) }
            }
            updateOngoing()
        }
    }

    override fun onHoldSteady(result: MatchResult, now: Long) {
        if (!prefs.holdHint) return
        main.post {
            if (!sessionActive) return@post
            alerts.holdSteady()
            Live.update { it.copy(hint = "Segure firme… parece \"${result.spec.label}\"") }
            main.removeCallbacks(clearHint)
            main.postDelayed(clearHint, 2500)
        }
    }

    private val clearHint = Runnable { Live.update { it.copy(hint = null) } }

    // ---------------------------------------------------------------- registros

    private fun newDetection(kind: Detection.Kind, label: String, snippet: String, now: Long): Detection {
        val rec = recording != null
        val segOffset = if (rec) (now - segmentStartElapsed).coerceAtLeast(0) else lastSegmentDurMs
        return Detection(
            id = ++detectionSeq,
            kind = kind,
            label = label,
            snippet = snippet,
            wallTimeMs = System.currentTimeMillis(),
            sessionOffsetMs = recordedBeforeSegmentMs + if (rec) segOffset else 0L,
            segment = segmentIndex,
            segmentOffsetMs = segOffset,
        )
    }

    private fun addWarning(msg: String) {
        Live.update { it.copy(warning = msg) }
        if (!sessionActive) return
        val det = newDetection(Detection.Kind.WARNING, msg, "", SystemClock.elapsedRealtime())
        Live.detections.value = Live.detections.value + det
    }

    private fun addMark() {
        if (!sessionActive) return
        val det = newDetection(Detection.Kind.MARK, "Marcação manual", "", SystemClock.elapsedRealtime())
        Live.detections.value = Live.detections.value + det
        alerts.ack()
    }

    private fun writeSegmentSrt(segIdx: Int, name: String) {
        val content = SrtWriter.forSegment(Live.detections.value, segIdx) ?: return
        ioExecutor.execute { runCatching { MediaSaver.saveText(this, "$name.srt", "application/x-subrip", content) } }
    }

    private fun writeSessionLog() {
        val segs = Live.status.value.savedSegments.map { SessionLog.Segment(it.index, it.name, it.durationMs, it.error) }
        val text = SessionLog.build(sessionStartWall, keywordsAtStart, segs, Live.detections.value)
        val name = "GravadorOCR_${sessionStamp}_relatorio.txt"
        val exec = if (ioExecutor.isShutdown) null else ioExecutor
        val job = Runnable { runCatching { MediaSaver.saveText(this, name, "text/plain", text) } }
        if (exec != null) exec.execute(job) else job.run()
    }

    // ---------------------------------------------------------------- notificações

    private fun nm(): NotificationManager? = getSystemService(NotificationManager::class.java)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildOngoing(): Notification {
        val st = Live.status.value
        val title = when {
            st.alarm != null -> "⚠ A gravação parou!"
            st.recording -> "● Gravando " + TimeFmt.clock(st.recordedMs)
            sessionActive -> "Preparando a câmera…"
            else -> "Gravador OCR"
        }
        val found = Live.detections.value.count { it.kind == Detection.Kind.FOUND }
        val text = "${Keywords.flow.value.size} palavra(s) • $found detecção(ões)"
        return NotificationCompat.Builder(this, Notifs.CH_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Parar", serviceIntent(ACTION_STOP, 1))
            .addAction(0, "Marcar momento", serviceIntent(ACTION_MARK, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateOngoing() {
        if (!sessionActive) return
        runCatching { nm()?.notify(NOTIF_ONGOING, buildOngoing()) }
    }

    private fun notifyFound(det: Detection, frame: Bitmap?) {
        val thumb = frame?.let { runCatching { Bitmap.createScaledBitmap(it, 256, (256f * it.height / it.width).toInt().coerceAtLeast(1), true) }.getOrNull() }
        val b = NotificationCompat.Builder(this, Notifs.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Encontrado: ${det.label}")
            .setContentText("${TimeFmt.clock(det.sessionOffsetMs)} — \"${det.snippet}\"")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setContentIntent(openAppIntent())
        if (thumb != null) b.setLargeIcon(thumb)
        runCatching { nm()?.notify(NOTIF_FOUND_BASE + (det.id % 500).toInt(), b.build()) }
    }

    private fun notifyAlarm(msg: String) {
        val n = NotificationCompat.Builder(this, Notifs.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("⚠ A gravação parou!")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Tentar de novo", serviceIntent(ACTION_RETRY, 3))
            .addAction(0, "Silenciar", serviceIntent(ACTION_SILENCE, 4))
            .addAction(0, "Encerrar", serviceIntent(ACTION_STOP, 5))
            .build()
        runCatching { nm()?.notify(NOTIF_ALARM, n) }
    }

    private fun notifyDone(text: String) {
        val n = NotificationCompat.Builder(this, Notifs.CH_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Gravação encerrada")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { nm()?.notify(NOTIF_DONE, n) }
    }

    // ---------------------------------------------------------------- ganchos de teste

    /** Processa uma imagem como se viesse da câmera (usado nos testes automáticos). */
    fun debugProcessFrame(bitmap: Bitmap) {
        analysisExecutor.execute { pipeline?.process(bitmap, SystemClock.elapsedRealtime()) }
    }

    /** Simula a gravação parando sozinha (usado nos testes automáticos). */
    fun debugSimulateUnexpectedStop() {
        main.post { recording?.stop() }
    }
}
