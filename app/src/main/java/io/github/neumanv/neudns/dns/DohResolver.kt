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
 * Si el servidor no responde se devuelve la última respuesta conocida, aunque haya caducado, o
 * SERVFAIL al momento, para que la app no se quede esperando. Todo lo que llega del servidor se
 * valida antes de usarlo o guardarlo en caché.
 *
 * Los dominios de [exceptions] (y sus subdominios) se preguntan a un servidor que no bloquea
 * nada ([unfilteredUrl]): así el usuario puede desbloquear lo que una app necesita. Los de
 * [blocked] se responden como inexistentes sin preguntar a nadie. Si un dominio está en las dos
 * listas a la vez (por ejemplo, se permite «ejemplo.com» y se bloquea «ads.ejemplo.com»), decide
 * la entrada más concreta.
 */
class DohResolver(
    val url: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    unfilteredUrl: String? = null,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val endpoint = URL(url)
    private val unfilteredEndpoint = unfilteredUrl?.let(::URL)
    private val cache = DnsCache(now = now)

    /** Dominios que no se bloquean. Al cambiarlos se olvida la caché, que podría tenerlos bloqueados. */
    @Volatile
    var exceptions: Set<String> = emptySet()
        set(value) {
            if (value == field) return
            field = value
            cache.clear()
        }

    /** Dominios que bloquea el usuario. Se comprueban antes de la caché, así que no hace falta vaciarla. */
    @Volatile
    var blocked: Set<String> = emptySet()

    /**
     * Hasta cuándo (según [now]) no se bloquea nada: todo se pregunta al servidor sin bloqueo.
     * Solo funciona si hay [unfilteredUrl].
     */
    @Volatile
    var pausedUntil: Long = 0

    val canPause: Boolean get() = unfilteredEndpoint != null

    /** Se llama con el nombre de cada consulta contestada y si se ha bloqueado, y quién lo ha hecho. */
    @Volatile
    var onAnswer: ((name: String, block: Block) -> Unit)? = null

    enum class Block { NONE, SERVER, USER }

    init {
        for (target in listOfNotNull(endpoint, unfilteredEndpoint)) {
            require(target.protocol == "https") { "El servidor DNS debe usar HTTPS" }
        }
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

        val unfiltered = unfilteredEndpoint
        if (unfiltered != null && now() < pausedUntil) {
            // En pausa no se usa la caché: puede tener respuestas bloqueadas, y las de ahora no deben quedarse.
            val response = fetch(unfiltered, query)?.takeIf { DnsMessage.sameQuestion(query, questionEnd, it) }
            report(name, Block.NONE)
            return response?.withIdOf(query) ?: DnsMessage.errorResponse(query, DnsMessage.RCODE_SERVFAIL)
        }

        val allowed = if (unfiltered != null && name != null) Domains.match(name, exceptions) else null
        val denied = name?.let { Domains.match(it, blocked) }
        if (denied != null && (allowed == null || denied.length > allowed.length)) {
            report(name, Block.USER)
            return DnsMessage.errorResponse(query, DnsMessage.RCODE_NXDOMAIN)
        }

        val response = lookup(unfiltered.takeIf { allowed != null } ?: endpoint, query, questionEnd)
        // Lo que contesta el servidor sin bloqueo nunca cuenta como bloqueado.
        val blockedByServer = allowed == null && response != null && DnsMessage.isBlocked(response)
        report(name, if (blockedByServer) Block.SERVER else Block.NONE)
        return response
    }

    private fun lookup(target: URL, query: ByteArray, questionEnd: Int): ByteArray? {
        cache.get(query, questionEnd)?.let { return it }
        val response = fetch(target, query)
        if (response == null || !DnsMessage.sameQuestion(query, questionEnd, response)) {
            return cache.getStale(query, questionEnd) ?: DnsMessage.errorResponse(query, DnsMessage.RCODE_SERVFAIL)
        }
        cache.put(query, questionEnd, response)
        return response.withIdOf(query)
    }

    private fun report(name: String?, block: Block) {
        if (name != null) onAnswer?.invoke(name, block)
    }

    private fun ByteArray.withIdOf(query: ByteArray) = copyOf().apply {
        this[0] = query[0]
        this[1] = query[1]
    }

    /** Olvida lo guardado, por ejemplo al cambiar de red. */
    fun clearCache() = cache.clear()

    private fun fetch(target: URL, query: ByteArray): ByteArray? {
        // RFC 8484 recomienda ID 0 para que las respuestas se puedan cachear.
        val body = query.copyOf().apply {
            this[0] = 0
            this[1] = 0
        }
        val start = now()
        post(target, body)?.let { return it }
        // Un fallo inmediato suele ser una conexión reutilizada que el servidor ya cerró:
        // se reintenta una vez. Un fallo por tiempo agotado no se repite.
        return if (now() - start < RETRY_WINDOW_MS) post(target, body) else null
    }

    private fun post(target: URL, body: ByteArray): ByteArray? {
        val connection = try {
            open(target)
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
