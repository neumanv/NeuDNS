package io.github.neumanv.neudns.dns

import io.github.neumanv.neudns.net.put16
import io.github.neumanv.neudns.net.u16
import io.github.neumanv.neudns.net.u8

/**
 * Lo justo para entender un mensaje DNS (RFC 1035) sin decodificarlo entero:
 * dónde acaba la pregunta, dónde están los TTL y cómo fabricar respuestas cortas.
 */
internal object DnsMessage {
    const val HEADER_SIZE = 12
    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_NOTIMP = 4

    /** Sin EDNS0 una respuesta UDP no puede pasar de 512 bytes. */
    private const val CLASSIC_UDP_SIZE = 512
    private const val TYPE_OPT = 41

    fun isQuery(msg: ByteArray) = msg.size >= HEADER_SIZE && msg.u8(2) and 0x80 == 0

    /** Consulta normal (opcode QUERY) con una sola pregunta: lo único que se reenvía al servidor. */
    fun isStandardQuery(msg: ByteArray) = isQuery(msg) && msg.u8(2) and 0x78 == 0 && msg.u16(4) == 1

    fun rcode(msg: ByteArray) = msg.u8(3) and 0x0F

    /**
     * Comprueba que [response] contesta exactamente a la pregunta de [query] (sin distinguir
     * mayúsculas). Evita guardar en caché una respuesta que no corresponde a lo preguntado.
     */
    fun sameQuestion(query: ByteArray, questionEnd: Int, response: ByteArray): Boolean {
        if (query.u16(4) != 1 || response.u16(4) != 1 || questionEnd(response) != questionEnd) return false
        val nameEnd = questionEnd - 4
        for (i in HEADER_SIZE until nameEnd) {
            if (asciiLower(query[i]) != asciiLower(response[i])) return false
        }
        // Tipo y clase deben coincidir exactamente.
        for (i in nameEnd until questionEnd) {
            if (query[i] != response[i]) return false
        }
        return true
    }

    /** Minúscula ASCII de un byte de nombre DNS (los nombres no distinguen mayúsculas). */
    fun asciiLower(b: Byte): Char {
        val c = b.toInt() and 0xFF
        return (if (c in 'A'.code..'Z'.code) c + 32 else c).toChar()
    }

    fun isTruncated(msg: ByteArray) = msg.u8(2) and 0x02 != 0

    /** Posición donde termina la sección de preguntas, o -1 si el mensaje está mal formado. */
    fun questionEnd(msg: ByteArray): Int {
        if (msg.size < HEADER_SIZE) return -1
        var offset = HEADER_SIZE
        repeat(msg.u16(4)) {
            offset = skipName(msg, offset)
            if (offset < 0 || offset + 4 > msg.size) return -1
            offset += 4
        }
        return offset
    }

    /** Nombre de la primera pregunta en minúsculas, por ejemplo "ads.example.com". */
    fun questionName(msg: ByteArray): String? {
        if (msg.size <= HEADER_SIZE || msg.u16(4) == 0) return null
        val name = StringBuilder()
        var offset = HEADER_SIZE
        while (offset < msg.size) {
            val length = msg.u8(offset)
            if (length == 0) return name.toString()
            if (length and 0xC0 != 0 || offset + 1 + length > msg.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in offset + 1..offset + length) name.append(asciiLower(msg[i]))
            offset += length + 1
        }
        return null
    }

    /** Tamaño máximo de respuesta UDP que admite quien pregunta (lo anuncia con EDNS0). */
    fun maxUdpResponseSize(query: ByteArray): Int {
        var size = CLASSIC_UDP_SIZE
        forEachRecord(query) { type, offset ->
            if (type == TYPE_OPT) size = maxOf(CLASSIC_UDP_SIZE, query.u16(offset + 2))
        }
        return size
    }

    /** Posiciones de los TTL de todos los registros (menos OPT), o null si está mal formado. */
    fun ttlOffsets(msg: ByteArray): IntArray? {
        val offsets = ArrayList<Int>()
        val ok = forEachRecord(msg) { type, offset -> if (type != TYPE_OPT) offsets += offset + 4 }
        return if (ok) offsets.toIntArray() else null
    }

    /** Respuesta vacía con el código de error indicado (SERVFAIL, NXDOMAIN…). */
    fun errorResponse(query: ByteArray, rcode: Int): ByteArray? {
        val end = questionEnd(query)
        if (end < 0) return null
        return query.copyOf(end).apply {
            this[2] = ((query.u8(2) and 0x79) or 0x80).toByte() // QR=1, conserva opcode y RD
            this[3] = (0x80 or rcode).toByte() // RA=1
            clearRecordCounts()
        }
    }

    /** Solo cabecera y pregunta con el bit TC: le dice a la app que repita la consulta por TCP. */
    fun truncated(response: ByteArray): ByteArray? {
        val end = questionEnd(response)
        if (end < 0) return null
        return response.copyOf(end).apply {
            this[2] = (u8(2) or 0x02).toByte()
            clearRecordCounts()
        }
    }

    private fun ByteArray.clearRecordCounts() {
        put16(6, 0)
        put16(8, 0)
        put16(10, 0)
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var offset = start
        while (offset < msg.size) {
            val length = msg.u8(offset)
            when {
                length == 0 -> return offset + 1
                length and 0xC0 == 0xC0 -> return if (offset + 2 <= msg.size) offset + 2 else -1
                length and 0xC0 != 0 -> return -1
                else -> offset += length + 1
            }
        }
        return -1
    }

    /** Recorre respuestas, autoridad y adicionales. Devuelve false si el mensaje está mal formado. */
    private inline fun forEachRecord(msg: ByteArray, action: (type: Int, offset: Int) -> Unit): Boolean {
        var offset = questionEnd(msg)
        if (offset < 0) return false
        repeat(msg.u16(6) + msg.u16(8) + msg.u16(10)) {
            offset = skipName(msg, offset)
            if (offset < 0 || offset + 10 > msg.size) return false
            val dataLength = msg.u16(offset + 8)
            action(msg.u16(offset), offset)
            offset += 10 + dataLength
            if (offset > msg.size) return false
        }
        return true
    }
}
