package io.github.neumanv.neudns.vpn

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import io.github.neumanv.neudns.dns.BlockStats
import java.util.concurrent.CopyOnWriteArraySet

/** Estado del filtro, compartido entre el servicio, la pantalla y el botón de ajustes rápidos. */
object VpnState {

    /**
     * [errorRes] es un recurso de texto con el motivo por el que se apagó, si no fue el usuario.
     * [lockdown] indica que Android tiene activado «Bloquear conexiones sin VPN».
     * [pausedUntil] es el momento (SystemClock.elapsedRealtime) en que acaba la pausa, o 0.
     */
    data class Status(
        val running: Boolean,
        val errorRes: Int? = null,
        val lockdown: Boolean = false,
        val pausedUntil: Long = 0,
    ) {
        /** El filtro está encendido pero no bloquea nada hasta [pausedUntil]. */
        val paused: Boolean get() = running && pausedUntil > SystemClock.elapsedRealtime()
    }

    @Volatile
    var status = Status(running = false)
        private set

    /** Consultas y bloqueos desde que se encendió el filtro, solo en memoria. */
    val stats = BlockStats()

    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: (Status) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (Status) -> Unit) = listeners.remove(listener)

    internal fun update(context: Context, newStatus: Status) {
        status = newStatus
        main.post { listeners.forEach { it(newStatus) } }
        try {
            TileService.requestListeningState(context, ComponentName(context, ToggleTileService::class.java))
        } catch (_: RuntimeException) {
            // Sin el botón añadido algunos sistemas lo rechazan; se actualizará al abrir el panel.
        }
    }
}
