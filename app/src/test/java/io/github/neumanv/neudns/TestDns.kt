package io.github.neumanv.neudns

import java.io.ByteArrayOutputStream

/** Fabrica mensajes DNS de prueba. */
object TestDns {

    fun query(name: String, id: Int = 0x1234, type: Int = 1, ednsSize: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write16(id)
        out.write16(0x0100) // RD
        out.write16(1) // una pregunta
        out.write16(0)
        out.write16(0)
        out.write16(if (ednsSize != null) 1 else 0)
        out.writeName(name)
        out.write16(type)
        out.write16(1) // IN
        if (ednsSize != null) {
            out.write(0) // nombre raíz
            out.write16(41) // OPT
            out.write16(ednsSize)
            out.write16(0)
            out.write16(0)
            out.write16(0)
        }
        return out.toByteArray()
    }

    /** Respuesta con un registro A por cada TTL indicado (sin la sección EDNS de la consulta). */
    fun answer(query: ByteArray, vararg ttls: Long): ByteArray {
        val questionEnd = questionEnd(query)
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write16(0x8180) // QR, RD, RA
        out.write16(1)
        out.write16(ttls.size)
        out.write16(0)
        out.write16(0)
        out.write(query, 12, questionEnd - 12)
        for (ttl in ttls) {
            out.write16(0xC00C) // puntero al nombre de la pregunta
            out.write16(1)
            out.write16(1)
            out.write16((ttl ushr 16).toInt())
            out.write16(ttl.toInt())
            out.write16(4)
            out.write(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        }
        return out.toByteArray()
    }

    private fun questionEnd(query: ByteArray): Int {
        var offset = 12
        while (query[offset].toInt() != 0) offset += query[offset] + 1
        return offset + 5
    }

    private fun ByteArrayOutputStream.write16(value: Int) {
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeName(name: String) {
        for (label in name.split('.')) {
            write(label.length)
            write(label.toByteArray())
        }
        write(0)
    }
}
