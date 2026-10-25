package io.github.neumanv.neudns.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
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
 *
 * Android asigna la VPN por identificador de usuario (UID), no por paquete. Las apps del sistema
 * comparten el UID del propio Android, así que nunca se añaden: el filtro llegaría a servicios
 * del sistema que nadie ha elegido.
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
    private lateinit var transport: HttpTransport

    /** Fin de la pausa (SystemClock.elapsedRealtime), o 0. Solo en memoria: si el servicio muere, se reanuda el bloqueo. */
    private var pausedUntil = 0L

    // Sigue la red por defecto (wifi o datos) por la que salen las consultas cifradas.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onDefaultNetwork(network)

        override fun onLost(network: Network) {
            if (transport.network == network) onDefaultNetwork(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        transport = HttpTransport(this)
        try {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: RuntimeException) {
            // Sin aviso de cambios de red las consultas siguen saliendo por la red por defecto.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stop(errorRes = null)
                return START_NOT_STICKY
            }
            ACTION_PAUSE, ACTION_RESUME -> {
                val duration = if (intent.action == ACTION_PAUSE) intent.getLongExtra(EXTRA_DURATION_MS, 0) else 0
                if (!pause(duration)) {
                    // El filtro no estaba encendido: no hay nada que pausar.
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
        // ACTION_START, o el propio sistema arrancándonos (VPN siempre activa, reinicio del servicio).
        prefs.enabled = true
        start(prefs)
        return START_STICKY
    }

    @Synchronized
    private fun start(prefs: Prefs) {
        val apps = prefs.selectedApps - packageName
        val http = transport
        // Si el sistema no ha podido darnos HTTP/2, se usa un servidor que no lo necesita.
        val provider = prefs.provider.takeUnless { it.needsHttp2 && !http.hasHttp2 } ?: PROVIDERS.first { !it.needsHttp2 }
        val newConfig = Config(apps, provider.url)
        // Con «Bloquear conexiones sin VPN» Android corta todo lo que no pasa por la VPN: las
        // apps no elegidas y el tráfico normal de las elegidas. Hay que avisar al usuario.
        val lockdown = isLockdownEnabled
        if (tunnel != null && newConfig == config) {
            // Las listas de dominios se aplican sin rehacer la VPN.
            resolver?.exceptions = prefs.exceptions
            resolver?.blocked = prefs.blocked
            if (VpnState.status.lockdown != lockdown) publishRunning(lockdown)
            return
        }
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
                if (!Process.isApplicationUid(packageManager.getApplicationInfo(pkg, 0).uid)) continue
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

        // Al encender el filtro el recuento empieza de cero; al cambiar las apps o el servidor, sigue.
        if (tunnel == null) VpnState.stats.reset()
        // Sin servidor sin bloqueo (Mullvad sin bloqueo, servidor personalizado) no se puede pausar.
        if (provider.unfilteredUrl == null) pausedUntil = 0
        val currentResolver = resolver?.takeIf { it.url == newConfig.url }
            ?: DohResolver(newConfig.url, http::open, provider.unfilteredUrl)
        currentResolver.exceptions = prefs.exceptions
        currentResolver.blocked = prefs.blocked
        currentResolver.pausedUntil = pausedUntil
        currentResolver.onAnswer = VpnState.stats::record
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
        publishRunning(lockdown)
    }

    private fun publishRunning(lockdown: Boolean) =
        VpnState.update(this, VpnState.Status(running = true, lockdown = lockdown, pausedUntil = pausedUntil))

    /**
     * Deja de bloquear durante [durationMs] (0 reanuda el bloqueo): las consultas van al servidor
     * sin bloqueo del mismo proveedor. Devuelve false si el filtro no está encendido.
     */
    @Synchronized
    private fun pause(durationMs: Long): Boolean {
        val current = resolver ?: return false
        if (tunnel == null) return false
        pausedUntil = if (durationMs > 0 && current.canPause) SystemClock.elapsedRealtime() + durationMs else 0
        current.pausedUntil = pausedUntil
        publishRunning(VpnState.status.lockdown)
        return true
    }

    /** Al cambiar de red se olvida la caché: lo guardado se obtuvo desde la red anterior. */
    @Synchronized
    private fun onDefaultNetwork(network: Network?) {
        val previous = transport.network
        if (network == previous) return
        transport.network = network
        if (previous != null) resolver?.clearCache()
    }

    @Synchronized
    private fun stop(errorRes: Int?) {
        tunnel?.stop()
        tunnel = null
        config = null
        pausedUntil = 0
        resolver?.pausedUntil = 0
        // Lo apuntado (recuento y diagnóstico) se olvida al apagar el filtro.
        VpnState.stats.reset()
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
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: RuntimeException) {
            // No llegó a registrarse.
        }
        executor.shutdownNow()
        transport.shutdown()
        if (VpnState.status.running) VpnState.update(this, VpnState.Status(running = false))
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "io.github.neumanv.neudns.START"
        private const val ACTION_STOP = "io.github.neumanv.neudns.STOP"
        private const val ACTION_PAUSE = "io.github.neumanv.neudns.PAUSE"
        private const val ACTION_RESUME = "io.github.neumanv.neudns.RESUME"
        private const val EXTRA_DURATION_MS = "duration_ms"

        private const val VPN_ADDRESS_V4 = "10.111.222.1"
        private const val DNS_ADDRESS_V4 = "10.111.222.2"
        private const val VPN_ADDRESS_V6 = "fd6e:6575:646e::1"
        private const val DNS_ADDRESS_V6 = "fd6e:6575:646e::2"

        // Con HTTP/2 todas las consultas comparten una conexión; varias a la vez evitan que una
        // app que abre muchas conexiones al arrancar (YouTube, navegadores) espere en cola.
        private const val MAX_WORKERS = 16
        private const val MAX_QUEUED_QUERIES = 256

        /**
         * Enciende el filtro o lo actualiza con la configuración guardada. Requiere permiso de VPN.
         * Devuelve false si Android no deja arrancar el servicio en este momento.
         */
        fun start(context: Context): Boolean = send(context, ACTION_START)

        fun stop(context: Context): Boolean = send(context, ACTION_STOP)

        /** Deja de bloquear durante [durationMs] sin apagar el filtro. */
        fun pause(context: Context, durationMs: Long): Boolean =
            send(context, ACTION_PAUSE) { putExtra(EXTRA_DURATION_MS, durationMs) }

        fun resume(context: Context): Boolean = send(context, ACTION_RESUME)

        private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}): Boolean = try {
            // Intent explícito: solo puede llegar a nuestro propio servicio.
            context.startService(Intent(context, DnsVpnService::class.java).setAction(action).apply(extras)) != null
        } catch (_: IllegalStateException) {
            false // la app está en segundo plano y Android no permite arrancar servicios
        } catch (_: SecurityException) {
            false
        }
    }
}
