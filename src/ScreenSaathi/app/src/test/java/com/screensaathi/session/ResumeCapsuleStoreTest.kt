package com.screensaathi.session

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeCapsuleStoreTest {

    @Test
    fun `valid encrypted capsule loads for less than thirty minutes`() {
        val fixture = Fixture()
        fixture.store.save(resumableSession())
        val stored = fixture.values.value.orEmpty()
        fixture.elapsedNow += ResumeCapsuleStore.MAX_AGE_MS - 1
        fixture.wallNow += ResumeCapsuleStore.MAX_AGE_MS - 1

        val loaded = fixture.store.load()

        assertEquals("Заказать такси", loaded?.goal)
        assertEquals(2, loaded?.stepNumber)
        assertTrue(stored.isNotBlank())
        assertTrue(!stored.contains("Заказать такси"))
        assertEquals(0, fixture.values.deletes)
    }

    @Test
    fun `capsule expires at thirty minutes and is deleted`() {
        val fixture = Fixture()
        fixture.store.save(resumableSession())
        fixture.elapsedNow += ResumeCapsuleStore.MAX_AGE_MS
        fixture.wallNow += ResumeCapsuleStore.MAX_AGE_MS

        assertNull(fixture.store.load())
        assertNull(fixture.values.value)
        assertEquals(1, fixture.values.deletes)
    }

    @Test
    fun `a capsule offered before expiry is rejected when continue happens after expiry`() {
        val fixture = Fixture()
        fixture.store.save(resumableSession())
        fixture.elapsedNow += ResumeCapsuleStore.MAX_AGE_MS - 1
        fixture.wallNow += ResumeCapsuleStore.MAX_AGE_MS - 1
        val offered = fixture.store.load()
        fixture.elapsedNow += 2
        fixture.wallNow += 2

        val refreshedAtContinue = fixture.store.load()

        assertEquals("Заказать такси", offered?.goal)
        assertNull(refreshedAtContinue)
        assertNull(fixture.values.value)
        assertEquals(1, fixture.values.deletes)
    }

    @Test
    fun `elapsed realtime rollback detects reboot and deletes capsule`() {
        val fixture = Fixture(elapsedNow = 90_000)
        fixture.store.save(resumableSession())
        fixture.elapsedNow = 1_000

        assertNull(fixture.store.load())
        assertNull(fixture.values.value)
        assertEquals(1, fixture.values.deletes)
    }

    @Test
    fun `corrupt envelope json and failed authentication all delete stored data`() {
        val corruptEnvelope = Fixture().also { it.values.value = "not-an-envelope" }
        assertInvalidAndDeleted(corruptEnvelope)

        val corruptJson = Fixture().also {
            it.store.save(resumableSession())
            it.cipher.openOverride = "{broken".toByteArray(StandardCharsets.UTF_8)
        }
        assertInvalidAndDeleted(corruptJson)

        val failedAuthentication = Fixture().also {
            it.store.save(resumableSession())
            it.cipher.rejectOpen = true
        }
        assertInvalidAndDeleted(failedAuthentication)
    }

    @Test
    fun `storage read failure deletes the unreadable value`() {
        val fixture = Fixture()
        fixture.values.value = "wrong-shared-preferences-type"
        fixture.values.throwOnRead = true

        assertNull(fixture.store.load())
        assertNull(fixture.values.value)
        assertEquals(1, fixture.values.deletes)
    }

    @Test
    fun `failed encryption removes an older capsule instead of resuming stale state`() {
        val fixture = Fixture()
        fixture.store.save(resumableSession())
        fixture.cipher.rejectSeal = true

        fixture.store.save(resumableSession().copy(stepNumber = 3))

        assertNull(fixture.values.value)
        assertEquals(1, fixture.values.deletes)
    }

    @Test
    fun `unsafe logical phases cannot overwrite a resumable capsule`() {
        val fixture = Fixture()
        fixture.store.save(resumableSession())

        fixture.store.save(
            resumableSession().copy(
                phase = ScenarioPhase.ANALYZING_SCREEN,
                screenFingerprint = "must-not-persist",
            )
        )

        assertNull(fixture.values.value)
        assertEquals(1, fixture.values.deletes)
    }

    @Test
    fun `explicit delete is idempotent`() {
        val fixture = Fixture()
        fixture.store.save(resumableSession())

        fixture.store.delete()
        fixture.store.delete()

        assertNull(fixture.values.value)
        assertEquals(2, fixture.values.deletes)
    }

    @Test
    fun `stop and successful completion request persisted capsule deletion`() {
        val stopped = ScenarioReducer.reduce(
            resumableSession(),
            ScenarioEvent.StopRequested,
            nowMs = 5,
        )
        val completed = ScenarioReducer.reduce(
            resumableSession(),
            ScenarioEvent.GoalCompleted,
            nowMs = 5,
        )

        assertTrue(stopped.effects.contains(ScenarioEffect.DeleteResumeCapsule))
        assertTrue(completed.effects.contains(ScenarioEffect.DeleteResumeCapsule))
    }

    @Test
    fun `rephrase and restart delete the superseded capsule`() {
        val failed = resumableSession().copy(phase = ScenarioPhase.FAILED)

        val rephrased = ScenarioReducer.reduce(
            failed,
            ScenarioEvent.RephraseRequested,
            nowMs = 5,
        )
        val restarted = ScenarioReducer.reduce(
            failed,
            ScenarioEvent.RestartRequested,
            nowMs = 5,
        )

        assertTrue(rephrased.effects.contains(ScenarioEffect.DeleteResumeCapsule))
        assertTrue(restarted.effects.contains(ScenarioEffect.DeleteResumeCapsule))
    }

    private fun assertInvalidAndDeleted(fixture: Fixture) {
        assertNull(fixture.store.load())
        assertNull(fixture.values.value)
        assertEquals(1, fixture.values.deletes)
    }

    private class Fixture(
        var wallNow: Long = 1_700_000_000_000,
        var elapsedNow: Long = 50_000,
    ) {
        val values = MemoryValues()
        val cipher = FakeCipher()
        val store = DefaultResumeCapsuleStore(
            values = values,
            cipher = cipher,
            wallClock = { wallNow },
            elapsedClock = { elapsedNow },
        )
    }

    private class MemoryValues : ResumeCapsuleValues {
        var value: String? = null
        var deletes = 0
        var throwOnRead = false

        override fun read(): String? {
            if (throwOnRead) throw ClassCastException("wrong stored type")
            return value
        }

        override fun write(value: String) {
            this.value = value
        }

        override fun delete() {
            value = null
            deletes++
        }
    }

    private class FakeCipher : ResumeCapsuleCipher {
        var rejectSeal = false
        var rejectOpen = false
        var openOverride: ByteArray? = null

        override fun seal(plaintext: ByteArray): SealedResumeCapsule? {
            if (rejectSeal) return null
            return SealedResumeCapsule(
                iv = byteArrayOf(1, 2, 3),
                ciphertext = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
            )
        }

        override fun open(sealed: SealedResumeCapsule): ByteArray? {
            if (rejectOpen) return null
            openOverride?.let { return it }
            return sealed.ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private fun resumableSession() = ScenarioSession(
        goal = "Заказать такси",
        targetPackage = "com.example.taxi",
        mode = ScenarioMode.GUIDANCE,
        phase = ScenarioPhase.GUIDING_STEP,
        stepNumber = 2,
        lastSafeInstruction = "Нажмите пункт назначения",
        scrollAttempts = 0,
        replanFailures = 0,
        createdAtMs = 1,
        updatedAtMs = 2,
    )
}
