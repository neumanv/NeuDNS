package io.github.neumanv.neudns.net

import io.github.neumanv.neudns.TestDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Executor

class PacketTest {

    private val client4 = InetAddress.getByName("10.111.222.1").address
    private val dns4 = InetAddress.getByName("10.111.222.2").address
    private val client6 = InetAddress.getByName("fd6e:6575:646e::1").address
    private val dns6 = InetAddress.getByName("fd6e:6575:646e::2").address

    // ---------- IP y sumas de verificación ----------

    @Test
    fun ipv4UdpRoundTripWithValidChecksums() {
        val packet = IpPacket.build(4, client4, dns4, IpPacket.UDP, udp(40000, 53, TestDns.query("example.com")))
        assertEquals(0xFFFF, fold(sum(packet, 0, 20)))
        assertTrue(validL4Checksum(packet, 20, client4, dns4, IpPacket.UDP))

        val parsed = IpPacket.parse(packet, packet.size)!!
        assertEquals(4, parsed.version)
        assertEquals(40000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)

        val reply = parse(parsed.reply(IpPacket.UDP, udp(53, 40000, ByteArray(12))))
        assertEquals(dns4.toList(), reply.source.toList())
        assertEquals(client4.toList(), reply.destination.toList())
    }

    @Test
    fun ipv6UdpHasValidChecksum() {
        val packet = IpPacket.build(6, client6, dns6, IpPacket.UDP, udp(40000, 53, TestDns.query("example.com")))
        assertTrue(validL4Checksum(packet, 40, client6, dns6, IpPacket.UDP))
        val parsed = IpPacket.parse(packet, packet.size)!!
        assertEquals(6, parsed.version)
        assertEquals(53, parsed.destinationPort)
    }

    @Test
    fun ignoresFragmentsAndOtherProtocols() {
        val packet = IpPacket.build(4, client4, dns4, IpPacket.UDP, udp(40000, 53, ByteArray(12)))
        packet[6] = 0x20 // More Fragments
        assertNull(IpPacket.parse(packet, packet.size))

        val icmp = IpPacket.build(4, client4, dns4, 1, ByteArray(8))
        assertNull(IpPacket.parse(icmp, icmp.size))
    }

    // ---------- TCP ----------

    @Test
    fun answersDnsOverTcp() {
        val query = TestDns.query("example.com")
        val response = TestDns.answer(query, 60)
        val sent = mutableListOf<IpPacket>()
        val raw = mutableListOf<ByteArray>()
        val handler = TcpDnsHandler({ response }, Executor { it.run() }, {
            raw += it
            sent += parse(it)
        }, maxSegment = 16)

        // Apertura
        handler.handle(tcpPacket(seq = 1000, ack = 0, flags = SYN))
        val synAck = sent.single()
        assertEquals(SYN or ACK, synAck.flags())
        assertEquals(1001L, synAck.segment.u32(8))
        val serverIsn = synAck.segment.u32(4)
        assertTrue(validL4Checksum(raw.single(), 20, dns4, client4, IpPacket.TCP))

        // Consulta: 2 bytes de longitud + mensaje
        sent.clear()
        val framed = byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()) + query
        handler.handle(tcpPacket(seq = 1001, ack = serverIsn + 1, flags = ACK or PSH, data = framed))

        val received = java.io.ByteArrayOutputStream()
        var expectedSeq = serverIsn + 1
        for (segment in sent.filter { it.payload().isNotEmpty() }) {
            assertEquals(expectedSeq, segment.segment.u32(4))
            assertTrue(segment.payload().size <= 16)
            received.write(segment.payload())
            expectedSeq += segment.payload().size
        }
        val bytes = received.toByteArray()
        assertEquals(response.size, bytes.u16(0))
        assertEquals(response.toList(), bytes.copyOfRange(2, bytes.size).toList())
        assertEquals(1001L + framed.size, sent.last().segment.u32(8))

        // Cierre
        sent.clear()
        handler.handle(tcpPacket(seq = 1001L + framed.size, ack = expectedSeq, flags = FIN or ACK))
        val fin = sent.single()
        assertEquals(FIN or ACK, fin.flags())
        assertEquals(1002L + framed.size, fin.segment.u32(8))
    }

    @Test
    fun queryAndFinTogetherStillGetAnswer() {
        val query = TestDns.query("example.com")
        val sent = mutableListOf<IpPacket>()
        val handler = TcpDnsHandler({ TestDns.answer(query, 60) }, Executor { it.run() }, { sent += parse(it) }, 1400)
        handler.handle(tcpPacket(seq = 5, ack = 0, flags = SYN))
        val isn = sent.last().segment.u32(4)
        val framed = byteArrayOf(0, query.size.toByte()) + query
        handler.handle(tcpPacket(seq = 6, ack = isn + 1, flags = ACK or PSH or FIN, data = framed))
        assertTrue(sent.any { it.payload().isNotEmpty() })
        assertEquals(FIN or ACK, sent.last().flags())
    }

    @Test
    fun rejectsEncryptedDnsWithReset() {
        val sent = mutableListOf<IpPacket>()
        val handler = TcpDnsHandler({ null }, Executor { it.run() }, { sent += parse(it) }, 1400)
        handler.handle(tcpPacket(seq = 77, ack = 0, flags = SYN, dstPort = 853))
        val reset = sent.single()
        assertEquals(RST or ACK, reset.flags())
        assertEquals(78L, reset.segment.u32(8))
        assertEquals(853, reset.sourcePort)
    }

    @Test
    fun floodOfQueriesOnOneConnectionIsCut() {
        val queued = mutableListOf<Runnable>()
        val sent = mutableListOf<IpPacket>()
        // Ejecutor que no ejecuta nada: las consultas se quedan pendientes.
        val handler = TcpDnsHandler({ null }, Executor { queued += it }, { sent += parse(it) }, 1400)
        handler.handle(tcpPacket(seq = 1, ack = 0, flags = SYN))
        val isn = sent.last().segment.u32(4)

        val query = TestDns.query("example.com")
        val one = byteArrayOf(0, query.size.toByte()) + query
        val flood = (1..20).fold(ByteArray(0)) { acc, _ -> acc + one }
        handler.handle(tcpPacket(seq = 2, ack = isn + 1, flags = ACK or PSH, data = flood))

        assertEquals(16, queued.size)
        assertEquals(RST or ACK, sent.last().flags())
    }

    @Test
    fun rejectedTaskIsReportedNotThrown() {
        val full = Executor { throw java.util.concurrent.RejectedExecutionException() }
        assertEquals(false, full.trySubmit { })
    }

    @Test
    fun guardedBlockSwallowsProgrammingErrors() {
        runGuarded { throw ArrayIndexOutOfBoundsException() } // no debe propagarse
    }

    @Test
    fun ignoresAcksForUnknownConnections() {
        val sent = mutableListOf<IpPacket>()
        val handler = TcpDnsHandler({ null }, Executor { it.run() }, { sent += parse(it) }, 1400)
        handler.handle(tcpPacket(seq = 1, ack = 1, flags = ACK))
        assertTrue(sent.isEmpty())
    }

    // ---------- Utilidades ----------

    private fun udp(src: Int, dst: Int, payload: ByteArray) = ByteArray(8 + payload.size).apply {
        put16(0, src)
        put16(2, dst)
        put16(4, size)
        System.arraycopy(payload, 0, this, 8, payload.size)
    }

    private fun tcpPacket(seq: Long, ack: Long, flags: Int, data: ByteArray = ByteArray(0), dstPort: Int = 53): IpPacket {
        val segment = ByteArray(20 + data.size).apply {
            put16(0, 40000)
            put16(2, dstPort)
            put32(4, seq)
            put32(8, ack)
            this[12] = 0x50
            this[13] = flags.toByte()
            put16(14, 65535)
            System.arraycopy(data, 0, this, 20, data.size)
        }
        return parse(IpPacket.build(4, client4, dns4, IpPacket.TCP, segment))
    }

    private fun parse(bytes: ByteArray): IpPacket {
        val packet = IpPacket.parse(bytes, bytes.size)
        assertNotNull(packet)
        return packet!!
    }

    private fun IpPacket.flags() = segment.u8(13)
    private fun IpPacket.payload() = segment.copyOfRange((segment.u8(12) ushr 4) * 4, segment.size)

    private fun sum(b: ByteArray, offset: Int, length: Int): Long {
        var total = 0L
        var i = offset
        while (i + 1 < offset + length) {
            total += b.u16(i)
            i += 2
        }
        if (i < offset + length) total += b.u8(i) shl 8
        return total
    }

    private fun fold(value: Long): Int {
        var v = value
        while (v ushr 16 != 0L) v = (v and 0xFFFF) + (v ushr 16)
        return v.toInt()
    }

    private fun validL4Checksum(packet: ByteArray, header: Int, src: ByteArray, dst: ByteArray, protocol: Int): Boolean {
        val length = packet.size - header
        val pseudo = sum(src, 0, src.size) + sum(dst, 0, dst.size) + protocol + length
        return fold(pseudo + sum(packet, header, length)) == 0xFFFF
    }

    private companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
    }
}
