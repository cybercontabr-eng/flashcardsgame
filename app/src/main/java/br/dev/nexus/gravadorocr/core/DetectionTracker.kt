package br.dev.nexus.gravadorocr.core

sealed class TrackerEvent {
    abstract val result: MatchResult

    /** Palavra confirmada: apitar/vibrar/falar. */
    data class Found(override val result: MatchResult) : TrackerEvent()

    /** Quase leu: vibração curta para segurar o celular parado. */
    data class HoldSteady(override val result: MatchResult) : TrackerEvent()
}

/**
 * Decide quando avisar, evitando repetir o alerta a cada quadro.
 * - Leitura exata: avisa na hora.
 * - Leitura com erro de OCR: precisa ser vista 2 vezes — em até [confirmWindowMs] ou em quadros
 *   seguidos (para celulares lentos) — evitando alarme falso; enquanto isso, pede para segurar firme.
 * - Depois de avisar, só avisa de novo se a palavra sumir por [cooldownMs] e voltar,
 *   ou como lembrete a cada [repeatMs] se continuar na frente da câmera.
 */
class DetectionTracker(
    var cooldownMs: Long = 8_000,
    private val confirmWindowMs: Long = 3_000,
    private val holdCooldownMs: Long = 2_500,
    private val repeatMs: Long = 60_000,
) {
    private val lastAlert = HashMap<String, Long>()
    private val lastSeen = HashMap<String, Long>()
    private val pendingFuzzy = HashMap<String, Long>()
    private val pendingFrame = HashMap<String, Long>()
    private val lastHold = HashMap<String, Long>()
    private var frame = 0L

    /** Idade máxima de uma leitura pendente quando a confirmação é por quadros seguidos. */
    private val maxFrameConfirmMs = 20_000L

    fun update(results: List<MatchResult>, now: Long): List<TrackerEvent> {
        frame++
        val events = ArrayList<TrackerEvent>()
        for (r in results) {
            val key = r.spec.key
            val seenRecently = lastSeen[key]?.let { now - it < cooldownMs } == true
            when (r.kind) {
                MatchKind.FULL -> {
                    val confirmed = if (r.distance == 0) {
                        true
                    } else {
                        val p = pendingFuzzy[key]
                        val pf = pendingFrame[key]
                        val recentTime = p != null && now - p <= confirmWindowMs
                        val previousFrame = p != null && pf != null && frame - pf <= 2 && now - p <= maxFrameConfirmMs
                        if (recentTime || previousFrame) {
                            true
                        } else {
                            pendingFuzzy[key] = now
                            pendingFrame[key] = frame
                            false
                        }
                    }
                    if (confirmed) {
                        pendingFuzzy.remove(key)
                        pendingFrame.remove(key)
                        val prevSeen = lastSeen[key]
                        val prevAlert = lastAlert[key]
                        lastSeen[key] = now
                        val shouldAlert = prevAlert == null ||
                            prevSeen == null ||
                            now - prevSeen >= cooldownMs ||
                            now - prevAlert >= repeatMs
                        if (shouldAlert) {
                            lastAlert[key] = now
                            events.add(TrackerEvent.Found(r))
                        }
                    } else if (!seenRecently) {
                        maybeHold(key, r, now, events)
                    }
                }
                MatchKind.PARTIAL -> if (!seenRecently) maybeHold(key, r, now, events)
            }
        }
        return events
    }

    private fun maybeHold(key: String, r: MatchResult, now: Long, out: MutableList<TrackerEvent>) {
        val lh = lastHold[key]
        if (lh == null || now - lh >= holdCooldownMs) {
            lastHold[key] = now
            out.add(TrackerEvent.HoldSteady(r))
        }
    }

    fun reset() {
        lastAlert.clear()
        lastSeen.clear()
        pendingFuzzy.clear()
        pendingFrame.clear()
        lastHold.clear()
        frame = 0
    }
}
