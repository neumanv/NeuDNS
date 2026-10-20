package io.github.neumanv.neudns.dns

import android.os.SystemClock
import io.github.neumanv.neudns.net.put32
import io.github.neumanv.neudns.net.u32

/**
 * Caché de respuestas que respeta el TTL de cada registro.
 *
 * Al devolver una respuesta guardada se descuenta el tiempo que lleva en caché,
 * así la app nunca guarda un dato más tiempo del que permite el servidor.
 */
internal class DnsCache(private val maxEntries: Int = 512) {

    private class Entry(
        val response: ByteArray,
        val ttlOffsets: IntArray,
        val storedAt: Long,
        val expiresAt: Long,
    )

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > maxEntries
    }

    fun get(query: ByteArray, questionEnd: Int): ByteArray? {
        val key = key(query, questionEnd)
        val now = SystemClock.elapsedRealtime()
        val entry = synchronized(entries) {
            val found = entries[key] ?: return null
            if (now >= found.expiresAt) {
                entries.remove(key)
                return null
            }
            found
        }

        val elapsedSeconds = (now - entry.storedAt) / 1000
        return entry.response.copyOf().apply {
            for (offset in entry.ttlOffsets) put32(offset, maxOf(0L, u32(offset) - elapsedSeconds))
            // ID y pregunta (con sus mayúsculas) de quien pregunta ahora.
            System.arraycopy(query, 0, this, 0, 2)
            System.arraycopy(query, DnsMessage.HEADER_SIZE, this, DnsMessage.HEADER_SIZE, questionEnd - DnsMessage.HEADER_SIZE)
        }
    }

    fun put(query: ByteArray, questionEnd: Int, response: ByteArray) {
        val rcode = DnsMessage.rcode(response)
        if (rcode != DnsMessage.RCODE_NOERROR && rcode != DnsMessage.RCODE_NXDOMAIN) return
        if (DnsMessage.isTruncated(response) || !DnsMessage.sameQuestion(query, questionEnd, response)) return
        val offsets = DnsMessage.ttlOffsets(response) ?: return

        val ttl = offsets.minOfOrNull { response.u32(it) } ?: DEFAULT_TTL_SECONDS
        val seconds = minOf(ttl, MAX_TTL_SECONDS)
        if (seconds <= 0) return

        val now = SystemClock.elapsedRealtime()
        val entry = Entry(response, offsets, now, now + seconds * 1000)
        synchronized(entries) { entries[key(query, questionEnd)] = entry }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    /**
     * La clave es la pregunta (nombre sin distinguir mayúsculas, más tipo y clase) y el bit CD:
     * el ID cambia en cada consulta y no forma parte de ella.
     */
    private fun key(query: ByteArray, questionEnd: Int): String {
        val nameEnd = questionEnd - 4
        val chars = CharArray(questionEnd - DnsMessage.HEADER_SIZE + 1)
        for (i in DnsMessage.HEADER_SIZE until questionEnd) {
            val b = query[i]
            chars[i - DnsMessage.HEADER_SIZE] = if (i < nameEnd) DnsMessage.asciiLower(b) else (b.toInt() and 0xFF).toChar()
        }
        chars[chars.size - 1] = if (query[3].toInt() and 0x10 != 0) '1' else '0' // CD: sin validar DNSSEC
        return String(chars)
    }

    private companion object {
        /** Para respuestas sin registros (por ejemplo NXDOMAIN sin SOA). */
        const val DEFAULT_TTL_SECONDS = 60L
        const val MAX_TTL_SECONDS = 3600L
    }
}
