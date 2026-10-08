package br.dev.nexus.gravadorocr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import br.dev.nexus.gravadorocr.core.Detection
import br.dev.nexus.gravadorocr.core.KeywordSpec
import br.dev.nexus.gravadorocr.core.Tolerance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import android.graphics.Bitmap

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.createChannels(this)
        Keywords.load(this)
    }
}

/** Configurações do usuário (SharedPreferences). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun setI(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    var toleranceIndex: Int
        get() = i("tolerance", 1)
        set(v) = setI("tolerance", v)
    val tolerance: Tolerance get() = Tolerance.entries.getOrElse(toleranceIndex) { Tolerance.NORMAL }

    var beep: Boolean
        get() = b("beep", true)
        set(v) = setB("beep", v)
    var vibrate: Boolean
        get() = b("vibrate", true)
        set(v) = setB("vibrate", v)
    var voice: Boolean
        get() = b("voice", true)
        set(v) = setB("voice", v)
    var holdHint: Boolean
        get() = b("holdHint", true)
        set(v) = setB("holdHint", v)
    var snapshots: Boolean
        get() = b("snapshots", true)
        set(v) = setB("snapshots", v)
    var audio: Boolean
        get() = b("audio", true)
        set(v) = setB("audio", v)
    var autoRestart: Boolean
        get() = b("autoRestart", true)
        set(v) = setB("autoRestart", v)
    var darkWarn: Boolean
        get() = b("darkWarn", true)
        set(v) = setB("darkWarn", v)
    var useFront: Boolean
        get() = b("useFront", false)
        set(v) = setB("useFront", v)

    /** 0 = SD (480p), 1 = HD (720p), 2 = Full HD (1080p) */
    var quality: Int
        get() = i("quality", 1)
        set(v) = setI("quality", v)

    /** Divide o vídeo em partes de N minutos (0 = não divide). */
    var segmentMinutes: Int
        get() = i("segmentMinutes", 10)
        set(v) = setI("segmentMinutes", v)

    /** 0 = economia (1 leitura/s), 1 = normal (~3/s), 2 = máxima */
    var ocrSpeed: Int
        get() = i("ocrSpeed", 1)
        set(v) = setI("ocrSpeed", v)
    val ocrIntervalMs: Long
        get() = when (ocrSpeed) {
            0 -> 1000L
            2 -> 0L
            else -> 300L
        }

    var cooldownSec: Int
        get() = i("cooldownSec", 10)
        set(v) = setI("cooldownSec", v)
}

/** Lista de palavras-chave, compartilhada entre a tela e o serviço de gravação. */
object Keywords {
    private const val KEY = "keywords"
    val flow = MutableStateFlow<List<String>>(emptyList())

    @Volatile private var cacheKey: List<String>? = null
    @Volatile private var cache: List<KeywordSpec> = emptyList()

    fun load(ctx: Context) {
        val sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val s = sp.getString(KEY, null)
        flow.value = s?.split('\n')?.filter { it.isNotBlank() } ?: listOf("botijão de gás")
    }

    fun set(ctx: Context, list: List<String>) {
        flow.value = list
        ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("\n")).apply()
    }

    /** Devolve false se for vazio ou repetido. */
    fun add(ctx: Context, raw: String): Boolean {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        val spec = KeywordSpec.parse(t) ?: return false
        if (flow.value.any { KeywordSpec.parse(it)?.key == spec.key }) return false
        set(ctx, flow.value + (spec.pattern?.token ?: t))
        return true
    }

    fun remove(ctx: Context, raw: String) = set(ctx, flow.value.filter { it != raw })

    fun specs(): List<KeywordSpec> {
        val cur = flow.value
        if (cur !== cacheKey) {
            cache = cur.mapNotNull { KeywordSpec.parse(it) }
            cacheKey = cur
        }
        return cache
    }
}

data class SavedSegment(val index: Int, val uri: String, val name: String, val error: Int, val durationMs: Long)

data class LiveStatus(
    val sessionActive: Boolean = false,
    val recording: Boolean = false,
    val sessionStartWall: Long = 0,
    val segmentIndex: Int = 0,
    val recordedMs: Long = 0,
    val restarts: Int = 0,
    val alarm: String? = null,
    val warning: String? = null,
    val hint: String? = null,
    val audioOk: Boolean = true,
    val audioOn: Boolean = false,
    val savedSegments: List<SavedSegment> = emptyList(),
    val ocrFps: Float = 0f,
    val batteryPct: Int = -1,
    val freeBytes: Long = -1,
)

/** Estado "ao vivo" observado pela tela (e pelos testes). */
object Live {
    val status = MutableStateFlow(LiveStatus())
    val frame = MutableStateFlow<Bitmap?>(null)
    val lastText = MutableStateFlow("")
    val detections = MutableStateFlow<List<Detection>>(emptyList())

    @Volatile var uiVisible = false

    fun update(f: (LiveStatus) -> LiveStatus) = status.update(f)
}

/** Ganchos usados só pelos testes automáticos. */
object TestHooks {
    /** null = usa a configuração; 0 = não divide; >0 = divide a cada N ms. */
    @Volatile var segmentMsOverride: Long? = null

    fun reset() {
        segmentMsOverride = null
    }
}

object Notifs {
    const val CH_REC = "gravacao"
    const val CH_ALERT = "alertas"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val rec = NotificationChannel(CH_REC, "Gravação em andamento", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mostra que o app está gravando"
            setShowBadge(false)
        }
        val alert = NotificationChannel(CH_ALERT, "Palavras encontradas e alarmes", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisa quando uma palavra-chave aparece ou quando a gravação para"
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(rec)
        nm.createNotificationChannel(alert)
    }
}
