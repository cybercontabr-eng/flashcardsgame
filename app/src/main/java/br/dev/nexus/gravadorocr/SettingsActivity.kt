package br.dev.nexus.gravadorocr

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import br.dev.nexus.gravadorocr.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val prefs by lazy { Prefs(this) }
    private var tester: AlertPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        val pad = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }

        radio(b.rgTolerance, listOf(b.tolExact.id, b.tolNormal.id, b.tolLoose.id), prefs.toleranceIndex) { prefs.toleranceIndex = it }
        radio(b.rgOcrSpeed, listOf(b.ocrEco.id, b.ocrNormal.id, b.ocrMax.id), prefs.ocrSpeed) { prefs.ocrSpeed = it }
        val cds = listOf(5, 10, 30)
        radio(b.rgCooldown, listOf(b.cd5.id, b.cd10.id, b.cd30.id), cds.indexOf(prefs.cooldownSec).coerceAtLeast(1)) {
            prefs.cooldownSec = cds[it]
        }
        val segs = listOf(5, 10, 30, 0)
        radio(b.rgSegment, listOf(b.seg5.id, b.seg10.id, b.seg30.id, b.segOff.id), segs.indexOf(prefs.segmentMinutes).coerceAtLeast(1)) {
            prefs.segmentMinutes = segs[it]
        }
        radio(b.rgQuality, listOf(b.qSd.id, b.qHd.id, b.qFhd.id), prefs.quality) { prefs.quality = it }

        switch(b.swBeep, prefs.beep) { prefs.beep = it }
        switch(b.swVibrate, prefs.vibrate) { prefs.vibrate = it }
        switch(b.swVoice, prefs.voice) { prefs.voice = it }
        switch(b.swHold, prefs.holdHint) { prefs.holdHint = it }
        switch(b.swSnap, prefs.snapshots) { prefs.snapshots = it }
        switch(b.swAutoRestart, prefs.autoRestart) { prefs.autoRestart = it }
        switch(b.swDark, prefs.darkWarn) { prefs.darkWarn = it }
        switch(b.swAudio, prefs.audio) { prefs.audio = it }
        switch(b.swFront, prefs.useFront) { prefs.useFront = it }

        b.btnTest.setOnClickListener {
            val t = tester ?: AlertPlayer(this).also { tester = it }
            t.found("botijão de gás")
        }
        b.btnBattery.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Abra Configurações > Apps > Gravador OCR > Bateria", Toast.LENGTH_LONG).show()
            }
        }
        b.version.text = "Versão ${BuildConfig.VERSION_NAME}"
    }

    override fun onDestroy() {
        tester?.release()
        tester = null
        super.onDestroy()
    }

    private fun radio(group: RadioGroup, ids: List<Int>, selected: Int, onChange: (Int) -> Unit) {
        group.check(ids.getOrElse(selected) { ids.first() })
        group.setOnCheckedChangeListener { _, checkedId ->
            val i = ids.indexOf(checkedId)
            if (i >= 0) onChange(i)
        }
    }

    private fun switch(sw: CompoundButton, value: Boolean, onChange: (Boolean) -> Unit) {
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
