package io.github.neumanv.neudns.dns

/**
 * Formas conocidas con las que una app puede saltarse el DNS del sistema.
 *
 * - Las IP de DNS públicos se desvían al túnel: si una app pregunta directamente a 8.8.8.8,
 *   la consulta también pasa por el filtro, y sus conexiones cifradas (DoH/DoT) se rechazan
 *   para que la app vuelva al DNS normal.
 * - Los dominios de DNS cifrado se responden como inexistentes, para que las apps no puedan
 *   averiguar la dirección de esos servidores. use-application-dns.net le indica a Firefox
 *   que no active su DNS cifrado propio.
 */
internal object DnsBypass {

    val publicDnsV4 = listOf(
        "8.8.8.8", "8.8.4.4", // Google
        "1.1.1.1", "1.0.0.1", // Cloudflare
        "9.9.9.9", "149.112.112.112", // Quad9
        "208.67.222.222", "208.67.220.220", // OpenDNS
        "94.140.14.14", "94.140.15.15", // AdGuard
    )

    val publicDnsV6 = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2620:fe::fe", "2620:fe::9",
        "2620:119:35::35", "2620:119:53::53",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
    )

    private val encryptedDnsDomains = listOf(
        "use-application-dns.net",
        "dns.google", "dns.google.com",
        "cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com",
        "dns.adguard-dns.com", "unfiltered.adguard-dns.com", "family.adguard-dns.com", "dns.adguard.com",
        "dns.nextdns.io",
        "dns.mullvad.net",
    )

    fun isEncryptedDnsDomain(name: String) =
        encryptedDnsDomains.any { name == it || name.endsWith(".$it") }
}
