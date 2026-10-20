package io.github.neumanv.neudns.net

/**
 * Paquete IPv4 o IPv6 leído del túnel, con lo necesario para contestarlo.
 * Solo se aceptan paquetes UDP o TCP sin fragmentar ni cabeceras de extensión.
 */
internal class IpPacket private constructor(
    val version: Int,
    val source: ByteArray,
    val destination: ByteArray,
    val protocol: Int,
    /** Datos del paquete a partir de la cabecera UDP/TCP. */
    val segment: ByteArray,
) {
    val sourcePort get() = segment.u16(0)
    val destinationPort get() = segment.u16(2)

    /** Paquete de vuelta: intercambia origen y destino y calcula las sumas de verificación. */
    fun reply(protocol: Int, segment: ByteArray): ByteArray =
        build(version, destination, source, protocol, segment)

    companion object {
        const val TCP = 6
        const val UDP = 17

        private const val IPV4_HEADER = 20
        private const val IPV6_HEADER = 40

        fun parse(buffer: ByteArray, length: Int): IpPacket? {
            if (length < IPV4_HEADER) return null
            return when (buffer.u8(0) ushr 4) {
                4 -> parseV4(buffer, length)
                6 -> parseV6(buffer, length)
                else -> null
            }
        }

        private fun parseV4(b: ByteArray, length: Int): IpPacket? {
            val headerLength = (b.u8(0) and 0x0F) * 4
            val totalLength = minOf(b.u16(2), length)
            val fragmented = b.u16(6) and 0x3FFF != 0 // MF o desplazamiento distinto de 0
            if (headerLength < IPV4_HEADER || totalLength <= headerLength || fragmented) return null
            return create(4, b.copyOfRange(12, 16), b.copyOfRange(16, 20), b.u8(9), b.copyOfRange(headerLength, totalLength))
        }

        private fun parseV6(b: ByteArray, length: Int): IpPacket? {
            if (length < IPV6_HEADER) return null
            val end = minOf(IPV6_HEADER + b.u16(4), length)
            return create(6, b.copyOfRange(8, 24), b.copyOfRange(24, 40), b.u8(6), b.copyOfRange(IPV6_HEADER, end))
        }

        private fun create(version: Int, src: ByteArray, dst: ByteArray, protocol: Int, segment: ByteArray): IpPacket? {
            val minimum = when (protocol) {
                UDP -> 8
                TCP -> 20
                else -> return null
            }
            return if (segment.size >= minimum) IpPacket(version, src, dst, protocol, segment) else null
        }

        fun headerSize(version: Int) = if (version == 4) IPV4_HEADER else IPV6_HEADER

        fun build(version: Int, src: ByteArray, dst: ByteArray, protocol: Int, segment: ByteArray): ByteArray {
            val header = headerSize(version)
            val packet = ByteArray(header + segment.size)
            if (version == 4) {
                packet[0] = 0x45
                packet.put16(2, packet.size)
                packet.put16(6, 0x4000) // no fragmentar
                packet[8] = 64 // TTL
                packet[9] = protocol.toByte()
                System.arraycopy(src, 0, packet, 12, 4)
                System.arraycopy(dst, 0, packet, 16, 4)
                packet.put16(10, checksum(0, packet, 0, IPV4_HEADER))
            } else {
                packet[0] = 0x60
                packet.put16(4, segment.size)
                packet[6] = protocol.toByte()
                packet[7] = 64 // límite de saltos
                System.arraycopy(src, 0, packet, 8, 16)
                System.arraycopy(dst, 0, packet, 24, 16)
            }
            System.arraycopy(segment, 0, packet, header, segment.size)

            // Pseudo-cabecera: igual para IPv4 e IPv6 al sumarla en palabras de 16 bits.
            val pseudo = wordSum(src) + wordSum(dst) + protocol + segment.size
            var sum = checksum(pseudo, packet, header, segment.size)
            if (protocol == UDP && sum == 0) sum = 0xFFFF // en UDP, 0 significa "sin suma"
            packet.put16(header + if (protocol == TCP) 16 else 6, sum)
            return packet
        }

        private fun wordSum(b: ByteArray): Long {
            var sum = 0L
            for (i in b.indices step 2) sum += b.u16(i)
            return sum
        }

        private fun checksum(initial: Long, b: ByteArray, offset: Int, length: Int): Int {
            var sum = initial
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                sum += b.u16(i)
                i += 2
            }
            if (i < end) sum += b.u8(i) shl 8
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            return sum.inv().toInt() and 0xFFFF
        }
    }
}
