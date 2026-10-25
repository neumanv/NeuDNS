package io.github.neumanv.neudns

import android.content.Context
import io.github.neumanv.neudns.dns.HttpTransport

/**
 * Un servidor DNS-over-HTTPS al que se envían las consultas de las apps filtradas.
 * [nameRes] es un recurso de texto; [needsHttp2] indica que el servidor no acepta HTTP/1.1.
 * [unfilteredUrl] es el servidor sin bloqueo del mismo proveedor, para las excepciones y la pausa
 * (null si este ya no bloquea nada o no se conoce, como en un servidor personalizado).
 */
data class DnsProvider(val nameRes: Int, val url: String, val unfilteredUrl: String?, val needsHttp2: Boolean = false)

private const val ADGUARD_UNFILTERED = "https://unfiltered.adguard-dns.com/dns-query"
private const val MULLVAD_UNFILTERED = "https://dns.mullvad.net/dns-query"

val PROVIDERS = listOf(
    DnsProvider(R.string.provider_adguard, "https://dns.adguard-dns.com/dns-query", ADGUARD_UNFILTERED),
    DnsProvider(R.string.provider_adguard_family, "https://family.adguard-dns.com/dns-query", ADGUARD_UNFILTERED),
    DnsProvider(R.string.provider_mullvad_base, "https://base.dns.mullvad.net/dns-query", MULLVAD_UNFILTERED, needsHttp2 = true),
    DnsProvider(R.string.provider_mullvad_adblock, "https://adblock.dns.mullvad.net/dns-query", MULLVAD_UNFILTERED, needsHttp2 = true),
    DnsProvider(R.string.provider_mullvad_extended, "https://extended.dns.mullvad.net/dns-query", MULLVAD_UNFILTERED, needsHttp2 = true),
    DnsProvider(R.string.provider_mullvad_family, "https://family.dns.mullvad.net/dns-query", MULLVAD_UNFILTERED, needsHttp2 = true),
    DnsProvider(R.string.provider_mullvad_all, "https://all.dns.mullvad.net/dns-query", MULLVAD_UNFILTERED, needsHttp2 = true),
    DnsProvider(R.string.provider_mullvad_plain, MULLVAD_UNFILTERED, null, needsHttp2 = true),
)

/** Servidores compatibles con este dispositivo. */
val availableProviders: List<DnsProvider> by lazy {
    val http2 = HttpTransport.isHttp2Available()
    PROVIDERS.filter { http2 || !it.needsHttp2 }
}

/** Configuración guardada: qué apps se filtran, qué DNS se usa, qué dominios se permiten o bloquean y si el filtro debe estar activo. */
class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    var selectedApps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_APPS, value).apply()

    /** Servidor DNS-over-HTTPS que ha escrito el usuario (ya validado con Domains.dohUrl), o null. */
    var customUrl: String?
        get() = prefs.getString(KEY_CUSTOM_URL, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_URL, value).apply()

    /** Posición en el selector: las de [availableProviders] y, justo después, la del servidor personalizado. */
    var providerIndex: Int
        get() {
            val url = prefs.getString(KEY_PROVIDER_URL, null)
            if (url != null && url == customUrl) return CUSTOM_INDEX
            return availableProviders.indexOfFirst { it.url == url }.coerceAtLeast(0)
        }
        set(value) {
            val url = if (value == CUSTOM_INDEX) customUrl ?: return else availableProviders[value].url
            prefs.edit().putString(KEY_PROVIDER_URL, url).apply()
        }

    val provider: DnsProvider
        get() = availableProviders.getOrNull(providerIndex)
            ?: customUrl?.let { DnsProvider(R.string.provider_custom, it, null) }
            ?: availableProviders.first()

    val isCustomProvider: Boolean get() = providerIndex == CUSTOM_INDEX

    /** Dominios que nunca se bloquean, ya normalizados con Domains.normalize. */
    var exceptions: Set<String>
        get() = prefs.getStringSet(KEY_EXCEPTIONS, null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_EXCEPTIONS, value).apply()

    /** Dominios que el usuario bloquea además de los del servidor, ya normalizados con Domains.normalize. */
    var blocked: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, null)?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED, value).apply()

    /** El usuario quiere el filtro encendido: se restaura al reiniciar el dispositivo. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        /** Posición del servidor personalizado en el selector. */
        val CUSTOM_INDEX: Int get() = availableProviders.size

        private const val KEY_APPS = "apps"
        private const val KEY_PROVIDER_URL = "provider_url"
        private const val KEY_CUSTOM_URL = "custom_url"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EXCEPTIONS = "exceptions"
        private const val KEY_BLOCKED = "blocked"
    }
}
