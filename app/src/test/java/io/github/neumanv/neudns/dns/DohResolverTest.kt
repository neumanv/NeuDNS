package io.github.neumanv.neudns.dns

import io.github.neumanv.neudns.TestDns
import io.github.neumanv.neudns.net.put16
import io.github.neumanv.neudns.net.u16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
