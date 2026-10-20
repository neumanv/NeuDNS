package io.github.neumanv.neudns.dns

import io.github.neumanv.neudns.TestDns
import io.github.neumanv.neudns.net.u16
import io.github.neumanv.neudns.net.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    @Test
    fun readsQuestionNameInLowercase() {
        assertEquals("ads.example.com", DnsMessage.questionName(TestDns.query("Ads.Example.COM")))
    }

    @Test
    fun questionEndPointsAfterTypeAndClass() {
        val query = TestDns.query("example.com")
        // 12 de cabecera + 13 del nombre + 4 de tipo y clase
        assertEquals(29, DnsMessage.questionEnd(query))
    }

    @Test
    fun rejectsMalformedMessages() {
        assertEquals(-1, DnsMessage.questionEnd(ByteArray(5)))
        val cut = TestDns.query("example.com").copyOf(20)
        assertEquals(-1, DnsMessage.questionEnd(cut))
        assertNull(DnsMessage.errorResponse(cut, DnsMessage.RCODE_SERVFAIL))
    }

    @Test
    fun maxUdpSizeComesFromEdns() {
        assertEquals(512, DnsMessage.maxUdpResponseSize(TestDns.query("example.com")))
        assertEquals(1232, DnsMessage.maxUdpResponseSize(TestDns.query("example.com", ednsSize = 1232)))
        // Valores menores de 512 no son válidos y se tratan como 512.
        assertEquals(512, DnsMessage.maxUdpResponseSize(TestDns.query("example.com", ednsSize = 100)))
    }

    @Test
    fun errorResponseKeepsIdAndQuestion() {
        val query = TestDns.query("example.com", id = 0xBEEF, ednsSize = 1232)
        val response = DnsMessage.errorResponse(query, DnsMessage.RCODE_NXDOMAIN)!!
        assertEquals(0xBEEF, response.u16(0))
        assertTrue(!DnsMessage.isQuery(response))
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(response))
        assertEquals(1, response.u16(4))
        assertEquals(0, response.u16(10)) // sin la sección EDNS
        assertEquals(DnsMessage.questionEnd(query), response.size)
    }

    @Test
    fun truncatedResponseHasTcBitAndNoRecords() {
        val query = TestDns.query("example.com")
        val truncated = DnsMessage.truncated(TestDns.answer(query, 60, 60))!!
        assertTrue(DnsMessage.isTruncated(truncated))
        assertEquals(0, truncated.u16(6))
        assertEquals(DnsMessage.questionEnd(query), truncated.size)
    }

    @Test
    fun findsEveryTtl() {
        val response = TestDns.answer(TestDns.query("example.com"), 300, 20)
        val offsets = DnsMessage.ttlOffsets(response)
        assertNotNull(offsets)
        assertArrayEquals(longArrayOf(300, 20), offsets!!.map { response.u32(it) }.toLongArray())
    }

    @Test
    fun recognisesEncryptedDnsDomains() {
        assertTrue(DnsBypass.isEncryptedDnsDomain("dns.google"))
        assertTrue(DnsBypass.isEncryptedDnsDomain("mozilla.cloudflare-dns.com"))
        assertTrue(DnsBypass.isEncryptedDnsDomain("use-application-dns.net"))
        assertTrue(!DnsBypass.isEncryptedDnsDomain("google.com"))
        assertTrue(!DnsBypass.isEncryptedDnsDomain("notdns.google.com.example"))
    }
}
