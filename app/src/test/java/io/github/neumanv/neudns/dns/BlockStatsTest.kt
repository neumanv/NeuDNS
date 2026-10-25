package io.github.neumanv.neudns.dns

import io.github.neumanv.neudns.dns.DohResolver.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockStatsTest {

    private var time = 1_000L
    private val stats = BlockStats(now = { time }, maxRecent = 3)

    @Test
    fun countsQueriesAndBlocks() {
        stats.record("a.com", Block.NONE)
        stats.record("ads.com", Block.SERVER)
        stats.record("mine.com", Block.USER)
        assertEquals(3, stats.queryCount)
        assertEquals(2, stats.blockedCount)
        stats.reset()
        assertEquals(0, stats.queryCount)
        assertEquals(0, stats.blockedCount)
    }

    @Test
    fun domainsAreOnlyNotedWhileRecording() {
        stats.record("ads.com", Block.SERVER)
        assertTrue(stats.recent().isEmpty())

        stats.startRecording(60_000)
        assertTrue(stats.isRecording)
        stats.record("a.com", Block.NONE) // lo que no se bloquea nunca se apunta
        stats.record("ads.com", Block.SERVER)
        stats.record("mine.com", Block.USER)
        stats.record("ads.com", Block.SERVER)
        assertEquals(
            listOf(BlockStats.Recent("ads.com", byUser = false, count = 2), BlockStats.Recent("mine.com", byUser = true, count = 1)),
            stats.recent(),
        )

        time += 60_000
        assertFalse(stats.isRecording)
        assertEquals(0, stats.recordingRemainingMs)
        stats.record("late.com", Block.SERVER)
        assertEquals(2, stats.recent().size) // terminado: no se apunta más, pero se puede consultar
    }

    @Test
    fun keepsOnlyTheLatestDomains() {
        stats.startRecording(60_000)
        for (name in listOf("1.com", "2.com", "3.com", "4.com")) stats.record(name, Block.SERVER)
        assertEquals(listOf("4.com", "3.com", "2.com"), stats.recent().map { it.domain })
    }

    @Test
    fun notesAreForgottenAfterAWhileOrWhenStopped() {
        stats.startRecording(60_000)
        stats.record("ads.com", Block.SERVER)
        time += 60_000 + 30 * 60_000
        assertTrue(stats.recent().isEmpty())

        stats.startRecording(60_000)
        stats.record("ads.com", Block.SERVER)
        stats.stopRecording()
        assertFalse(stats.isRecording)
        assertTrue(stats.recent().isEmpty())
    }
}
