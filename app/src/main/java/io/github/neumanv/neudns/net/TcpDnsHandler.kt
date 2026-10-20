package io.github.neumanv.neudns.net

import android.os.SystemClock
import io.github.neumanv.neudns.dns.DnsMessage
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.Executor

/**
 * TCP mínimo, solo para DNS sobre TCP (puerto 53, RFC 7766).
 *
 * Las apps usan TCP cuando la respuesta no cabe en UDP. Como el túnel es local no se pierden
 * paquetes, así que no hacen falta retransmisiones ni control de congestión: basta con abrir
 * la conexión, confirmar lo recibido, responder y cerrar.
 *
 * Cualquier otra conexión TCP que llegue (DNS cifrado a 853 o 443 de los DNS públicos
 * desviados) se rechaza con RST, para que la app vuelva al DNS normal en vez de esperar.
 */
internal class TcpDnsHandler(
    private val resolve: (ByteArray) -> ByteArray?,
    private val executor: Executor,
    private val send: (ByteArray) -> Unit,
    private val maxSegment: Int,
) {
    private class Connection(val syn: IpPacket, var clientNext: Long, var serverNext: Long) {
        val pending = ByteArrayOutputStream()
        var inFlight = 0
        var clientClosed = false
        var lastActivity = SystemClock.elapsedRealtime()
    }

    private val connections = HashMap<String, Connection>()
    private val random = SecureRandom()

    fun handle(packet: IpPacket) {
        val segment = packet.segment
        val headerLength = (segment.u8(12) ushr 4) * 4
        if (headerLength < 20 || headerLength > segment.size) return
        val flags = segment.u8(13)
        val seq = segment.u32(4)
        val payloadLength = segment.size - headerLength
        val key = key(packet)

        if (flags and RST != 0) {
            synchronized(connections) { connections.remove(key) }
            return
        }
        if (packet.destinationPort != DNS_PORT) {
            reset(packet, flags, seq, segment.u32(8), payloadLength)
            return
        }

        synchronized(connections) {
            var connection = connections[key]

            if (flags and SYN != 0) {
                if (flags and ACK != 0) return
                if (connection == null) {
                    removeIdle()
                    if (connections.size >= MAX_CONNECTIONS) {
                        reset(packet, flags, seq, 0, payloadLength)
                        return
                    }
                    val isn = random.nextInt().toLong() and MASK
                    connection = Connection(packet, (seq + 1) and MASK, (isn + 1) and MASK)
                    connections[key] = connection
                }
                // También responde a un SYN repetido.
                sendSegment(connection, (connection.serverNext - 1) and MASK, SYN or ACK)
                return
            }

            if (connection == null) {
                // Restos de una conexión ya cerrada: solo se rechazan si traen datos.
                if (payloadLength > 0 || flags and FIN != 0) reset(packet, flags, seq, segment.u32(8), payloadLength)
                return
            }
            connection.lastActivity = SystemClock.elapsedRealtime()

            if (payloadLength > 0) {
                if (seq == connection.clientNext) {
                    connection.pending.write(segment, headerLength, payloadLength)
                    connection.clientNext = (connection.clientNext + payloadLength) and MASK
                    if (!dispatchQueries(key, connection)) {
                        close(key, connection, RST or ACK)
                        return
                    }
                }
                // Si llegó fuera de orden, este ACK repetido le pide a la app que lo reenvíe.
                if (flags and FIN == 0) sendSegment(connection, connection.serverNext, ACK)
            }

            if (flags and FIN != 0 && ((seq + payloadLength) and MASK) == connection.clientNext) {
                connection.clientNext = (connection.clientNext + 1) and MASK
                connection.clientClosed = true
                if (connection.inFlight == 0) close(key, connection, FIN or ACK)
                else sendSegment(connection, connection.serverNext, ACK)
            }
        }
    }

    /**
     * Lanza la resolución de cada consulta completa. Devuelve false si hay que cortar la conexión:
     * datos sin sentido o demasiadas consultas pendientes.
     */
    private fun dispatchQueries(key: String, connection: Connection): Boolean {
        val data = connection.pending.toByteArray()
        var offset = 0
        while (data.size - offset >= 2) {
            val length = data.u16(offset)
            if (length < DnsMessage.HEADER_SIZE) return false
            if (data.size - offset - 2 < length) break
            // Una app que envía demasiadas consultas a la vez por una conexión se corta.
            if (connection.inFlight >= MAX_IN_FLIGHT) return false
            val query = data.copyOfRange(offset + 2, offset + 2 + length)
            offset += 2 + length
            connection.inFlight++
            if (!executor.trySubmit { answer(key, connection, query) }) {
                connection.inFlight--
                return false
            }
        }
        connection.pending.reset()
        connection.pending.write(data, offset, data.size - offset)
        return true
    }

    private fun answer(key: String, connection: Connection, query: ByteArray) {
        val response = try {
            resolve(query)?.takeIf { it.size <= 0xFFFF }
        } catch (_: RuntimeException) {
            null // la conexión debe liberarse igualmente
        }
        synchronized(connections) {
            connection.inFlight--
            if (connections[key] !== connection) return
            if (response != null) {
                val framed = ByteArray(2 + response.size)
                framed.put16(0, response.size)
                System.arraycopy(response, 0, framed, 2, response.size)
                var offset = 0
                while (offset < framed.size) {
                    val length = minOf(maxSegment, framed.size - offset)
                    sendSegment(connection, connection.serverNext, ACK or PSH, framed, offset, length)
                    connection.serverNext = (connection.serverNext + length) and MASK
                    offset += length
                }
            }
            if (connection.clientClosed && connection.inFlight == 0) close(key, connection, FIN or ACK)
        }
    }

    private fun close(key: String, connection: Connection, flags: Int) {
        sendSegment(connection, connection.serverNext, flags)
        connections.remove(key)
    }

    private fun removeIdle() {
        val limit = SystemClock.elapsedRealtime() - IDLE_TIMEOUT_MS
        connections.values.removeAll { it.lastActivity < limit }
    }

    private fun sendSegment(
        connection: Connection,
        seq: Long,
        flags: Int,
        data: ByteArray? = null,
        offset: Int = 0,
        length: Int = 0,
    ) {
        val syn = connection.syn
        val segment = segment(syn.destinationPort, syn.sourcePort, seq, connection.clientNext, flags, data, offset, length)
        send(syn.reply(IpPacket.TCP, segment))
    }

    private fun reset(packet: IpPacket, flags: Int, seq: Long, ack: Long, payloadLength: Int) {
        val segment = if (flags and ACK != 0) {
            segment(packet.destinationPort, packet.sourcePort, ack, 0, RST)
        } else {
            var consumed = payloadLength.toLong()
            if (flags and SYN != 0) consumed++
            if (flags and FIN != 0) consumed++
            segment(packet.destinationPort, packet.sourcePort, 0, (seq + consumed) and MASK, RST or ACK)
        }
        send(packet.reply(IpPacket.TCP, segment))
    }

    private fun segment(
        sourcePort: Int,
        destinationPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        data: ByteArray? = null,
        offset: Int = 0,
        length: Int = 0,
    ) = ByteArray(20 + length).apply {
        put16(0, sourcePort)
        put16(2, destinationPort)
        put32(4, seq)
        put32(8, ack)
        this[12] = 0x50 // cabecera de 20 bytes, sin opciones
        this[13] = flags.toByte()
        put16(14, WINDOW)
        if (data != null) System.arraycopy(data, offset, this, 20, length)
    }

    private fun key(packet: IpPacket) =
        "${packet.source.toHex()}:${packet.sourcePort}>${packet.destination.toHex()}:${packet.destinationPort}"

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        const val DNS_PORT = 53
        const val MASK = 0xFFFFFFFFL
        const val WINDOW = 0xFFFF
        const val MAX_CONNECTIONS = 64
        const val MAX_IN_FLIGHT = 16
        const val IDLE_TIMEOUT_MS = 30_000L
    }
}
