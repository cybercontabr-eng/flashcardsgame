package br.dev.nexus.gravadorocr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.dev.nexus.gravadorocr.core.KeywordMatcher
import br.dev.nexus.gravadorocr.core.KeywordSpec
import br.dev.nexus.gravadorocr.core.MatchKind
import br.dev.nexus.gravadorocr.core.Tolerance
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** OCR de verdade (ML Kit) em imagens geradas com texto, sem câmera. */
@RunWith(AndroidJUnit4::class)
class OcrRecognitionTest {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val matcher = KeywordMatcher(Tolerance.NORMAL)

    @After
    fun tearDown() = recognizer.close()

    private fun read(bmp: android.graphics.Bitmap): Text {
        val t = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
        Log.i(TestUtils.TAG, "OCR leu: ${t.text.replace('\n', '|')}")
        return t
    }

    @Test
    fun findsBotijaoDeGasInDocument() {
        val text = read(TestUtils.renderText(listOf("NOTA DE ENTREGA 4521", "BOTIJÃO DE GÁS P13", "VALOR R$ 120,00")))
        val r = matcher.match(OcrText.tokens(text), listOf(KeywordSpec.parse("botijão de gás")!!))
        assertTrue("deveria achar 'botijão de gás' em: ${text.text}", r.any { it.kind == MatchKind.FULL })
        assertTrue("deveria devolver as caixas das palavras", r.first().boxes.isNotEmpty())
    }

    @Test
    fun doesNotFindWhatIsNotThere() {
        val text = read(TestUtils.renderText(listOf("GASOLINA COMUM", "POSTO CENTRAL")))
        val r = matcher.match(OcrText.tokens(text), listOf(KeywordSpec.parse("botijão de gás")!!, KeywordSpec.parse("gás")!!))
        assertFalse("não deveria achar nada em: ${text.text}", r.any { it.kind == MatchKind.FULL })
    }

    @Test
    fun phraseSplitAcrossTwoLines() {
        val text = read(TestUtils.renderText(listOf("COMPRA DE BOTIJÃO", "DE GÁS COMPLETO")))
        val r = matcher.match(OcrText.tokens(text), listOf(KeywordSpec.parse("botijão de gás")!!))
        assertTrue("frase quebrada em 2 linhas: ${text.text}", r.any { it.kind == MatchKind.FULL })
    }

    @Test
    fun rotatedCameraFrameIsStraightenedBeforeOcr() {
        // A câmera entrega o quadro "deitado" (rotationDegrees = 90). O app desvira antes do OCR.
        val upright = TestUtils.renderText(listOf("BOTIJÃO DE GÁS"))
        val sensor = TestUtils.rotate(upright, 270)
        val fixed = FrameUtils.upright(sensor, 90)
        val text = read(fixed)
        val r = matcher.match(OcrText.tokens(text), listOf(KeywordSpec.parse("botijão de gás")!!))
        assertTrue("após desvirar: ${text.text}", r.any { it.kind == MatchKind.FULL })
    }

    @Test
    fun findsCpfAndPlaca() {
        val text = read(TestUtils.renderText(listOf("CPF 529.982.247-25", "PLACA ABC1D23")))
        val r = matcher.match(
            OcrText.tokens(text),
            listOf(KeywordSpec.parse("#cpf")!!, KeywordSpec.parse("#placa")!!),
        )
        assertTrue("CPF: ${text.text}", r.any { it.spec.pattern?.token == "#cpf" })
        assertTrue("placa: ${text.text}", r.any { it.spec.pattern?.token == "#placa" })
    }
}
