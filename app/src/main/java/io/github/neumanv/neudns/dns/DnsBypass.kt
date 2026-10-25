package io.github.neumanv.neudns.dns

/**
 * Formas conocidas con las que una app puede saltarse el DNS del sistema.
 *
 * - Las IP de DNS públicos se desvían al túnel: si una app pregunta directamente a 8.8.8.8,
 *   la consulta también pasa por el filtro, y sus conexiones cifradas (DoH/DoT) se rechazan
 *   para que la app vuelva al DNS normal. Solo se incluyen direcciones dedicadas a DNS, para
 *   no cortar ningún otro servicio de las apps filtradas.
 * - Los dominios de DNS cifrado se responden como inexistentes, para que las apps no puedan
 *   averiguar la dirección de esos servidores. use-application-dns.net le indica a Firefox
 *   que no active su DNS cifrado propio, y resolver.arpa evita que se descubra un servidor
 *   cifrado preguntando al propio DNS (RFC 9462).
 */
internal object DnsBypass {

    val publicDnsV4 = listOf(
        "8.8.8.8", "8.8.4.4", // Google
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3", // Cloudflare
        "9.9.9.9", "149.112.112.112", "9.9.9.10", "149.112.112.10", "9.9.9.11", "149.112.112.11", // Quad9
        "208.67.222.222", "208.67.220.220", "208.67.222.123", "208.67.220.123", // OpenDNS
        "94.140.14.14", "94.140.15.15", "94.140.14.15", "94.140.15.16", "94.140.14.140", "94.140.14.141", // AdGuard
        "185.228.168.9", "185.228.169.9", "185.228.168.10", "185.228.169.11", "185.228.168.168", "185.228.169.168", // CleanBrowsing
        "45.90.28.0", "45.90.30.0", // NextDNS
        "194.242.2.2", "194.242.2.3", "194.242.2.4", "194.242.2.5", "194.242.2.6", "194.242.2.9", // Mullvad
        "76.76.2.0", "76.76.10.0", // Control D
        "185.222.222.222", "45.11.45.11", // DNS.SB
    )

    val publicDnsV6 = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844", "2001:4860:4860::6464", "2001:4860:4860::64",
        "2606:4700:4700::1111", "2606:4700:4700::1001", "2606:4700:4700::1112", "2606:4700:4700::1002",
        "2606:4700:4700::1113", "2606:4700:4700::1003", "2606:4700:4700::64", "2606:4700:4700::6400",
        "2620:fe::fe", "2620:fe::9", "2620:fe::10", "2620:fe::fe:10", "2620:fe::11", "2620:fe::fe:11",
        "2620:119:35::35", "2620:119:53::53", "2620:119:35::123", "2620:119:53::123",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff", "2a10:50c0::1:ff", "2a10:50c0::2:ff",
        "2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff",
        "2a0d:2a00:1::", "2a0d:2a00:2::", "2a0d:2a00:1::1", "2a0d:2a00:2::1", "2a0d:2a00:1::2", "2a0d:2a00:2::2",
        "2a07:a8c0::", "2a07:a8c1::",
        "2a07:e340::2", "2a07:e340::3", "2a07:e340::4", "2a07:e340::5", "2a07:e340::6", "2a07:e340::9",
        "2606:1a40::", "2606:1a40:1::",
        "2a09::", "2a11::",
    )

    private val encryptedDnsDomains = listOf(
        "use-application-dns.net",
        "resolver.arpa",
        "dns.google", "dns.google.com", "8888.google",
        "cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net", "dns12.quad9.net",
        "doh.opendns.com", "doh.familyshield.opendns.com",
        "dns.adguard-dns.com", "unfiltered.adguard-dns.com", "family.adguard-dns.com", "d.adguard-dns.com",
        "dns.adguard.com", "dns-unfiltered.adguard.com", "dns-family.adguard.com",
        "doh.cleanbrowsing.org",
        "dns.nextdns.io",
        "dns.mullvad.net", "doh.mullvad.net",
        "dns.controld.com", "freedns.controld.com",
        "doh.dns.sb", "doh.sb",
        "doh.xfinity.com",
        "dns.alidns.com", "doh.pub", "dns.pub", "doh.360.cn",
    )

    fun isEncryptedDnsDomain(name: String) =
        encryptedDnsDomains.any { name == it || name.endsWith(".$it") }
}
