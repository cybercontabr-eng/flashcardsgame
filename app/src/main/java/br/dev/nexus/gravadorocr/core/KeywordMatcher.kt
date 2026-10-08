package br.dev.nexus.gravadorocr.core

import java.util.Locale

/** Retângulo em pixels (independente do Android para poder testar na JVM). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Uma "palavra" devolvida pelo OCR, na ordem de leitura. */
data class OcrToken(val text: String, val box: Box? = null)

enum class Tolerance {
    /** Só aceita a palavra exata (ignorando maiúsculas/acentos). */
    EXACT,

    /** Aceita 1 erro de leitura em palavras médias e 2 em frases longas. */
    NORMAL,

    /** Mais permissivo: acha mais, mas pode dar alarme falso. */
    LOOSE;

    fun allowedEdits(len: Int): Int = when (this) {
        EXACT -> 0
        NORMAL -> when {
            len <= 5 -> 0
            len <= 13 -> 1
            else -> 2
        }
        LOOSE -> when {
            len <= 3 -> 0
            len <= 7 -> 1
            len <= 12 -> 2
            else -> 3
        }
    }
}

/** Padrões especiais que o usuário pode adicionar como "palavra-chave". */
enum class PatternType(val token: String, val label: String) {
    PLACA("#placa", "Qualquer placa de veículo"),
    CPF("#cpf", "Qualquer CPF"),
    CNPJ("#cnpj", "Qualquer CNPJ");

    internal val regex: Regex
        get() = when (this) {
            PLACA -> Regex("(?<![A-Z0-9])[A-Z]{3}[- ]?[0-9][A-Z0-9][0-9]{2}(?![A-Z0-9])")
            CPF -> Regex("(?<![0-9])[0-9]{3}[. ]?[0-9]{3}[. ]?[0-9]{3}[-. ]?[0-9]{2}(?![0-9])")
            CNPJ -> Regex("(?<![A-Z0-9])[A-Z0-9]{2}[. ]?[A-Z0-9]{3}[. ]?[A-Z0-9]{3}[/ ]?[A-Z0-9]{4}[-. ]?[0-9]{2}(?![0-9])")
        }

    internal fun validate(value: String): Boolean = when (this) {
        PLACA -> true
        CPF -> Documents.cpfValid(value)
        CNPJ -> Documents.cnpjValid(value)
    }
}

/** Palavra-chave já pré-processada. */
class KeywordSpec private constructor(
    val raw: String,
    val label: String,
    val words: List<String>,
    val pattern: PatternType?,
) {
    val joined: String = words.joinToString("")
    val shapeJoined: String = TextNormalizer.shape(joined)

    /** Chave estável para controle de repetição de alertas. */
    val key: String = pattern?.token ?: words.joinToString(" ")

    override fun toString(): String = "KeywordSpec($raw)"

    companion object {
        fun parse(raw: String): KeywordSpec? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            PatternType.entries.firstOrNull { it.token.equals(t, ignoreCase = true) }?.let {
                return KeywordSpec(it.token, it.label, emptyList(), it)
            }
            val w = TextNormalizer.words(t)
            if (w.isEmpty()) return null
            return KeywordSpec(t, t, w, null)
        }

        fun labelOf(raw: String): String = parse(raw)?.label ?: raw
    }
}

enum class MatchKind { FULL, PARTIAL }

data class MatchResult(
    val spec: KeywordSpec,
    val kind: MatchKind,
    val matchedText: String,
    val distance: Int,
    val boxes: List<Box>,
)

/**
 * Procura as palavras-chave no texto lido pelo OCR.
 * - Ignora maiúsculas, acentos e pontuação.
 * - Frases podem estar quebradas em linhas diferentes.
 * - Tolera erros típicos de OCR (letra trocada, palavra grudada ou separada).
 * - Devolve PARTIAL quando "quase" leu (útil para pedir para segurar o celular parado).
 */
class KeywordMatcher(private val tolerance: Tolerance) {

    private class NormTok(val text: String, val shape: String, val src: Int)

    fun match(tokens: List<OcrToken>, keywords: List<KeywordSpec>): List<MatchResult> {
        if (tokens.isEmpty() || keywords.isEmpty()) return emptyList()
        val norm = ArrayList<NormTok>(tokens.size + 8)
        tokens.forEachIndexed { i, t ->
            for (w in TextNormalizer.words(t.text)) norm.add(NormTok(w, TextNormalizer.shape(w), i))
        }
        val out = ArrayList<MatchResult>()
        for (spec in keywords) {
            val r = if (spec.pattern != null) matchPattern(spec, spec.pattern, tokens) else matchWords(spec, tokens, norm)
            if (r != null) out.add(r)
        }
        return out
    }

    private fun matchWords(spec: KeywordSpec, tokens: List<OcrToken>, norm: List<NormTok>): MatchResult? {
        if (norm.isEmpty()) return null
        val k = spec.words.size
        val target = spec.joined
        val targetShape = spec.shapeJoined
        val allowed = tolerance.allowedEdits(target.length)
        val nearLimit = if (target.length >= 6) allowed + 1 else allowed
        val useShape = tolerance != Tolerance.EXACT

        var bestD = Int.MAX_VALUE
        var bestStart = -1
        var bestLen = 0
        val minW = maxOf(1, k - 1)
        val maxW = k + 1
        for (i in norm.indices) {
            val sb = StringBuilder()
            for (w in 1..maxW) {
                val j = i + w - 1
                if (j >= norm.size) break
                sb.append(norm[j].text)
                if (w < minW) continue
                if (sb.length > target.length + nearLimit) break
                if (target.length - sb.length > nearLimit) continue
                var d = EditDistance.of(sb.toString(), target, nearLimit)
                if (useShape && d > 0) {
                    d = minOf(d, EditDistance.of(TextNormalizer.shape(sb.toString()), targetShape, nearLimit))
                }
                if (d < bestD) {
                    bestD = d
                    bestStart = i
                    bestLen = w
                }
                if (bestD == 0) break
            }
            if (bestD == 0) break
        }

        if (bestStart >= 0 && bestD <= allowed) {
            return result(spec, MatchKind.FULL, bestD, (bestStart until bestStart + bestLen).map { norm[it].src }, tokens)
        }
        if (bestStart >= 0 && bestD <= nearLimit && target.length >= 6) {
            return result(spec, MatchKind.PARTIAL, bestD, (bestStart until bestStart + bestLen).map { norm[it].src }, tokens)
        }
        if (k >= 2) {
            val significant = spec.words.filter { it.length >= 3 }.distinct()
            if (significant.isNotEmpty()) {
                val longest = significant.maxBy { it.length }
                val found = LinkedHashMap<String, Int>()
                for (sw in significant) {
                    val a = tolerance.allowedEdits(sw.length)
                    val sws = TextNormalizer.shape(sw)
                    val idx = norm.indexOfFirst {
                        EditDistance.of(it.text, sw, a) <= a || (useShape && EditDistance.of(it.shape, sws, a) <= a)
                    }
                    if (idx >= 0) found[sw] = idx
                }
                if (found.containsKey(longest) || found.size >= 2) {
                    return result(spec, MatchKind.PARTIAL, nearLimit + 1, found.values.map { norm[it].src }, tokens)
                }
            }
        }
        return null
    }

    private fun result(spec: KeywordSpec, kind: MatchKind, d: Int, srcIdx: List<Int>, tokens: List<OcrToken>): MatchResult {
        val distinct = srcIdx.distinct().sorted()
        val text = distinct.joinToString(" ") { tokens[it].text }
        val boxes = distinct.mapNotNull { tokens[it].box }
        return MatchResult(spec, kind, text, d, boxes)
    }

    private fun matchPattern(spec: KeywordSpec, type: PatternType, tokens: List<OcrToken>): MatchResult? {
        val raw = tokens.joinToString(" ") { it.text }.uppercase(Locale.ROOT)
        for (m in type.regex.findAll(raw)) {
            val v = m.value
            if (!type.validate(v)) continue
            val alnum = v.filter { it.isLetterOrDigit() }
            val boxes = tokens.filter { t ->
                val ta = t.text.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
                ta.length >= 2 && alnum.contains(ta)
            }.mapNotNull { it.box }
            return MatchResult(spec, MatchKind.FULL, v, 0, boxes)
        }
        return null
    }
}

/** Validação de CPF e CNPJ (inclui o CNPJ alfanumérico). */
object Documents {
    fun cpfValid(s: String): Boolean {
        val d = s.filter { it.isDigit() }.map { it - '0' }
        if (d.size != 11 || d.all { it == d[0] }) return false
        var sum = 0
        for (i in 0 until 9) sum += d[i] * (10 - i)
        var r = (sum * 10) % 11
        if (r == 10) r = 0
        if (r != d[9]) return false
        sum = 0
        for (i in 0 until 10) sum += d[i] * (11 - i)
        r = (sum * 10) % 11
        if (r == 10) r = 0
        return r == d[10]
    }

    fun cnpjValid(s: String): Boolean {
        val c = s.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (c.length != 14) return false
        if (!c[12].isDigit() || !c[13].isDigit()) return false
        if (c.all { it == c[0] }) return false
        val v = c.map { it.code - '0'.code }
        val w1 = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        val w2 = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
        fun dv(n: Int, w: IntArray): Int {
            var sum = 0
            for (i in 0 until n) sum += v[i] * w[i]
            val r = sum % 11
            return if (r < 2) 0 else 11 - r
        }
        return dv(12, w1) == v[12] && dv(13, w2) == v[13]
    }
}
