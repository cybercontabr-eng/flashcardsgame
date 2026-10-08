package br.dev.nexus.gravadorocr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Apitos, vibrações e voz.
 * - Palavra encontrada: apito no canal de mídia (sai no fone se estiver conectado) + vibração longa + voz.
 * - "Segure firme": duas vibrações curtinhas.
 * - Alarme de gravação parada: sirene no canal de ALARME + vibração forte repetindo até silenciar.
 */
class AlertPlayer(ctx: Context) : TextToSpeech.OnInitListener {
    private val app = ctx.applicationContext
    private val prefs = Prefs(app)
    private val main = Handler(Looper.getMainLooper())

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Vibrator::class.java)
    }

    private val toneMedia: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    private val toneAlarm: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private var alarmRunning = false
    private var alarmStarted = 0L
    private var alarmCount = 0
    private var alarmMessage = ""

    init {
        tts = runCatching { TextToSpeech(app, this) }.getOrNull()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val t = tts ?: return
            val r = t.setLanguage(Locale.forLanguageTag("pt-BR"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS sem português; usando idioma padrão")
            }
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ttsReady = true
        }
    }

    val isAlarming: Boolean get() = alarmRunning

    fun found(label: String) {
        if (prefs.beep) beep(toneMedia, ToneGenerator.TONE_PROP_BEEP2, 450)
        if (prefs.vibrate) vibrate(longArrayOf(0, 400, 150, 400, 150, 700))
        if (prefs.voice) main.postDelayed({ speak("Encontrei: $label") }, 500)
    }

    fun holdSteady() {
        if (prefs.vibrate) vibrate(longArrayOf(0, 45, 90, 45))
    }

    /** Aviso de segurança: sempre vibra (mesmo com vibração de palavras desligada). */
    fun warning(message: String, speakIt: Boolean = true) {
        beep(toneAlarm, ToneGenerator.TONE_PROP_NACK, 500)
        vibrate(longArrayOf(0, 250, 150, 250, 150, 250))
        if (speakIt && prefs.voice) speak(message)
    }

    fun ack() {
        vibrate(longArrayOf(0, 70))
    }

    fun startAlarm(message: String) {
        alarmMessage = message
        if (alarmRunning) return
        alarmRunning = true
        alarmStarted = SystemClock.elapsedRealtime()
        alarmCount = 0
        main.post(alarmLoop)
    }

    private val alarmLoop = object : Runnable {
        override fun run() {
            if (!alarmRunning) return
            val elapsed = SystemClock.elapsedRealtime() - alarmStarted
            beep(toneAlarm, ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1400)
            vibrate(longArrayOf(0, 700, 250, 700))
            if (alarmCount % 4 == 0) main.postDelayed({ if (alarmRunning) speak(alarmMessage, force = true) }, 1500)
            alarmCount++
            // Depois de 5 minutos tocando, passa a lembrar a cada 30 s para poupar bateria.
            main.postDelayed(this, if (elapsed > 5 * 60_000) 30_000 else 3_000)
        }
    }

    fun stopAlarm() {
        alarmRunning = false
        main.removeCallbacks(alarmLoop)
        runCatching { toneAlarm?.stopTone() }
        runCatching { vibrator?.cancel() }
    }

    fun speak(text: String, force: Boolean = false) {
        if (!ttsReady) return
        if (!force && !prefs.voice) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "gocr-" + SystemClock.elapsedRealtimeNanos()) }
    }

    private fun beep(gen: ToneGenerator?, tone: Int, ms: Int) {
        runCatching { gen?.startTone(tone, ms) }
    }

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }

    fun release() {
        stopAlarm()
        main.removeCallbacksAndMessages(null)
        runCatching { toneMedia?.release() }
        runCatching { toneAlarm?.release() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        private const val TAG = "AlertPlayer"
    }
}
