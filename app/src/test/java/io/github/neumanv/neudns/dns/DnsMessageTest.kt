package io.github.neumanv.neudns.dns

import io.github.neumanv.neudns.TestDns
import io.github.neumanv.neudns.net.u16
import io.github.neumanv.neudns.net.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun hex(text: String) = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Respuestas reales de los servidores para doubleclick.net y pornhub.com (con ID 0).

    @Test
    fun recognisesAdguardZeroAddress() {
        val response = hex(
            "0000 8180 0001 0001 0000 0000 0b646f75626c65636c69636b036e6574 00 0001 0001" +
                "c00c 0001 0001 00000e10 0004 00000000",
        )
        assertTrue(DnsMessage.isBlocked(response))
    }

    @Test
    fun recognisesAdguardBlockingSoa() {
        val uncompressed = hex(
            "0000 8180 0001 0000 0001 0000 0b646f75626c65636c69636b036e6574 00 0041 0001" +
                "c00c 0006 0001 00000e10 0048 1966616b652d666f722d6e656761746976652d63616368696e67" +
                "076164677561726403636f6d00 0a686f73746d6173746572c00c 00018894 00000708 00000384 00093a80 00015180",
        )
        assertTrue(DnsMessage.isBlocked(uncompressed))
        // Aquí el final del nombre del SOA («com») apunta al de la pregunta.
        val compressed = hex(
            "0000 8180 0001 0000 0001 0000 07706f726e687562 03636f6d 00 001c 0001" +
                "c00c 0006 0001 00000e10 0045 1966616b652d666f722d6e656761746976652d63616368696e67" +
                "0761646775617264 c014 0a686f73746d6173746572c00c 00018894 00000708 00000384 00093a80 00015180",
        )
        assertTrue(DnsMessage.isBlocked(compressed))
    }

    @Test
    fun recognisesMullvadBlockingNxdomain() {
        val response = hex(
            "0000 8183 0001 0000 0000 0001 0b646f75626c65636c69636b036e6574 00 0001 0001" +
                "156d756c6c7661642d626c6f636b6c6973742d616473 00 0006 0001 00000e10 003d" +
                "0372707a076d756c6c766164036e657400 07737570706f72740a6d756c6c76616476706e036e657400" +
                "6ad995e5 00015180 00001c20 00278d00 00000e10",
        )
        assertTrue(DnsMessage.isBlocked(response))
    }

    @Test
    fun ordinaryAnswersAreNotBlocks() {
        assertFalse(DnsMessage.isBlocked(TestDns.answer(TestDns.query("example.com"), 300)))
        // Un dominio que de verdad no existe: NXDOMAIN con el SOA de la zona .com.
        val nxdomain = hex(
            "0000 8183 0001 0000 0001 0000 166e6f6e6578697374656e742d78797a2d6e6575646e73 03636f6d 00 0001 0001" +
                "c023 0006 0001 00000384 0040 01610c67746c642d73657276657273036e657400" +
                "056e73746c640c766572697369676e2d67727303636f6d00 6adddb55 00000708 00000384 00093a80 00000384",
        )
        assertFalse(DnsMessage.isBlocked(nxdomain))
        assertFalse(DnsMessage.isBlocked(DnsMessage.errorResponse(TestDns.query("example.com"), DnsMessage.RCODE_SERVFAIL)!!))
        // Mal formada: el SOA dice ocupar más de lo que hay.
        assertFalse(DnsMessage.isBlocked(nxdomain.copyOf(nxdomain.size - 30)))
    }

    @Test
    fun compressionLoopsDoNotHang() {
        // El nombre del SOA es un puntero a sí mismo.
        val response = hex("0000 8180 0001 0000 0001 0000 03636f6d 00 0001 0001 c00c 0006 0001 00000e10 0016 c021") + ByteArray(20)
        assertFalse(DnsMessage.isBlocked(response))
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
