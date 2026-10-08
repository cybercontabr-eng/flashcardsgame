package br.dev.nexus.gravadorocr

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import br.dev.nexus.gravadorocr.core.DetectionTracker
import br.dev.nexus.gravadorocr.core.KeywordMatcher
import br.dev.nexus.gravadorocr.core.MatchResult
import br.dev.nexus.gravadorocr.core.Tolerance
import br.dev.nexus.gravadorocr.core.TrackerEvent
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * Recebe quadros "em pé", roda o OCR (ML Kit, offline), procura as palavras-chave
 * e avisa o serviço. Deve ser chamado sempre na mesma thread de fundo.
 */
class OcrPipeline(private val prefs: Prefs, private val callback: Callback) {

    interface Callback {
        fun onFrameAnalyzed(now: Long, luma: Int)
        fun onFound(result: MatchResult, frame: Bitmap, now: Long)
        fun onHoldSteady(result: MatchResult, now: Long)
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val tracker = DetectionTracker()
    private var matcher: KeywordMatcher? = null
    private var matcherTolerance: Tolerance? = null

    private var inFlight: Task<Text>? = null
    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    @Volatile var closed = false
        private set

    @WorkerThread
    fun process(upright: Bitmap, now: Long = SystemClock.elapsedRealtime()) {
        if (closed) return
        // Se o quadro anterior ainda está sendo lido (celular lento), pula este para não acumular fila.
        inFlight?.let { if (!it.isComplete) return }
        val luma = FrameUtils.meanLuma(upright)
        callback.onFrameAnalyzed(now, luma)

        val task = recognizer.process(InputImage.fromBitmap(upright, 0))
        inFlight = task
        val text = try {
            Tasks.await(task, 20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "OCR falhou neste quadro: ${e.message}")
            null
        }
        Live.update { it.copy(framesAnalyzed = it.framesAnalyzed + 1) }
        if (closed) return
        val tokens = text?.let { OcrText.tokens(it) } ?: emptyList()
        if (tokens.isNotEmpty() && Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Lido: ${text?.text?.replace('\n', '|')}")

        val tol = prefs.tolerance
        val m = matcher?.takeIf { matcherTolerance == tol } ?: KeywordMatcher(tol).also {
            matcher = it
            matcherTolerance = tol
        }
        val specs = Keywords.specs()
        val results = if (specs.isEmpty()) emptyList() else m.match(tokens, specs)
        tracker.cooldownMs = prefs.cooldownSec * 1000L
        val events = tracker.update(results, now)
        if (results.isNotEmpty()) Log.i(TAG, "Resultados: ${results.joinToString { "${it.spec.label}=${it.kind}/d${it.distance}" }} eventos=${events.size}")

        if (Live.uiVisible) {
            runCatching { Live.frame.value = FrameUtils.monitor(upright, tokens, results) }
        }
        Live.lastText.value = text?.text?.replace('\n', ' ')?.take(240) ?: ""

        fpsFrames++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        val win = now - fpsWindowStart
        if (win >= 3000) {
            val fps = fpsFrames * 1000f / win
            Live.update { it.copy(ocrFps = fps) }
            fpsFrames = 0
            fpsWindowStart = now
        }

        for (e in events) {
            when (e) {
                is TrackerEvent.Found -> callback.onFound(e.result, upright, now)
                is TrackerEvent.HoldSteady -> callback.onHoldSteady(e.result, now)
            }
        }
    }

    fun close() {
        closed = true
        runCatching { recognizer.close() }
    }

    companion object {
        private const val TAG = "OcrPipeline"
    }
}
