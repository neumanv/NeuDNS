package io.github.neumanv.neudns

import android.content.Context
import io.github.neumanv.neudns.dns.HttpTransport

/**
 * Un servidor DNS-over-HTTPS al que se envían las consultas de las apps filtradas.
 * [nameRes] es un recurso de texto; [needsHttp2] indica que el servidor no acepta HTTP/1.1.
 */
data class DnsProvider(val nameRes: Int, val url: String, val needsHttp2: Boolean = false)

val PROVIDERS = listOf(
    DnsProvider(R.string.provider_adguard, "https://dns.adguard-dns.com/dns-query"),
    DnsProvider(R.string.provider_mullvad_base, "https://base.dns.mullvad.net/dns-query", needsHttp2 = true),
    DnsProvider(R.string.provider_mullvad_adblock, "https://adblock.dns.mullvad.net/dns-query", needsHttp2 = true),
    DnsProvider(R.string.provider_mullvad_plain, "https://dns.mullvad.net/dns-query", needsHttp2 = true),
)

/** Servidores compatibles con este dispositivo. */
val availableProviders: List<DnsProvider> by lazy {
    val http2 = HttpTransport.isHttp2Available()
    PROVIDERS.filter { http2 || !it.needsHttp2 }
}

/** Configuración guardada: qué apps se filtran, qué DNS se usa y si el filtro debe estar activo. */
class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    var selectedApps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_APPS, value).apply()

    /** Posición en [availableProviders]. */
    var providerIndex: Int
        get() = availableProviders.indexOfFirst { it.url == prefs.getString(KEY_PROVIDER_URL, null) }.coerceAtLeast(0)
        set(value) = prefs.edit().putString(KEY_PROVIDER_URL, availableProviders[value].url).apply()

    val provider: DnsProvider get() = availableProviders[providerIndex]

    /** El usuario quiere el filtro encendido: se restaura al reiniciar el dispositivo. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    private companion object {
        const val KEY_APPS = "apps"
        const val KEY_PROVIDER_URL = "provider_url"
        const val KEY_ENABLED = "enabled"
    }
}
