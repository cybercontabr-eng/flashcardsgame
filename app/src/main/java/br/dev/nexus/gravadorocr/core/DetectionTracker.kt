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
 * - Leitura com erro de OCR: precisa ser vista 2 vezes em [confirmWindowMs] (evita alarme falso)
 *   e, enquanto isso, pede para segurar firme.
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
    private val lastHold = HashMap<String, Long>()

    fun update(results: List<MatchResult>, now: Long): List<TrackerEvent> {
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
                        if (p != null && now - p <= confirmWindowMs) {
                            true
                        } else {
                            pendingFuzzy[key] = now
                            false
                        }
                    }
                    if (confirmed) {
                        pendingFuzzy.remove(key)
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
        lastHold.clear()
    }
}
