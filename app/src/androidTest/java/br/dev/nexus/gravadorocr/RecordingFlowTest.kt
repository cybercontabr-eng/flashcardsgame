package br.dev.nexus.gravadorocr

import android.content.Context
import android.util.Log
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import br.dev.nexus.gravadorocr.core.Detection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes de ponta a ponta no emulador: grava de verdade com a câmera emulada,
 * confere os arquivos MP4 gerados, a divisão em partes, a retomada automática,
 * o alarme e a detecção de palavra durante a gravação.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RecordingFlowTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestUtils.permissions())

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        TestHooks.reset()
        TestHooks.segmentMsOverride = 0L
        Prefs(ctx).apply {
            audio = true
            autoRestart = true
            snapshots = true
            voice = false
            quality = 1
            useFront = false
            ocrSpeed = 1
            toleranceIndex = 1
            cooldownSec = 30
        }
        Keywords.set(ctx, listOf("botijão de gás"))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ensureStopped()
    }

    @After
    fun tearDown() {
        ensureStopped()
        TestHooks.reset()
        scenario.close()
    }

    private fun ensureStopped() {
        if (Live.status.value.alarm != null) RecordingService.silence(ctx)
        if (Live.status.value.sessionActive) {
            RecordingService.stop(ctx)
            TestUtils.waitUntil(30_000, "sessão encerrar") { !Live.status.value.sessionActive }
        }
        TestUtils.waitUntil(15_000, "serviço encerrar") { RecordingService.current == null }
    }

    private fun startAndWaitRecording() {
        RecordingService.start(ctx)
        TestUtils.waitUntil(40_000, "gravação começar") { Live.status.value.recording }
    }

    private fun stopAndWait() {
        RecordingService.stop(ctx)
        TestUtils.waitUntil(30_000, "gravação finalizar") { !Live.status.value.sessionActive }
    }

    @Test
    fun recordsAValidMp4() {
        startAndWaitRecording()
        Thread.sleep(7_000)
        val st = Live.status.value
        assertTrue("ainda deveria estar gravando: $st", st.recording)
        assertNull("não deveria ter alarme: ${st.alarm}", st.alarm)
        assertTrue("tempo gravado deveria avançar: ${st.recordedMs}", st.recordedMs >= 4_000)
        // o OCR roda junto com a gravação (no emulador ele é bem mais lento que num celular)
        TestUtils.waitUntil(60_000, "OCR analisar quadros da câmera durante a gravação") {
            Live.status.value.framesAnalyzed >= 2
        }
        assertTrue("continua gravando enquanto o OCR roda", Live.status.value.recording)
        stopAndWait()
        val segs = Live.status.value.savedSegments
        Log.i(TestUtils.TAG, "Partes: $segs")
        assertEquals("deveria salvar 1 parte", 1, segs.size)
        TestUtils.assertValidVideo(ctx, segs[0].uri, minDurationMs = 5_000)
    }

    @Test
    fun splitsIntoSegmentsWithoutLosingVideo() {
        TestHooks.segmentMsOverride = 4_000L
        startAndWaitRecording()
        TestUtils.waitUntil(45_000, "pelo menos 2 partes salvas") { Live.status.value.savedSegments.size >= 2 }
        TestUtils.waitUntil(15_000, "gravando a parte seguinte") { Live.status.value.recording }
        Thread.sleep(1_500)
        stopAndWait()
        val segs = Live.status.value.savedSegments
        Log.i(TestUtils.TAG, "Partes: $segs")
        assertTrue("esperava >= 2 partes, veio ${segs.size}", segs.size >= 2)
        assertNull(Live.status.value.alarm)
        assertEquals("sem retomadas inesperadas", 0, Live.status.value.restarts)
        for (s in segs.take(2)) TestUtils.assertValidVideo(ctx, s.uri, minDurationMs = 2_500)
        assertEquals("as partes devem ser numeradas em sequência", (1..segs.size).toList(), segs.map { it.index })
    }

    @Test
    fun detectsKeywordWhileRecordingAndSavesPhoto() {
        // a câmera do emulador não mostra texto; usamos uma imagem gerada e pausamos os quadros da câmera
        TestHooks.ignoreCameraFrames = true
        startAndWaitRecording()
        Thread.sleep(2_000)
        val svc = RecordingService.current
        assertNotNull(svc)
        val page = TestUtils.renderText(listOf("ENTREGA AGENDADA", "BOTIJÃO DE GÁS P13", "QTD 2"))
        // como na câmera real, a mesma imagem chega em vários quadros seguidos até ser lida
        // (no emulador o primeiro OCR pode levar dezenas de segundos)
        val deadline = android.os.SystemClock.elapsedRealtime() + 150_000
        fun found() = Live.detections.value.any { it.kind == Detection.Kind.FOUND && it.label == "botijão de gás" }
        while (!found() && android.os.SystemClock.elapsedRealtime() < deadline) {
            svc!!.debugProcessFrame(page)
            Thread.sleep(1_500)
        }
        assertTrue("deveria detectar 'botijão de gás' | status=${Live.status.value}", found())
        TestUtils.waitUntil(20_000, "foto da detecção salva") {
            Live.detections.value.any { it.kind == Detection.Kind.FOUND && it.snapshotUri != null }
        }
        val det = Live.detections.value.first { it.kind == Detection.Kind.FOUND }
        val size = ctx.contentResolver.openFileDescriptor(android.net.Uri.parse(det.snapshotUri), "r")?.use { it.statSize } ?: -1
        assertTrue("foto vazia", size > 1_000)
        // a mesma palavra logo em seguida não deve gerar novo alerta (evita apitar sem parar)
        svc!!.debugProcessFrame(page)
        Thread.sleep(3_000)
        assertEquals(1, Live.detections.value.count { it.kind == Detection.Kind.FOUND })
        stopAndWait()
        TestUtils.assertValidVideo(ctx, Live.status.value.savedSegments.first().uri, minDurationMs = 3_000)
    }

    @Test
    fun resumesAutomaticallyWhenRecordingStopsUnexpectedly() {
        startAndWaitRecording()
        Thread.sleep(4_000)
        RecordingService.current!!.debugSimulateUnexpectedStop()
        TestUtils.waitUntil(30_000, "retomada automática") {
            val s = Live.status.value
            s.restarts >= 1 && s.recording
        }
        assertNull("retomou sozinho, não deveria tocar alarme", Live.status.value.alarm)
        assertTrue(
            "deveria registrar o aviso da parada",
            Live.detections.value.any { it.kind == Detection.Kind.WARNING },
        )
        Thread.sleep(3_000)
        stopAndWait()
        val segs = Live.status.value.savedSegments
        assertTrue("esperava 2 partes (antes e depois da parada), veio ${segs.size}", segs.size >= 2)
        TestUtils.assertValidVideo(ctx, segs[0].uri, minDurationMs = 2_500)
    }

    @Test
    fun alarmWhenRecordingStopsAndAutoResumeIsOff() {
        Prefs(ctx).autoRestart = false
        startAndWaitRecording()
        Thread.sleep(4_000)
        RecordingService.current!!.debugSimulateUnexpectedStop()
        TestUtils.waitUntil(20_000, "alarme disparar") { Live.status.value.alarm != null }
        assertTrue("sessão continua aberta esperando o usuário", Live.status.value.sessionActive)
        RecordingService.retry(ctx)
        TestUtils.waitUntil(30_000, "voltar a gravar após 'Tentar de novo'") {
            Live.status.value.recording && Live.status.value.alarm == null
        }
        Thread.sleep(3_000)
        stopAndWait()
        assertTrue(Live.status.value.savedSegments.size >= 2)
    }

    @Test
    fun addKeywordFromScreen() {
        scenario.onActivity { a ->
            a.findViewById<EditText>(R.id.input).setText("nota fiscal")
            a.findViewById<android.view.View>(R.id.btnAdd).performClick()
        }
        assertTrue(Keywords.flow.value.contains("nota fiscal"))
        TestUtils.waitUntil(5_000, "chip da nova palavra aparecer") {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var n = 0
            scenario.onActivity { a -> n = a.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chips).childCount }
            n == 2
        }
        Keywords.remove(ctx, "nota fiscal")
        assertEquals(listOf("botijão de gás"), Keywords.flow.value)
        // não aceita repetida nem vazia
        assertTrue(!Keywords.add(ctx, "  BOTIJÃO de gas "))
        assertTrue(!Keywords.add(ctx, "   "))
    }
}
