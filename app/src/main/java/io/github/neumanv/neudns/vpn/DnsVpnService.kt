package io.github.neumanv.neudns.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import io.github.neumanv.neudns.PROVIDERS
import io.github.neumanv.neudns.Prefs
import io.github.neumanv.neudns.R
import io.github.neumanv.neudns.dns.DnsBypass
import io.github.neumanv.neudns.dns.DohResolver
import io.github.neumanv.neudns.dns.HttpTransport
import io.github.neumanv.neudns.ui.MainActivity
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * VPN local que solo captura las consultas DNS de las apps elegidas.
 *
 * Las apps elegidas reciben como servidor DNS unas direcciones (IPv4 e IPv6) que solo existen
 * dentro de esta VPN, y solo esas direcciones (más las de DNS públicos conocidos) se enrutan
 * por el túnel. El resto de su tráfico sigue por la conexión normal, sin pasar por aquí, así
 * que ni la velocidad ni la batería se ven afectadas. Las apps no elegidas no se tocan.
 */
class DnsVpnService : VpnService() {

    /** Lo que define un túnel: si no cambia, no hace falta recrearlo. */
    private data class Config(val apps: Set<String>, val url: String)

    // Los hilos se crean al llegar consultas y desaparecen tras 30 s sin trabajo. La cola tiene
    // límite: si una app inunda de consultas, las que sobran se descartan en vez de agotar la memoria.
    private val executor = ThreadPoolExecutor(
        MAX_WORKERS, MAX_WORKERS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_QUEUED_QUERIES),
    ).apply { allowCoreThreadTimeOut(true) }

    private var tunnel: Tunnel? = null
    private var config: Config? = null
    private var resolver: DohResolver? = null
    private var transport: HttpTransport? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stop(errorRes = null)
            return START_NOT_STICKY
        }
        // ACTION_START, o el propio sistema arrancándonos (VPN siempre activa, reinicio del servicio).
        prefs.enabled = true
        start(prefs)
        return START_STICKY
    }

    @Synchronized
    private fun start(prefs: Prefs) {
        val apps = prefs.selectedApps - packageName
        val http = transport ?: HttpTransport(this).also { transport = it }
        // Si el sistema no ha podido darnos HTTP/2, se usa un servidor que no lo necesita.
        val provider = prefs.provider.takeUnless { it.needsHttp2 && !http.hasHttp2 } ?: PROVIDERS.first { !it.needsHttp2 }
        val newConfig = Config(apps, provider.url)
        if (tunnel != null && newConfig == config) return
        if (apps.isEmpty()) return stop(R.string.error_no_apps)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS_V4, 32)
            .addAddress(VPN_ADDRESS_V6, 128)
            .addDnsServer(DNS_ADDRESS_V4)
            .addDnsServer(DNS_ADDRESS_V6)
            .addRoute(DNS_ADDRESS_V4, 32)
            .addRoute(DNS_ADDRESS_V6, 128)
            .setMtu(Tunnel.MTU)
            .setBlocking(true)
            // Que las apps vean la red como lo que es (wifi o datos), no como una red de pago.
            .setMetered(false)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        DnsBypass.publicDnsV4.forEach { builder.addRoute(it, 32) }
        DnsBypass.publicDnsV6.forEach { builder.addRoute(it, 128) }

        var added = 0
        for (pkg in apps) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (_: PackageManager.NameNotFoundException) {
                // App desinstalada: se ignora.
            }
        }
        if (added == 0) return stop(R.string.error_apps_missing)

        val tun = try {
            builder.establish()
        } catch (_: RuntimeException) {
            null
        } ?: return stop(R.string.error_vpn_denied) // sin permiso de VPN, u otra VPN lo impide

        val currentResolver = resolver?.takeIf { it.url == newConfig.url } ?: DohResolver(newConfig.url, http::open)
        resolver = currentResolver
        // Al establecer la nueva interfaz Android retira la anterior; solo falta parar su lector.
        tunnel?.stop()
        tunnel = null
        tunnel = try {
            Tunnel(tun, currentResolver, executor).also { it.start() }
        } catch (_: ErrnoException) {
            closeQuietly(tun)
            return stop(R.string.error_internal)
        }
        config = newConfig
        VpnState.update(this, VpnState.Status(running = true))
    }

    @Synchronized
    private fun stop(errorRes: Int?) {
        tunnel?.stop()
        tunnel = null
        config = null
        VpnState.update(this, VpnState.Status(running = false, errorRes = errorRes))
        stopSelf()
    }

    private fun closeQuietly(tun: ParcelFileDescriptor) {
        try {
            tun.close()
        } catch (_: IOException) {
        }
    }

    override fun onRevoke() {
        // El usuario la ha quitado desde Ajustes u otra VPN ha tomado el control.
        Prefs(this).enabled = false
        stop(R.string.error_revoked)
    }

    override fun onDestroy() {
        synchronized(this) {
            tunnel?.stop()
            tunnel = null
        }
        executor.shutdownNow()
        transport?.shutdown()
        if (VpnState.status.running) VpnState.update(this, VpnState.Status(running = false))
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "io.github.neumanv.neudns.START"
        private const val ACTION_STOP = "io.github.neumanv.neudns.STOP"

        private const val VPN_ADDRESS_V4 = "10.111.222.1"
        private const val DNS_ADDRESS_V4 = "10.111.222.2"
        private const val VPN_ADDRESS_V6 = "fd6e:6575:646e::1"
        private const val DNS_ADDRESS_V6 = "fd6e:6575:646e::2"

        private const val MAX_WORKERS = 8
        private const val MAX_QUEUED_QUERIES = 256

        /**
         * Enciende el filtro o lo actualiza con la configuración guardada. Requiere permiso de VPN.
         * Devuelve false si Android no deja arrancar el servicio en este momento.
         */
        fun start(context: Context): Boolean = send(context, ACTION_START)

        fun stop(context: Context): Boolean = send(context, ACTION_STOP)

        private fun send(context: Context, action: String): Boolean = try {
            // Intent explícito: solo puede llegar a nuestro propio servicio.
            context.startService(Intent(context, DnsVpnService::class.java).setAction(action)) != null
        } catch (_: IllegalStateException) {
            false // la app está en segundo plano y Android no permite arrancar servicios
        } catch (_: SecurityException) {
            false
        }
    }
}
