package io.github.neumanv.neudns.dns

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resuelve consultas DNS enviándolas cifradas por HTTPS (DNS-over-HTTPS, RFC 8484).
 *
 * Las conexiones HTTPS se reutilizan, así que la mayoría de consultas no abren conexión nueva.
 * Si el servidor no responde se devuelve SERVFAIL al momento, para que la app no se quede
 * esperando. Todo lo que llega del servidor se valida antes de usarlo o guardarlo en caché.
 */
class DohResolver(
    val url: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val endpoint = URL(url)
    private val cache = DnsCache()

    init {
        require(endpoint.protocol == "https") { "El servidor DNS debe usar HTTPS" }
    }

    /** Respuesta para la app, o null si lo recibido no es una consulta DNS válida. */
    fun resolve(query: ByteArray): ByteArray? {
        if (query.size > MAX_MESSAGE_SIZE || !DnsMessage.isQuery(query)) return null
        val questionEnd = DnsMessage.questionEnd(query)
        if (questionEnd < 0) return null

        // Solo se reenvían consultas normales de una pregunta; el resto no lo necesita ninguna app.
        if (!DnsMessage.isStandardQuery(query)) return DnsMessage.errorResponse(query, DnsMessage.RCODE_NOTIMP)

        val name = DnsMessage.questionName(query)
        if (name != null && DnsBypass.isEncryptedDnsDomain(name)) {
            return DnsMessage.errorResponse(query, DnsMessage.RCODE_NXDOMAIN)
        }

        cache.get(query, questionEnd)?.let { return it }

        val response = fetch(query)
        if (response == null || !DnsMessage.sameQuestion(query, questionEnd, response)) {
            return DnsMessage.errorResponse(query, DnsMessage.RCODE_SERVFAIL)
        }
        cache.put(query, questionEnd, response)
        return response.copyOf().apply {
            this[0] = query[0]
            this[1] = query[1]
        }
    }

    /** Olvida lo guardado, por ejemplo al cambiar de red. */
    fun clearCache() = cache.clear()

    private fun fetch(query: ByteArray): ByteArray? {
        // RFC 8484 recomienda ID 0 para que las respuestas se puedan cachear.
        val body = query.copyOf().apply {
            this[0] = 0
            this[1] = 0
        }
        val start = SystemClock.elapsedRealtime()
        post(body)?.let { return it }
        // Un fallo inmediato suele ser una conexión reutilizada que el servidor ya cerró:
        // se reintenta una vez. Un fallo por tiempo agotado no se repite.
        return if (SystemClock.elapsedRealtime() - start < RETRY_WINDOW_MS) post(body) else null
    }

    private fun post(body: ByteArray): ByteArray? {
        val connection = try {
            open(endpoint)
        } catch (_: IOException) {
            return null
        }
        return try {
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false // solo se acepta la respuesta del servidor elegido
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", MIME_TYPE)
                setRequestProperty("Accept", MIME_TYPE)
                setFixedLengthStreamingMode(body.size)
            }
            connection.outputStream.use { it.write(body) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.use { it.drain() } // vaciarlo permite reutilizar la conexión
                return null
            }
            val type = connection.contentType?.substringBefore(';')?.trim()
            val response = connection.inputStream.use { it.readLimited(MAX_MESSAGE_SIZE) }
            when {
                !MIME_TYPE.equals(type, ignoreCase = true) -> null
                response == null || response.size < DnsMessage.HEADER_SIZE || DnsMessage.isQuery(response) -> null
                else -> response
            }
        } catch (_: IOException) {
            connection.disconnect()
            null
        } catch (_: SecurityException) {
            connection.disconnect()
            null
        }
    }

    /** Lee como mucho [limit] bytes; null si el servidor envía más (un mensaje DNS no puede). */
    private fun InputStream.readLimited(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }

    private fun InputStream.drain() {
        val buffer = ByteArray(4096)
        var total = 0
        while (total < MAX_MESSAGE_SIZE) {
            val read = read(buffer)
            if (read < 0) return
            total += read
        }
    }

    private companion object {
        const val MIME_TYPE = "application/dns-message"
        const val TIMEOUT_MS = 4000
        const val RETRY_WINDOW_MS = 1000L

        /** Un mensaje DNS nunca pasa de 65 535 bytes. */
        const val MAX_MESSAGE_SIZE = 0xFFFF
    }
}
