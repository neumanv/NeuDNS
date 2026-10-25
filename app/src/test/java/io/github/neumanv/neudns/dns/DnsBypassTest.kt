package io.github.neumanv.neudns.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Una dirección mal escrita haría fallar addRoute() y con ella el arranque del filtro. */
class DnsBypassTest {

    // Solo se aceptan literales: así getByName() nunca hace una consulta DNS.
    private fun literal(address: String): InetAddress {
        assertTrue(address, address.all { it.isDigit() || it in "abcdef.:" })
        return InetAddress.getByName(address)
    }

    @Test
    fun publicDnsAddressesAreValidAndUnique() {
        DnsBypass.publicDnsV4.forEach { assertTrue(it, literal(it) is Inet4Address) }
        DnsBypass.publicDnsV6.forEach { assertTrue(it, literal(it) is Inet6Address) }
        val all = (DnsBypass.publicDnsV4 + DnsBypass.publicDnsV6).map { literal(it) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun encryptedDnsDomainsAndSubdomainsAreRecognised() {
        assertTrue(DnsBypass.isEncryptedDnsDomain("_dns.resolver.arpa"))
        assertTrue(DnsBypass.isEncryptedDnsDomain("chrome.cloudflare-dns.com"))
        assertTrue(DnsBypass.isEncryptedDnsDomain("abc123.dns.nextdns.io"))
        assertTrue(DnsBypass.isEncryptedDnsDomain("abc123.d.adguard-dns.com"))
        assertFalse(DnsBypass.isEncryptedDnsDomain("youtube.com"))
        assertFalse(DnsBypass.isEncryptedDnsDomain("notcloudflare-dns.com"))
        assertFalse(DnsBypass.isEncryptedDnsDomain("google.com"))
    }
}
