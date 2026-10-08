package br.dev.nexus.gravadorocr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMatcherTest {

    private fun toks(vararg lines: String): List<OcrToken> =
        lines.flatMap { l -> l.split(" ").filter { it.isNotBlank() }.map { OcrToken(it) } }

    private fun spec(s: String) = KeywordSpec.parse(s)!!

    private val normal = KeywordMatcher(Tolerance.NORMAL)

    private fun full(m: KeywordMatcher, kw: String, vararg lines: String): MatchResult? =
        m.match(toks(*lines), listOf(spec(kw))).firstOrNull { it.kind == MatchKind.FULL }

    private fun any(m: KeywordMatcher, kw: String, vararg lines: String): MatchResult? =
        m.match(toks(*lines), listOf(spec(kw))).firstOrNull()

    @Test
    fun normalizer_removesAccentsCaseAndPunctuation() {
        assertEquals("botijao de gas", TextNormalizer.normalize("BOTIJÃO DE GÁS!"))
        assertEquals(listOf("acao", "e", "reacao"), TextNormalizer.words("Ação, é reação."))
    }

    @Test
    fun exactPhraseWithAccents() {
        val r = full(normal, "botijão de gás", "BOTIJÃO DE GÁS 13KG")
        assertNotNull(r)
        assertEquals(0, r!!.distance)
        assertEquals("BOTIJÃO DE GÁS", r.matchedText)
    }

    @Test
    fun textWithoutAccentsStillMatches() {
        assertNotNull(full(normal, "botijão de gás", "venda de botijao de gas aqui"))
    }

    @Test
    fun phraseBrokenAcrossLines() {
        assertNotNull(full(normal, "botijão de gás", "COMPRA DE BOTIJÃO", "DE GÁS P13"))
    }

    @Test
    fun ocrConfusionZeroForO() {
        val r = full(normal, "botijão de gás", "B0TIJÃO DE GAS")
        assertNotNull(r)
        assertEquals(0, r!!.distance)
    }

    @Test
    fun oneLetterWrongIsAcceptedInNormal() {
        val r = full(normal, "botijão de gás", "BOTIJAO DF GAS")
        assertNotNull(r)
        assertEquals(1, r!!.distance)
    }

    @Test
    fun mergedAndSplitWords() {
        assertNotNull(full(normal, "botijão de gás", "BOTIJÃODE GÁS"))
        assertNotNull(full(normal, "botijão de gás", "BOTI JÃO DE GÁS"))
    }

    @Test
    fun shortWordIsNotFoundInsideAnotherWord() {
        assertNull(any(normal, "gás", "GASOLINA COMUM"))
        assertNotNull(full(normal, "gás", "TEM GÁS?"))
    }

    @Test
    fun shortWordsNeedExactInNormal() {
        assertNull(full(normal, "casa", "CASO ENCERRADO"))
    }

    @Test
    fun similarButDifferentPhraseIsNotFull() {
        // "nota final" não pode disparar "nota fiscal"
        assertNull(full(normal, "nota fiscal", "NOTA FINAL DO ALUNO"))
    }

    @Test
    fun partialWhenOnlyMainWordVisible() {
        val r = any(normal, "botijão de gás", "BOTIJÃO 13KG")
        assertNotNull(r)
        assertEquals(MatchKind.PARTIAL, r!!.kind)
    }

    @Test
    fun exactModeTurnsTypoIntoPartial() {
        val exact = KeywordMatcher(Tolerance.EXACT)
        assertNull(full(exact, "botijão de gás", "BOTIJAO DF GAS"))
        assertEquals(MatchKind.PARTIAL, any(exact, "botijão de gás", "BOTIJAO DF GAS")!!.kind)
        assertNotNull(full(exact, "botijão de gás", "botijao de gas"))
    }

    @Test
    fun looseAcceptsTwoErrors() {
        val loose = KeywordMatcher(Tolerance.LOOSE)
        assertNotNull(full(loose, "botijão de gás", "BOTJAO DF GAS"))
        assertNull(full(normal, "botijão de gás", "BOTJAO DF GAS"))
    }

    @Test
    fun unrelatedTextFindsNothing() {
        assertTrue(normal.match(toks("RELATÓRIO MENSAL", "VALOR TOTAL R$ 120,00"), listOf(spec("botijão de gás"))).isEmpty())
    }

    @Test
    fun multipleKeywords() {
        val r = normal.match(
            toks("NOTA FISCAL 123", "BOTIJÃO DE GÁS P13"),
            listOf(spec("botijão de gás"), spec("nota fiscal"), spec("extintor")),
        )
        assertEquals(setOf("botijão de gás", "nota fiscal"), r.filter { it.kind == MatchKind.FULL }.map { it.spec.raw }.toSet())
    }

    @Test
    fun boxesOfMatchedWordsAreReturned() {
        val tokens = listOf(
            OcrToken("O", Box(0, 0, 10, 10)),
            OcrToken("BOTIJÃO", Box(20, 0, 80, 10)),
            OcrToken("DE", Box(90, 0, 100, 10)),
            OcrToken("GÁS", Box(110, 0, 140, 10)),
        )
        val r = normal.match(tokens, listOf(spec("botijão de gás"))).single()
        assertEquals(3, r.boxes.size)
        assertEquals(Box(20, 0, 80, 10), r.boxes.first())
    }

    @Test
    fun placaMercosulAndOld() {
        assertNotNull(full(normal, "#placa", "PLACA ABC1D23 PRETO"))
        assertNotNull(full(normal, "#placa", "carro placa abc-1234"))
        assertNull(any(normal, "#placa", "SEM PLACA AQUI 12345"))
    }

    @Test
    fun cpfOnlyWhenValid() {
        assertNotNull(full(normal, "#cpf", "CPF: 529.982.247-25"))
        assertNull(any(normal, "#cpf", "CPF: 529.982.247-24"))
        assertNull(any(normal, "#cpf", "111.111.111-11"))
    }

    @Test
    fun cnpjNumericAndAlphanumeric() {
        assertNotNull(full(normal, "#cnpj", "CNPJ 11.222.333/0001-81"))
        assertNull(any(normal, "#cnpj", "CNPJ 11.222.333/0001-82"))
        assertTrue(Documents.cnpjValid("12ABC34501DE35"))
        assertFalse(Documents.cnpjValid("12ABC34501DE36"))
    }

    @Test
    fun specParsing() {
        assertNull(KeywordSpec.parse("   "))
        assertNull(KeywordSpec.parse("!!!"))
        assertEquals("Qualquer CPF", KeywordSpec.parse("#CPF")!!.label)
        assertEquals("botijao de gas", KeywordSpec.parse(" Botijão  de GÁS ")!!.key)
    }
}
