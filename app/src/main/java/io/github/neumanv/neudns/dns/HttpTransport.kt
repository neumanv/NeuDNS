package io.github.neumanv.neudns.dns

import android.annotation.SuppressLint
import android.content.Context
import android.net.Network
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import java.net.HttpURLConnection
import java.net.URL

/**
 * Abre las conexiones HTTPS hacia el servidor DNS.
 *
 * Si el sistema incluye HttpEngine (la pila de red de Chrome que trae Android), se usa: habla
 * HTTP/2 y HTTP/3 y envía todas las consultas por una sola conexión, lo que gasta menos batería.
 * Algunos servidores (Mullvad) solo aceptan HTTP/2. Si no está disponible se usa
 * HttpURLConnection, que solo habla HTTP/1.1.
 *
 * HttpEngine sigue por sí mismo los cambios de red. Con HttpURLConnection las conexiones se abren
 * sobre [network], que tiene su propio conjunto de conexiones reutilizables: al pasar de wifi a
 * datos no se reutiliza una conexión de la red anterior que ya no responde.
 */
internal class HttpTransport(context: Context) {

    private val engine: HttpEngine? = if (isHttp2Available()) createEngine(context) else null

    val hasHttp2: Boolean get() = engine != null

    /** Red por defecto del dispositivo, o null si aún no se conoce. */
    @Volatile
    var network: Network? = null

    @SuppressLint("NewApi") // engine solo existe si isHttp2Available()
    fun open(url: URL): HttpURLConnection =
        (engine?.openConnection(url) ?: network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

    @SuppressLint("NewApi")
    fun shutdown() {
        try {
            engine?.shutdown()
        } catch (_: IllegalStateException) {
            // Quedaban peticiones en curso: se cierran solas al terminar.
        }
    }

    @SuppressLint("NewApi")
    private fun createEngine(context: Context): HttpEngine? = try {
        HttpEngine.Builder(context.applicationContext)
            .setEnableHttp2(true)
            .setEnableQuic(true)
            .setEnableBrotli(false)
            .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
            .build()
    } catch (_: RuntimeException) {
        null
    }

    companion object {
        /** HttpEngine existe en Android 14+, y en 11–13 si el sistema está actualizado. */
        fun isHttp2Available(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
    }
}
