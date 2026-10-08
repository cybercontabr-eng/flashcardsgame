package br.dev.nexus.gravadorocr.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Um evento da sessão: palavra encontrada, marcação manual ou aviso de segurança. */
data class Detection(
    val id: Long,
    val kind: Kind,
    val label: String,
    val snippet: String,
    val wallTimeMs: Long,
    val sessionOffsetMs: Long,
    val segment: Int,
    val segmentOffsetMs: Long,
    val snapshotUri: String? = null,
) {
    enum class Kind { FOUND, MARK, WARNING }
}

object TimeFmt {
    fun clock(ms: Long): String {
        val t = (ms.coerceAtLeast(0)) / 1000
        val h = t / 3600
        val m = (t / 60) % 60
        val s = t % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", m, s)
        }
    }
}

/** Legenda .srt: abrindo o vídeo com a legenda, aparece o aviso no momento exato. */
object SrtWriter {
    data class Entry(val startMs: Long, val endMs: Long, val text: String)

    fun time(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        val h = v / 3_600_000
        val m = (v / 60_000) % 60
        val s = (v / 1000) % 60
        val milli = v % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    fun build(entries: List<Entry>): String =
        entries.sortedBy { it.startMs }.mapIndexed { i, e ->
            "${i + 1}\n${time(e.startMs)} --> ${time(e.endMs)}\n${e.text}\n"
        }.joinToString("\n")

    fun forSegment(detections: List<Detection>, segment: Int, durationMs: Long = 3_000): String? {
        val list = detections.filter { it.segment == segment }
        if (list.isEmpty()) return null
        return build(list.map {
            val prefix = when (it.kind) {
                Detection.Kind.FOUND -> "ENCONTRADO: "
                Detection.Kind.MARK -> "MARCAÇÃO"
                Detection.Kind.WARNING -> "AVISO: "
            }
            val text = when (it.kind) {
                Detection.Kind.MARK -> prefix
                else -> prefix + it.label + if (it.snippet.isNotBlank() && it.kind == Detection.Kind.FOUND) "\n\"${it.snippet}\"" else ""
            }
            Entry(it.segmentOffsetMs, it.segmentOffsetMs + durationMs, text)
        })
    }
}

/** Relatório em texto da sessão inteira. */
object SessionLog {
    data class Segment(val index: Int, val name: String, val durationMs: Long, val error: Int)

    fun build(
        startWall: Long,
        keywords: List<String>,
        segments: List<Segment>,
        detections: List<Detection>,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val ptBr = Locale.forLanguageTag("pt-BR")
        val full = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", ptBr).apply { timeZone = zone }
        val hour = SimpleDateFormat("HH:mm:ss", ptBr).apply { timeZone = zone }
        val sb = StringBuilder()
        sb.append("GRAVADOR OCR — relatório da gravação\n")
        sb.append("Início: ").append(full.format(Date(startWall))).append('\n')
        sb.append("Palavras-chave: ")
            .append(if (keywords.isEmpty()) "(nenhuma)" else keywords.joinToString(", ") { KeywordSpec.labelOf(it) })
            .append("\n\n")
        sb.append("Partes do vídeo (pasta Movies/GravadorOCR):\n")
        if (segments.isEmpty()) sb.append("  (nenhuma parte salva)\n")
        for (s in segments) {
            sb.append("  ").append(s.index).append(". ").append(s.name)
                .append(" — ").append(TimeFmt.clock(s.durationMs))
            if (s.error != 0) sb.append(" (encerrada com código ").append(s.error).append(")")
            sb.append('\n')
        }
        val found = detections.count { it.kind == Detection.Kind.FOUND }
        sb.append("\nEventos (").append(found).append(" detecção(ões)):\n")
        if (detections.isEmpty()) sb.append("  (nenhum)\n")
        for (d in detections.sortedBy { it.wallTimeMs }) {
            sb.append("  ").append(hour.format(Date(d.wallTimeMs)))
                .append("  [parte ").append(d.segment).append(" @ ").append(TimeFmt.clock(d.segmentOffsetMs)).append("]  ")
            when (d.kind) {
                Detection.Kind.FOUND -> sb.append("ENCONTRADO: ").append(d.label)
                    .append(if (d.snippet.isNotBlank()) "  — lido: \"${d.snippet}\"" else "")
                Detection.Kind.MARK -> sb.append("MARCAÇÃO MANUAL")
                Detection.Kind.WARNING -> sb.append("AVISO: ").append(d.label)
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
