package io.github.neumanv.neudns.dns

import io.github.neumanv.neudns.TestDns
import io.github.neumanv.neudns.net.put16
import io.github.neumanv.neudns.net.u16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Comprueba que nada de lo que envía el servidor se usa sin validar. */
class DohResolverTest {

    /** Servidor falso: responde lo que diga [reply] y cuenta las peticiones. */
    private class FakeServer(val reply: (ByteArray) -> Triple<Int, String?, ByteArray>) {
        var requests = 0
        var followedRedirects: Boolean? = null

        fun open(url: URL): HttpURLConnection = object : HttpURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private val response by lazy {
                requests++
                followedRedirects = instanceFollowRedirects
                reply(body.toByteArray())
            }

            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode() = response.first
            override fun getContentType() = response.second
            override fun getInputStream(): InputStream = ByteArrayInputStream(response.third)
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
        }
    }

    private fun ok(body: ByteArray) = Triple(200, "application/dns-message", body)

    @Test
    fun validAnswerIsReturnedWithOriginalIdAndCached() {
        val server = FakeServer { request ->
            assertEquals(0, request.u16(0)) // RFC 8484: ID 0 hacia el servidor
            ok(TestDns.answer(request, 300))
        }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        val first = resolver.resolve(TestDns.query("example.com", id = 0x1111))!!
        assertEquals(0x1111, first.u16(0))
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(first))
        assertEquals(false, server.followedRedirects)

        val second = resolver.resolve(TestDns.query("example.com", id = 0x2222))!!
        assertEquals(0x2222, second.u16(0))
        assertEquals(1, server.requests)
    }

    @Test
    fun answerForAnotherNameIsRejectedAndNotCached() {
        val server = FakeServer { ok(TestDns.answer(TestDns.query("evil.example"), 300)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        val response = resolver.resolve(TestDns.query("bank.example"))!!
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(response))
        resolver.resolve(TestDns.query("bank.example"))
        assertEquals(2, server.requests) // la segunda consulta vuelve al servidor: nada en caché
    }

    @Test
    fun answerForAnotherTypeIsRejected() {
        val server = FakeServer { request ->
            ok(TestDns.answer(request, 300).also { it.put16(DnsMessage.questionEnd(request) - 4, 28) })
        }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(resolver.resolve(TestDns.query("example.com"))!!))
    }

    @Test
    fun oversizedBodyIsRejected() {
        val server = FakeServer { request -> ok(TestDns.answer(request, 300) + ByteArray(70_000)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(resolver.resolve(TestDns.query("example.com"))!!))
    }

    @Test
    fun wrongContentTypeIsRejected() {
        val server = FakeServer { request -> Triple(200, "text/html", TestDns.answer(request, 300)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(resolver.resolve(TestDns.query("example.com"))!!))
    }

    @Test
    fun httpErrorGivesServfail() {
        val server = FakeServer { Triple(500, null, ByteArray(0)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(resolver.resolve(TestDns.query("example.com"))!!))
    }

    @Test
    fun nonStandardQueriesAreNotForwarded() {
        val server = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)

        val update = TestDns.query("example.com").also { it[2] = (5 shl 3).toByte() } // opcode UPDATE
        assertEquals(DnsMessage.RCODE_NOTIMP, DnsMessage.rcode(resolver.resolve(update)!!))

        val response = TestDns.answer(TestDns.query("example.com"), 300)
        assertNull(resolver.resolve(response)) // una respuesta no es una consulta

        assertEquals(0, server.requests)
    }

    @Test
    fun encryptedDnsDomainsAreAnsweredLocally() {
        val server = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        val response = resolver.resolve(TestDns.query("dns.google"))!!
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(response))
        assertEquals(0, server.requests)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plainHttpServerIsRefused() {
        DohResolver("http://dns.test/dns-query")
    }

    @Test
    fun sameQuestionComparesTypeExactly() {
        val query = TestDns.query("example.com", type = 65) // HTTPS
        val other = TestDns.query("example.com", type = 97)
        assertFalse(DnsMessage.sameQuestion(query, DnsMessage.questionEnd(query), other))
    }

    @Test
    fun lastKnownAnswerIsUsedWhenServerFails() {
        var time = 0L
        var up = true
        val server = FakeServer { request -> if (up) ok(TestDns.answer(request, 60)) else Triple(500, null, ByteArray(0)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open, now = { time })
        resolver.resolve(TestDns.query("example.com"))

        up = false
        time = 120_000
        val stale = resolver.resolve(TestDns.query("example.com", id = 0x3333))!!
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(stale))
        assertEquals(0x3333, stale.u16(0))

        // Sin nada guardado no hay reserva posible.
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(resolver.resolve(TestDns.query("other.com"))!!))
    }

    @Test
    fun exceptionsGoToUnfilteredServer() {
        val filtered = FakeServer { request -> ok(DnsMessage.errorResponse(request, DnsMessage.RCODE_NXDOMAIN)!!) }
        val unfiltered = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val open = { url: URL -> if (url.host == "unfiltered.test") unfiltered.open(url) else filtered.open(url) }
        val resolver = DohResolver("https://dns.test/dns-query", open, "https://unfiltered.test/dns-query")

        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(resolver.resolve(TestDns.query("ads.example.com"))!!))
        resolver.exceptions = setOf("example.com")
        // Lo que estaba en caché como bloqueado se olvida al cambiar las excepciones.
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resolver.resolve(TestDns.query("ads.example.com"))!!))
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(resolver.resolve(TestDns.query("ads.other.com"))!!))
        assertEquals(2, filtered.requests)
        assertEquals(1, unfiltered.requests)
    }

    @Test
    fun exceptionsCannotReenableEncryptedDns() {
        val server = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val resolver = DohResolver("https://dns.test/dns-query", server::open, "https://unfiltered.test/dns-query")
        resolver.exceptions = setOf("dns.google")
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(resolver.resolve(TestDns.query("dns.google"))!!))
        assertEquals(0, server.requests)
    }

    /** Servidor que bloquea lo que acaba en «ads.test» respondiendo 0.0.0.0, como AdGuard. */
    private fun blockingServer() = FakeServer { request ->
        val answer = TestDns.answer(request, 300)
        if (DnsMessage.questionName(request)!!.endsWith("ads.test")) answer.fill(0, answer.size - 4)
        ok(answer)
    }

    /** Resolutor con servidor con bloqueo y sin bloqueo que apunta lo que avisa en [answers]. */
    private fun resolverWith(
        filtered: FakeServer,
        unfiltered: FakeServer,
        answers: MutableList<Pair<String, DohResolver.Block>>,
        now: () -> Long = { 0 },
    ): DohResolver {
        val open = { url: URL -> if (url.host == "unfiltered.test") unfiltered.open(url) else filtered.open(url) }
        return DohResolver("https://dns.test/dns-query", open, "https://unfiltered.test/dns-query", now).apply {
            onAnswer = { name, block -> answers += name to block }
        }
    }

    @Test
    fun userBlockedDomainsAreAnsweredLocally() {
        val server = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val answers = mutableListOf<Pair<String, DohResolver.Block>>()
        val resolver = resolverWith(server, server, answers)
        resolver.blocked = setOf("tracker.example.com")

        val response = resolver.resolve(TestDns.query("eu.tracker.example.com", id = 0x4444))!!
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(response))
        assertEquals(0x4444, response.u16(0))
        assertEquals(0, server.requests)
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resolver.resolve(TestDns.query("example.com"))!!))
        assertEquals(listOf("eu.tracker.example.com" to DohResolver.Block.USER, "example.com" to DohResolver.Block.NONE), answers)
    }

    @Test
    fun mostSpecificListEntryWins() {
        val filtered = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val unfiltered = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val resolver = resolverWith(filtered, unfiltered, mutableListOf())

        resolver.exceptions = setOf("example.com")
        resolver.blocked = setOf("ads.example.com")
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(resolver.resolve(TestDns.query("x.ads.example.com"))!!))
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resolver.resolve(TestDns.query("www.example.com"))!!))
        assertEquals(1, unfiltered.requests)

        resolver.exceptions = setOf("cdn.example.org")
        resolver.blocked = setOf("example.org")
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resolver.resolve(TestDns.query("img.cdn.example.org"))!!))
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(resolver.resolve(TestDns.query("www.example.org"))!!))
        assertEquals(2, unfiltered.requests)
        assertEquals(0, filtered.requests)
    }

    @Test
    fun serverBlocksAreReportedAlsoFromCache() {
        val answers = mutableListOf<Pair<String, DohResolver.Block>>()
        val filtered = blockingServer()
        val resolver = resolverWith(filtered, blockingServer(), answers)
        resolver.resolve(TestDns.query("x.ads.test"))
        resolver.resolve(TestDns.query("x.ads.test"))
        resolver.resolve(TestDns.query("example.com"))
        assertEquals(2, filtered.requests) // la segunda consulta de x.ads.test sale de la caché
        assertEquals(
            listOf("x.ads.test" to DohResolver.Block.SERVER, "x.ads.test" to DohResolver.Block.SERVER, "example.com" to DohResolver.Block.NONE),
            answers,
        )
    }

    @Test
    fun pauseSendsEverythingToUnfilteredServerWithoutCache() {
        var time = 0L
        val answers = mutableListOf<Pair<String, DohResolver.Block>>()
        val filtered = blockingServer()
        val unfiltered = FakeServer { request -> ok(TestDns.answer(request, 300)) }
        val resolver = resolverWith(filtered, unfiltered, answers, now = { time })
        resolver.blocked = setOf("mine.test")
        resolver.resolve(TestDns.query("x.ads.test")) // queda en caché como bloqueado

        resolver.pausedUntil = 60_000
        assertTrue(resolver.canPause)
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resolver.resolve(TestDns.query("x.ads.test"))!!))
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resolver.resolve(TestDns.query("mine.test"))!!))
        resolver.resolve(TestDns.query("x.ads.test"))
        assertEquals(3, unfiltered.requests) // en pausa no se usa la caché
        assertEquals(DohResolver.Block.NONE, answers.last().second)

        time = 60_000 // la pausa termina sola
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(resolver.resolve(TestDns.query("mine.test"))!!))
        val blocked = resolver.resolve(TestDns.query("x.ads.test"))!!
        assertTrue(DnsMessage.isBlocked(blocked))
        assertEquals(1, filtered.requests) // y vuelve la respuesta bloqueada de antes de la pausa
    }

    @Test
    fun withoutUnfilteredServerPauseAndExceptionsDoNothing() {
        val server = blockingServer()
        val resolver = DohResolver("https://dns.test/dns-query", server::open)
        assertFalse(resolver.canPause)
        resolver.pausedUntil = Long.MAX_VALUE
        resolver.exceptions = setOf("ads.test")
        assertTrue(DnsMessage.isBlocked(resolver.resolve(TestDns.query("x.ads.test"))!!))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plainHttpUnfilteredServerIsRefused() {
        DohResolver("https://dns.test/dns-query", unfilteredUrl = "http://unfiltered.test/dns-query")
    }
}
