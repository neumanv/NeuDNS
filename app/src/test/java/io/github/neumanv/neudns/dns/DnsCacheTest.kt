package io.github.neumanv.neudns.dns

import io.github.neumanv.neudns.TestDns
import io.github.neumanv.neudns.net.u16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsCacheTest {

    @Test
    fun hitUsesIdAndCaseOfNewQuery() {
        val cache = DnsCache()
        val first = TestDns.query("Example.com", id = 1)
        cache.put(first, DnsMessage.questionEnd(first), TestDns.answer(first, 300))

        val second = TestDns.query("example.COM", id = 2)
        val hit = cache.get(second, DnsMessage.questionEnd(second))
        assertNotNull(hit)
        assertEquals(2, hit!!.u16(0))
        assertEquals("example.com", DnsMessage.questionName(hit))
        assertEquals(second.copyOfRange(12, 29).toList(), hit.copyOfRange(12, 29).toList())
    }

    @Test
    fun differentTypesAreDifferentEntries() {
        val cache = DnsCache()
        val a = TestDns.query("example.com", type = 1)
        cache.put(a, DnsMessage.questionEnd(a), TestDns.answer(a, 300))
        val aaaa = TestDns.query("example.com", type = 28)
        assertNull(cache.get(aaaa, DnsMessage.questionEnd(aaaa)))
    }

    @Test
    fun typeBytesAreNotCaseFolded() {
        // El tipo 65 (HTTPS) es la letra «A»: no debe confundirse con el tipo 97 («a»).
        val cache = DnsCache()
        val https = TestDns.query("example.com", type = 65)
        cache.put(https, DnsMessage.questionEnd(https), TestDns.answer(https, 300))
        val other = TestDns.query("example.com", type = 97)
        assertNull(cache.get(other, DnsMessage.questionEnd(other)))
    }

    @Test
    fun answerForAnotherQuestionIsNotStored() {
        val cache = DnsCache()
        val query = TestDns.query("bank.example")
        val end = DnsMessage.questionEnd(query)
        val forged = TestDns.answer(TestDns.query("evil.example"), 300)
        cache.put(query, end, forged)
        assertNull(cache.get(query, end))
    }

    @Test
    fun zeroTtlAndErrorsAreNotCached() {
        val cache = DnsCache()
        val query = TestDns.query("example.com")
        val end = DnsMessage.questionEnd(query)

        cache.put(query, end, TestDns.answer(query, 0))
        assertNull(cache.get(query, end))

        cache.put(query, end, DnsMessage.errorResponse(query, DnsMessage.RCODE_SERVFAIL)!!)
        assertNull(cache.get(query, end))
    }

    @Test
    fun nxdomainWithoutRecordsIsCached() {
        val cache = DnsCache()
        val query = TestDns.query("blocked.example")
        val end = DnsMessage.questionEnd(query)
        cache.put(query, end, DnsMessage.errorResponse(query, DnsMessage.RCODE_NXDOMAIN)!!)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(cache.get(query, end)!!))
    }

    @Test
    fun evictsOldestEntries() {
        val cache = DnsCache(maxEntries = 2)
        val names = listOf("a.com", "b.com", "c.com")
        for (name in names) {
            val query = TestDns.query(name)
            cache.put(query, DnsMessage.questionEnd(query), TestDns.answer(query, 300))
        }
        val oldest = TestDns.query("a.com")
        assertNull(cache.get(oldest, DnsMessage.questionEnd(oldest)))
        val newest = TestDns.query("c.com")
        assertNotNull(cache.get(newest, DnsMessage.questionEnd(newest)))
    }
}
