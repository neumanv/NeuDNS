package io.github.neumanv.neudns.dns

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Cuántas consultas se han contestado y cuántas se han bloqueado desde que se encendió el filtro.
 *
 * Solo mientras el usuario lo pide (diagnóstico) se apuntan además los últimos dominios
 * bloqueados, para que pueda ver qué necesita una app que ha dejado de funcionar. Todo vive en
 * memoria: nada se guarda en disco ni sale del dispositivo, y se olvida al apagar el filtro, al
 * detener el diagnóstico o un rato después de que termine.
 */
class BlockStats(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val maxRecent: Int = 200,
) {

    /** Un dominio bloqueado: si lo ha bloqueado el usuario o el servidor, y cuántas veces. */
    data class Recent(val domain: String, val byUser: Boolean, val count: Int)

    private val queries = AtomicLong()
    private val blocked = AtomicLong()

    // Orden de llegada: al repetirse un dominio se saca y se vuelve a meter al final.
    private val recent = LinkedHashMap<String, Recent>()

    @Volatile
    private var recordingUntil = 0L

    val queryCount: Long get() = queries.get()
    val blockedCount: Long get() = blocked.get()

    val isRecording: Boolean get() = now() < recordingUntil

    /** Milisegundos que le quedan al diagnóstico; 0 si no está en marcha. */
    val recordingRemainingMs: Long get() = (recordingUntil - now()).coerceAtLeast(0)

    fun record(name: String, block: DohResolver.Block) {
        queries.incrementAndGet()
        if (block == DohResolver.Block.NONE) return
        blocked.incrementAndGet()
        if (!isRecording) return
        synchronized(recent) {
            val previous = recent.remove(name)
            recent[name] = Recent(name, block == DohResolver.Block.USER, (previous?.count ?: 0) + 1)
            if (recent.size > maxRecent) recent.remove(recent.keys.first())
        }
    }

    fun startRecording(durationMs: Long) {
        recordingUntil = now() + durationMs
    }

    fun stopRecording() {
        recordingUntil = 0
        synchronized(recent) { recent.clear() }
    }

    /** Últimos dominios bloqueados durante el diagnóstico, el más reciente primero. */
    fun recent(): List<Recent> {
        if (recordingUntil != 0L && now() >= recordingUntil + KEEP_AFTER_MS) stopRecording()
        return synchronized(recent) { recent.values.reversed() }
    }

    /** Vuelve a cero y olvida todo, por ejemplo al apagar el filtro. */
    fun reset() {
        queries.set(0)
        blocked.set(0)
        stopRecording()
    }

    private companion object {
        /** Lo apuntado se conserva un rato tras el diagnóstico, para poder consultarlo con calma. */
        const val KEEP_AFTER_MS = 30 * 60 * 1000L
    }
}
