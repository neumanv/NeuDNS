package io.github.neumanv.neudns.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainsTest {

    @Test
    fun matchesDomainAndSubdomainsOnly() {
        val domains = setOf("ejemplo.com")
        assertTrue(Domains.matches("ejemplo.com", domains))
        assertTrue(Domains.matches("ads.cdn.ejemplo.com", domains))
        assertFalse(Domains.matches("otroejemplo.com", domains))
        assertFalse(Domains.matches("ejemplo.com.evil.net", domains))
        assertFalse(Domains.matches("com", domains))
        assertFalse(Domains.matches("ejemplo.com", emptySet()))
    }

    @Test
    fun matchReturnsMostSpecificDomain() {
        val domains = setOf("ejemplo.com", "cdn.ejemplo.com")
        assertEquals("cdn.ejemplo.com", Domains.match("ads.cdn.ejemplo.com", domains))
        assertEquals("ejemplo.com", Domains.match("www.ejemplo.com", domains))
        assertNull(Domains.match("ejemplo.net", domains))
    }

    @Test
    fun normalizesDohUrls() {
        assertEquals("https://dns.nextdns.io/abc123", Domains.dohUrl(" dns.nextdns.io/abc123 "))
        assertEquals("https://doh.ejemplo.net/dns-query", Domains.dohUrl("https://DOH.ejemplo.net"))
        assertEquals("https://doh.ejemplo.net/dns-query", Domains.dohUrl("HTTPS://doh.ejemplo.net/dns-query{?dns}"))
        assertEquals("https://doh.ejemplo.net:8443/q", Domains.dohUrl("https://doh.ejemplo.net:8443/q"))
        assertEquals("https://xn--espaa-rta.es/dns-query", Domains.dohUrl("españa.es"))
    }

    @Test
    fun rejectsUnsafeOrInvalidDohUrls() {
        assertNull(Domains.dohUrl("http://doh.ejemplo.net/dns-query"))
        assertNull(Domains.dohUrl("ftp://doh.ejemplo.net"))
        assertNull(Domains.dohUrl("https://usuario@doh.ejemplo.net/dns-query"))
        assertNull(Domains.dohUrl("https://doh.ejemplo.net:0/dns-query"))
        assertNull(Domains.dohUrl("https://doh.ejemplo.net:abc/dns-query"))
        assertNull(Domains.dohUrl("https://doh.ejemplo.net/dns query"))
        assertNull(Domains.dohUrl("https://localhost/dns-query"))
        assertNull(Domains.dohUrl(""))
    }

    @Test
    fun normalizesWhatTheUserTypes() {
        assertEquals("www.ejemplo.com", Domains.normalize("  https://www.Ejemplo.com/ruta?x=1#a "))
        assertEquals("ejemplo.com", Domains.normalize("*.ejemplo.com"))
        assertEquals("ejemplo.com", Domains.normalize("ejemplo.com."))
        assertEquals("ejemplo.com", Domains.normalize("usuario@ejemplo.com:8443"))
        assertEquals("xn--espaa-rta.es", Domains.normalize("españa.es"))
        assertEquals("_dmarc.ejemplo.com", Domains.normalize("_dmarc.ejemplo.com"))
    }

    @Test
    fun rejectsInvalidNames() {
        assertNull(Domains.normalize(""))
        assertNull(Domains.normalize("localhost"))
        assertNull(Domains.normalize("ejemplo..com"))
        assertNull(Domains.normalize("-ejemplo.com"))
        assertNull(Domains.normalize("ejem plo.com"))
        assertNull(Domains.normalize("a".repeat(64) + ".com"))
    }
}
