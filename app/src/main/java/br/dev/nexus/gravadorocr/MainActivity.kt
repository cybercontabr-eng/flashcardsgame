package br.dev.nexus.gravadorocr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import br.dev.nexus.gravadorocr.core.Detection
import br.dev.nexus.gravadorocr.core.KeywordSpec
import br.dev.nexus.gravadorocr.core.TimeFmt
import br.dev.nexus.gravadorocr.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: DetectionAdapter
    private var pocket = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA)) {
            if (prefs.audio && !has(Manifest.permission.RECORD_AUDIO)) {
                toast("Sem permissão do microfone: o vídeo será gravado sem áudio.")
            }
            startRecording()
        } else {
            toast("Sem a permissão da câmera não dá para gravar.")
        }
    }

    private val pocketBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            toast("Segure o dedo na tela por 2 s para sair da tela preta")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        onBackPressedDispatcher.addCallback(this, pocketBack)

        val pad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }

        adapter = DetectionAdapter(this) { openSnapshot(it) }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.btnAdd.setOnClickListener { addKeywordFromInput() }
        b.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeywordFromInput()
                true
            } else {
                false
            }
        }
        b.quickPlaca.setOnClickListener { addKeyword("#placa") }
        b.quickCpf.setOnClickListener { addKeyword("#cpf") }
        b.quickCnpj.setOnClickListener { addKeyword("#cnpj") }

        b.btnRecord.setOnClickListener {
            if (Live.status.value.sessionActive) RecordingService.stop(this) else requestAndStart()
        }
        b.btnPocket.setOnClickListener { enterPocket() }
        b.btnMark.setOnClickListener {
            if (Live.status.value.sessionActive) {
                RecordingService.mark(this)
                toast("Momento marcado")
            } else {
                toast("Comece a gravar para marcar momentos")
            }
        }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnSilence.setOnClickListener { RecordingService.silence(this) }
        b.btnRetry.setOnClickListener { RecordingService.retry(this) }
        setupPocketOverlay()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Keywords.flow.collect { renderKeywords(it) } }
                launch { Live.status.collect { renderStatus(it) } }
                launch {
                    Live.frame.collect { bmp ->
                        if (bmp != null) b.monitor.setImageBitmap(bmp) else b.monitor.setImageDrawable(null)
                        b.monitorHint.isVisible = bmp == null
                    }
                }
                launch {
                    Live.detections.collect { list ->
                        val shown = list.asReversed().take(100)
                        adapter.submit(shown)
                        b.emptyList.isVisible = shown.isEmpty()
                    }
                }
                launch {
                    Live.lastText.collect { b.lastText.text = "Texto lido: " + it.ifBlank { "—" } }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Live.uiVisible = true
    }

    override fun onStop() {
        Live.uiVisible = false
        super.onStop()
    }

    // ------------------------------------------------------------ palavras-chave

    private fun addKeywordFromInput() {
        val t = b.input.text?.toString().orEmpty()
        if (t.isBlank()) return
        if (addKeyword(t)) b.input.setText("")
    }

    private fun addKeyword(raw: String): Boolean {
        val ok = Keywords.add(this, raw)
        if (!ok) toast("Essa palavra já está na lista (ou está vazia)")
        return ok
    }

    private fun renderKeywords(list: List<String>) {
        b.chips.removeAllViews()
        for (kw in list) {
            val chip = Chip(this).apply {
                text = KeywordSpec.labelOf(kw)
                isCloseIconVisible = true
                setOnCloseIconClickListener { Keywords.remove(this@MainActivity, kw) }
                contentDescription = "Remover $kw"
            }
            b.chips.addView(chip)
        }
        b.noKeywords.isVisible = list.isEmpty()
    }

    // ------------------------------------------------------------ status

    private fun renderStatus(st: LiveStatus) {
        val red = ContextCompat.getColor(this, R.color.rec)
        val green = ContextCompat.getColor(this, R.color.ok)
        if (st.sessionActive) {
            b.btnRecord.text = "PARAR GRAVAÇÃO"
            b.btnRecord.backgroundTintList = ColorStateList.valueOf(red)
            b.btnRecord.setTextColor(Color.WHITE)
        } else {
            b.btnRecord.text = "INICIAR GRAVAÇÃO"
            b.btnRecord.backgroundTintList = ColorStateList.valueOf(green)
            b.btnRecord.setTextColor(Color.BLACK)
        }
        b.recStatus.text = when {
            st.alarm != null -> "⚠ PAROU"
            st.recording -> "● REC " + TimeFmt.clock(st.recordedMs) + if (st.segmentIndex > 1) "  p${st.segmentIndex}" else ""
            st.sessionActive -> "INICIANDO…"
            else -> "PARADO"
        }
        b.recStatus.setTextColor(
            when {
                st.alarm != null || st.recording -> red
                else -> ContextCompat.getColor(this, R.color.muted)
            }
        )

        b.alarmCard.isVisible = st.alarm != null
        b.alarmText.text = st.alarm ?: ""
        if (st.alarm != null && pocket) exitPocket()

        b.hintBadge.isVisible = st.hint != null
        b.hintBadge.text = st.hint ?: ""

        val parts = ArrayList<String>()
        if (st.sessionActive) {
            parts += if (st.ocrFps > 0) String.format(Locale.ROOT, "OCR %.1f leituras/s", st.ocrFps) else "OCR iniciando"
            parts += if (!st.audioOn) "sem áudio" else if (st.audioOk) "áudio OK" else "áudio com falha"
            if (st.restarts > 0) parts += "${st.restarts} retomada(s)"
        }
        if (st.batteryPct >= 0) parts += "bateria ${st.batteryPct}%"
        if (st.freeBytes >= 0) parts += String.format(Locale.ROOT, "livre %.1f GB", st.freeBytes / 1e9)
        if (!st.sessionActive && st.savedSegments.isNotEmpty()) parts += "última gravação: ${st.savedSegments.size} parte(s) salvas"
        b.info.text = parts.joinToString("  •  ")

        b.warning.isVisible = st.warning != null
        b.warning.text = st.warning?.let { "⚠ $it" } ?: ""
    }

    // ------------------------------------------------------------ gravação

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestAndStart() {
        val need = mutableListOf(Manifest.permission.CAMERA)
        if (prefs.audio) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { !has(it) }
        if (missing.isEmpty()) startRecording() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startRecording() {
        if (Keywords.flow.value.isEmpty()) toast("Sem palavras-chave: vai só gravar. Dá para adicionar durante a gravação.")
        RecordingService.start(this)
    }

    // ------------------------------------------------------------ tela preta

    private val exitPocketRunnable = Runnable { exitPocket() }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPocketOverlay() {
        b.pocketOverlay.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> handler.postDelayed(exitPocketRunnable, 2000)
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(exitPocketRunnable)
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(exitPocketRunnable)
            }
            true
        }
    }

    private fun enterPocket() {
        pocket = true
        pocketBack.isEnabled = true
        b.pocketOverlay.isVisible = true
        b.pocketText.text = if (Live.status.value.sessionActive) "● gravando\nsegure o dedo 2 s para sair" else "tela preta\nsegure o dedo 2 s para sair"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        WindowCompat.getInsetsController(window, b.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitPocket() {
        pocket = false
        pocketBack.isEnabled = false
        b.pocketOverlay.isVisible = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        WindowCompat.getInsetsController(window, b.root).show(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------ util

    private fun openSnapshot(d: Detection) {
        val uri = d.snapshotUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Não achei um app para abrir a foto")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
