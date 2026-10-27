package com.screensaathi.sarvam

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioPlayerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `prepared obsolete speech never starts and releases its clip`() {
        val clip = temp.newFile()
        val backend = FakePlayback()
        val player = AudioPlayer(clip) { backend }
        var current = true
        player.play(byteArrayOf(1), {}, {}, withCurrent = { action -> if (current) action() })
        current = false // Same-generation screen invalidation during async prepare.
        backend.prepared()
        assertEquals(0, backend.starts)
        assertEquals(1, backend.releases)
        assertTrue(!clip.exists())
    }

    @Test
    fun `Stop from prepared callback cannot be followed by obsolete start`() {
        val backend = FakePlayback()
        val player = AudioPlayer(temp.newFile()) { backend }
        player.play(byteArrayOf(1), onStart = { player.stop() }, onDone = {})
        backend.prepared()
        assertEquals(listOf("start", "release"), backend.events)
    }

    @Test
    fun `Stop completing after lease check but before start rejects prepared ownership`() {
        val checked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val backend = FakePlayback()
        val player = AudioPlayer(temp.newFile()) { backend }
        var checks = 0
        player.play(byteArrayOf(1), {}, {}, withCurrent = { action ->
            checks++
            if (checks == 2) {
                checked.countDown()
                check(proceed.await(2, TimeUnit.SECONDS))
            }
            action()
        })
        val prepare = Thread { backend.prepared() }.apply { start() }
        try {
            assertTrue("prepared boundary must use the supplied lease", checked.await(1, TimeUnit.SECONDS))
            player.stop()
        } finally {
            proceed.countDown()
            prepare.join(2000)
        }
        assertEquals(0, backend.starts)
        assertEquals(1, backend.releases)
    }

    @Test
    fun `late completion from an old playback cannot release the replacement`() {
        val old = FakePlayback()
        val replacement = FakePlayback()
        val queue = ArrayDeque(listOf(old, replacement))
        val player = AudioPlayer(temp.newFile()) { queue.removeFirst() }
        player.play(byteArrayOf(1), {}, {})
        player.play(byteArrayOf(2), {}, {})
        old.completed()
        replacement.prepared()
        assertEquals(1, replacement.starts)
        assertEquals(0, replacement.releases)
        player.stop()
    }

    private class FakePlayback : AudioPlayback {
        var prepared: () -> Unit = {}
        var completed: () -> Unit = {}
        val events = mutableListOf<String>()
        val starts get() = events.count { it == "start" }
        val releases get() = events.count { it == "release" }
        override fun prepare(path: String, onPrepared: () -> Unit, onDone: () -> Unit) {
            prepared = onPrepared
            completed = onDone
        }
        override fun start() { events += "start" }
        override fun release() { events += "release" }
    }
}
