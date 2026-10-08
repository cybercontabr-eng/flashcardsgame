package br.dev.nexus.gravadorocr.core

import java.text.Normalizer
import java.util.Locale

/**
 * Normaliza texto para comparação: minúsculas, sem acentos e só letras/dígitos.
 * "BOTIJÃO DE GÁS!" -> "botijao de gas"
 */
object TextNormalizer {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    fun normalize(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        val noAccents = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        return NON_ALNUM.replace(noAccents, " ").trim()
    }

    fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /**
     * Forma "visual": junta caracteres que o OCR costuma confundir
     * (0/o, 1/l/i, 5/s, 8/b, 2/z, 6/g, rn/m, vv/w). Aplicada nos dois lados da comparação.
     */
    fun shape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    '0' -> 'o'
                    '1', 'i' -> 'l'
                    '5' -> 's'
                    '8' -> 'b'
                    '2' -> 'z'
                    '6' -> 'g'
                    else -> c
                }
            )
        }
        return sb.toString().replace("rn", "m").replace("vv", "w")
    }
}

/** Distância de edição (Levenshtein) com limite para sair cedo. */
object EditDistance {
    fun of(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > max) return max + 1
        if (n == 0) return m
        if (m == 0) return n
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }
}
