package io.github.neumanv.neudns.net

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

private const val TAG = "NeuDNS"

/**
 * Ejecuta [task] en segundo plano si queda sitio en la cola. Devuelve false si se ha descartado
 * (cola llena o servicio cerrándose): así una app que inunde de consultas no agota la memoria.
 */
internal fun Executor.trySubmit(task: () -> Unit): Boolean = try {
    execute { runGuarded(task) }
    true
} catch (_: RejectedExecutionException) {
    false
}

/**
 * Ejecuta [block] sin dejar escapar errores de programación. En Android una excepción no
 * capturada en cualquier hilo cierra la app, y con ella el filtro; un paquete inesperado nunca
 * debe poder provocarlo. Solo se registra el tipo de error, nunca el contenido del tráfico.
 */
internal fun runGuarded(block: () -> Unit) {
    try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Paquete descartado: ${e.javaClass.simpleName}")
    }
}
