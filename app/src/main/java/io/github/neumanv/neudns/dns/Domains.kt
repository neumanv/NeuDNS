package io.github.neumanv.neudns.dns

import java.net.IDN
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException

/** Nombres de dominio tal como los escribe el usuario y como llegan en las consultas. */
object Domains {

    private const val MAX_NAME = 253
    private const val MAX_LABEL = 63

    /** true si [name] es uno de [domains] o un subdominio suyo («ads.ejemplo.com» está en «ejemplo.com»). */
    fun matches(name: String, domains: Set<String>): Boolean = match(name, domains) != null

    /**
     * El dominio de [domains] más concreto que abarca a [name], o null si ninguno lo abarca.
     * Para «ads.cdn.ejemplo.com» se prefiere «cdn.ejemplo.com» a «ejemplo.com».
     */
    fun match(name: String, domains: Set<String>): String? {
        if (domains.isEmpty()) return null
        var suffix = name
        while (true) {
            if (suffix in domains) return suffix
            val dot = suffix.indexOf('.')
            if (dot < 0) return null
            suffix = suffix.substring(dot + 1)
        }
    }

    /**
     * Convierte la dirección de un servidor DNS-over-HTTPS que escribe el usuario
     * («dns.nextdns.io/abc123», «https://doh.ejemplo.net/dns-query{?dns}») en una URL https
     * válida, o null si no lo es. Sin ruta se usa la habitual, /dns-query.
     */
    fun dohUrl(input: String): String? {
        val text = input.trim().removeSuffix("{?dns}")
        val rest = when {
            text.startsWith("https://", ignoreCase = true) -> text.substring("https://".length)
            "://" in text -> return null // http u otro protocolo: el DNS iría sin cifrar
            else -> text
        }
        if (rest.any { it.isWhitespace() || it == '@' || it == '#' || it == '\\' }) return null
        val authority = rest.substringBefore('/').substringBefore('?')
        val path = rest.substring(authority.length).ifEmpty { "/dns-query" }
        if (!path.startsWith('/')) return null
        val host = normalize(authority.substringBefore(':')) ?: return null
        val port = authority.substringAfter(':', "")
        if (port.isNotEmpty() && port.toIntOrNull() !in 1..65535) return null
        val url = "https://$host${if (port.isEmpty()) "" else ":$port"}$path"
        return try {
            URI(url).toURL()
            url
        } catch (_: URISyntaxException) {
            null
        } catch (_: MalformedURLException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Convierte lo que escribe el usuario («https://www.Ejemplo.com/ruta», «*.ejemplo.com»,
     * «españa.es») en el nombre que viaja en las consultas, o null si no es un dominio válido.
     */
    fun normalize(input: String): String? {
        var host = input.trim().substringAfter("://")
        host = host.substringBefore('/').substringBefore('?').substringBefore('#')
        host = host.substringAfterLast('@').substringBefore(':')
        host = host.removePrefix("*.").trim('.')
        if (host.isEmpty()) return null
        val ascii = try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (ascii.length > MAX_NAME || '.' !in ascii) return null
        val valid = ascii.split('.').all { label ->
            label.length in 1..MAX_LABEL &&
                !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        }
        return if (valid) ascii else null
    }
}
