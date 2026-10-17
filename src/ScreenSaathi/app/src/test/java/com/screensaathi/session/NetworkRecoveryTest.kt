package com.screensaathi.session

import android.graphics.Rect
import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.PlannerFailureKind
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.screen.PlannerScreen
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryTest {

    @Test
    fun `stop racing registration always unregisters the eventual callback`() {
        val registerEntered = CountDownLatch(1)
        val allowRegister = CountDownLatch(1)
        val unregistered = CountDownLatch(1)
        val monitor = LifecycleNetworkMonitor(
            ValidatedNetworkRegistrar {
                registerEntered.countDown()
                assertTrue(allowRegister.await(2, TimeUnit.SECONDS))
                NetworkRegistration { unregistered.countDown() }
            }
        )
        val starter = Thread { monitor.start {} }
        val stopper = Thread { monitor.stop() }

        starter.start()
        assertTrue(registerEntered.await(2, TimeUnit.SECONDS))
        stopper.start()
        allowRegister.countDown()
        starter.join(2_000)
        stopper.join(2_000)

        assertTrue(unregistered.await(2, TimeUnit.SECONDS))
        assertTrue(!starter.isAlive)
        assertTrue(!stopper.isAlive)
    }

    @Test
    fun `callback from an old registration cannot reach a replacement listener`() {
        val callbacks = mutableListOf<() -> Unit>()
        val monitor = LifecycleNetworkMonitor(
            ValidatedNetworkRegistrar { callback ->
                callbacks += callback
                NetworkRegistration {}
            }
        )
        var firstDeliveries = 0
        var replacementDeliveries = 0

        monitor.start { firstDeliveries++ }
        val staleCallback = callbacks.single()
        monitor.stop()
        monitor.start { replacementDeliveries++ }
        staleCallback()

        assertEquals(0, firstDeliveries)
        assertEquals(0, replacementDeliveries)
    }

    @Test
    fun `network failure pauses with retry stop and starts observation`() {
        val fixture = Fixture(networkFailure())

        fixture.loop.startGoal("Найти профиль")

        assertEquals(ScenarioPhase.PAUSED_FOR_NETWORK, fixture.loop.session.phase)
        assertEquals(1, fixture.planner.calls)
        assertTrue(fixture.effects().contains(ScenarioEffect.StartNetworkMonitoring))
        val decision = fixture.effects().filterIsInstance<ScenarioEffect.ShowDecision>().last()
        assertEquals(ScenarioNotice.NO_NETWORK, decision.notice)
        assertEquals(listOf(ScenarioChoice.RETRY, ScenarioChoice.STOP), decision.choices)
    }

    @Test
    fun `manual retry preserves the invalid response budget across network pause`() {
        val fixture = Fixture(
            invalidResponse(),
            networkFailure(),
            invalidResponse(),
        )
        fixture.loop.startGoal("Найти профиль")
        assertEquals(ScenarioPhase.PAUSED_FOR_NETWORK, fixture.loop.session.phase)
        assertEquals(1, fixture.loop.session.replanFailures)

        fixture.loop.retry()

        assertEquals(3, fixture.planner.calls)
        assertEquals(listOf(false, true, false), fixture.planner.repairFlags)
        assertEquals(ScenarioPhase.FAILED, fixture.loop.session.phase)
        assertTrue(fixture.effects().contains(ScenarioEffect.StopNetworkMonitoring))
    }

    @Test
    fun `failed manual retry does not immediately rearm automatic recovery`() {
        val fixture = Fixture(networkFailure(), networkFailure())
        fixture.loop.startGoal("Найти профиль")

        fixture.loop.retry()

        assertEquals(2, fixture.planner.calls)
        assertEquals(ScenarioPhase.PAUSED_FOR_NETWORK, fixture.loop.session.phase)
        assertEquals(1, fixture.effects().count { it == ScenarioEffect.StartNetworkMonitoring })
        assertEquals(1, fixture.effects().count { it == ScenarioEffect.StopNetworkMonitoring })
    }

    @Test
    fun `automatic reconnect on the unchanged screen permits one retry`() {
        val fixture = Fixture(
            networkFailure(),
            guide("Откройте профиль", "profile"),
        )
        fixture.loop.startGoal("Найти профиль")

        fixture.loop.networkRestored()

        assertEquals(2, fixture.planner.calls)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, fixture.loop.session.phase)
        assertEquals(1, fixture.effects().count { it == ScenarioEffect.StopNetworkMonitoring })
    }

    @Test
    fun `changed screen on reconnect is safety checked before a fresh planner call`() {
        val current = AtomicReference(safeScreen())
        val planner = QueuePlanner(networkFailure(), guide("Нажмите оплатить", "pay"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { current.get() },
            clock = clock(),
            emit = emissions::add,
        )
        loop.startGoal("Продолжить")
        current.set(finalScreen())

        loop.networkRestored()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertTrue(emissions.any { it.effect == ScenarioEffect.StopNetworkMonitoring })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `repeated reconnect callbacks are idempotent`() {
        val fixture = Fixture(
            networkFailure(),
            guide("Откройте профиль", "profile"),
        )
        fixture.loop.startGoal("Найти профиль")

        fixture.loop.networkRestored()
        fixture.loop.networkRestored()

        assertEquals(2, fixture.planner.calls)
        assertEquals(1, fixture.effects().count { it == ScenarioEffect.StopNetworkMonitoring })
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, fixture.loop.session.phase)
    }

    @Test
    fun `one failed automatic reconnect does not rearm or accept another callback`() {
        val fixture = Fixture(
            networkFailure(),
            networkFailure(),
            guide("Откройте профиль", "profile"),
        )
        fixture.loop.startGoal("Найти профиль")

        fixture.loop.networkRestored()
        fixture.loop.networkRestored()

        assertEquals(2, fixture.planner.calls)
        assertEquals(ScenarioPhase.PAUSED_FOR_NETWORK, fixture.loop.session.phase)
        assertEquals(1, fixture.effects().count { it == ScenarioEffect.StartNetworkMonitoring })
        assertEquals(1, fixture.effects().count { it == ScenarioEffect.StopNetworkMonitoring })
    }

    @Test
    fun `screen race after reconnect cannot reset the invalid response budget`() {
        val first = safeScreen()
        val second = ScreenSnapshot(
            packageName = "example.changed",
            settled = true,
            elements = listOf(element("profile", "Профиль")),
        )
        val current = AtomicReference(first)
        val planner = QueuePlanner(
            invalidResponse(),
            networkFailure(),
            invalidResponse(),
            guide("Откройте профиль", "profile"),
        )
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { current.get() },
            clock = clock(),
            emit = {},
        )
        loop.startGoal("Найти профиль")
        assertEquals(1, loop.session.replanFailures)
        current.set(second)

        loop.networkRestored(first)

        assertEquals(3, planner.calls)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
    }

    @Test
    fun `Stop unregisters observation cancels work and ignores a late reconnect`() {
        val fixture = Fixture(networkFailure())
        fixture.loop.startGoal("Найти профиль")

        fixture.loop.stop()
        fixture.loop.networkRestored()

        assertEquals(1, fixture.planner.calls)
        assertEquals(ScenarioPhase.IDLE, fixture.loop.session.phase)
        assertTrue(fixture.effects().contains(ScenarioEffect.StopNetworkMonitoring))
        assertTrue(fixture.effects().contains(ScenarioEffect.CancelPendingWork))
    }

    private class Fixture(vararg outcomes: PlannerOutcome) {
        val planner = QueuePlanner(*outcomes)
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = ::safeScreen,
            clock = clock(),
            emit = emissions::add,
        )

        fun effects(): List<ScenarioEffect> = emissions.map(UniversalGuidanceEmission::effect)
    }

    private class QueuePlanner(vararg outcomes: PlannerOutcome) : GuidancePlanner {
        private val queue = ArrayDeque(outcomes.toList())
        var calls = 0
        val repairFlags = mutableListOf<Boolean>()

        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            calls++
            repairFlags += repairFormat
            return queue.removeFirst()
        }
    }

    companion object {
        private fun clock(): () -> Long {
            var now = 0L
            return { ++now }
        }

        private fun networkFailure() = PlannerOutcome.Failure(
            PlannerFailureKind.NETWORK,
            "offline",
        )

        private fun invalidResponse() = PlannerOutcome.Failure(
            PlannerFailureKind.INVALID_RESPONSE,
            "invalid",
        )

        private fun guide(instruction: String, resourceId: String) = PlannerOutcome.Success(
            PlannerResult(
                version = 1,
                intent = "goal",
                step = "guide",
                targetResourceId = resourceId,
                targetIndex = 0,
                instruction = instruction,
                confidence = 0.95,
                reason = "visible target",
                language = "ru-RU",
                actionType = "guide",
            ),
        )

        private fun safeScreen() = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = listOf(element("profile", "Профиль")),
        )

        private fun finalScreen() = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = listOf(element("pay", "Оплатить")),
        )

        private fun element(resourceId: String, text: String) = ScreenElement(
            index = 0,
            resourceId = resourceId,
            text = text,
            className = "Button",
            bounds = Rect(10, 20, 110, 80),
            editable = false,
            clickable = true,
            visibleToUser = true,
        )
    }
}
