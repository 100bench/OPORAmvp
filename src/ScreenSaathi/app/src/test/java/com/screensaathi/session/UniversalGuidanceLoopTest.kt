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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalGuidanceLoopTest {

    @Test
    fun `visible guidance is invalidated only by a different readable fingerprint`() {
        assertFalse(shouldClearGuidanceHighlight("screen-1", "screen-1"))
        assertFalse(shouldClearGuidanceHighlight("screen-1", ""))
        assertFalse(shouldClearGuidanceHighlight("", "screen-2"))
        assertTrue(shouldClearGuidanceHighlight("screen-1", "screen-2"))
    }

    @Test
    fun `supplied goal skips initial recognition but repeat requests a new capture`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))

        fixture.loop.startGoal("Заказать такси", ambiguous = true)

        assertTrue(fixture.effects().none { it is ScenarioEffect.BeginRecognition })
        fixture.loop.repeatRecognition()
        assertTrue(fixture.effects().last() is ScenarioEffect.BeginRecognition)
    }

    @Test
    fun `one shot answer renders immediately without goal confirmation`() {
        val fixture = Fixture(answer("История заказов находится в разделе «Профиль»."))

        fixture.loop.startGoal("Где история заказов?")

        assertEquals(ScenarioPhase.COMPLETED, fixture.loop.session.phase)
        assertTrue(fixture.effects().any { it is ScenarioEffect.ShowAnswer })
        assertTrue(fixture.effects().any { it is ScenarioEffect.SpeakText })
        assertFalse(fixture.effects().any { it is ScenarioEffect.ConfirmGoal })
        assertNull(fixture.emissions.singleOrNull { it.resolvedTarget != null })
    }

    @Test
    fun `dialog close control is highlighted locally without a planner call`() {
        val close = element(0, "close_button", "Закрыть", 900).copy(inDialog = true)
        val dialog = ScreenSnapshot("example.popup", true, listOf(close), activeDialog = true)
        val planner = RepeatingPlanner(answer("unused"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { dialog }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        val shown = emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(close.bounds.left, shown.resolvedTarget?.left)
        assertEquals(close.bounds.right, shown.resolvedTarget?.right)
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })
    }

    @Test
    fun `unsettled dialog waits for a fresh safe snapshot before highlighting`() {
        val close = element(0, "close_button", "Закрыть", 900).copy(inDialog = true)
        val current = AtomicReference(
            ScreenSnapshot("example.popup", false, listOf(close), activeDialog = true)
        )
        val planner = RepeatingPlanner(answer("unused"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(ScenarioPhase.ANALYZING_SCREEN, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RecheckModalAfterSettling })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })
        assertEquals(0, planner.calls)

        current.set(current.get().copy(settled = true))
        loop.recheckCurrentScreen()

        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.ShowGuidance })
        assertEquals(0, planner.calls)
    }

    @Test
    fun `dialog without close target waits then resumes same goal after dismissal`() {
        val dialog = ScreenSnapshot(
            "example.popup", true,
            listOf(element(0, "", "Рекламное окно", 100).copy(clickable = false, inDialog = true)),
            activeDialog = true,
        )
        val ordinary = screen("popup", "search", "Поиск", 100)
        val current = AtomicReference(dialog)
        val planner = RepeatingPlanner(guide("Нажмите «Поиск».", resourceId = "search"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        assertEquals("Найти товары", loop.session.goal)
        assertEquals(0, loop.session.scrollAttempts)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualModalDismiss })
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })

        current.set(ordinary)
        loop.observeScreen(ordinary)

        assertEquals(1, planner.calls)
        assertEquals("Найти товары", loop.session.goal)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        assertEquals(0, loop.session.scrollAttempts)
    }

    @Test
    fun `popup appearing mid guidance suspends old step and replans after close`() {
        val initial = screen("stable", "lavka", "Лавка", 100)
        val popup = ScreenSnapshot(
            "example.stable", true,
            listOf(element(0, "close_button", "Закрыть", 900).copy(inDialog = true)),
            activeDialog = true,
        )
        val next = screen("stable", "search", "Поиск", 100)
        val current = AtomicReference(initial)
        val planner = QueuePlanner(
            guide("Нажмите «Лавка».", resourceId = "lavka"),
            guide("Нажмите «Поиск».", resourceId = "search"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти товар в Лавке")
        loop.confirmGoal()
        assertEquals(1, loop.session.stepNumber)

        current.set(popup)
        loop.observeScreen(popup)
        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        assertEquals(1, planner.calls)
        assertEquals(1, loop.session.stepNumber)
        assertEquals("Найти товар в Лавке", loop.session.goal)
        assertTrue(emissions.last { it.effect is ScenarioEffect.ShowGuidance }.resolvedTarget != null)

        current.set(next)
        loop.observeScreen(next)
        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        assertEquals(2, loop.session.stepNumber)
        assertEquals("Найти товар в Лавке", loop.session.goal)
    }

    @Test
    fun `sensitive field in a dialog pauses before local close guidance`() {
        val dialog = ScreenSnapshot(
            "example.popup", true,
            listOf(
                element(0, "close_button", "Закрыть", 900).copy(inDialog = true),
                element(1, "password", "Пароль", 100, password = true).copy(inDialog = true),
            ),
            activeDialog = true,
        )
        val planner = RepeatingPlanner(answer("unused"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { dialog }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(ScenarioPhase.PAUSED_FOR_AUTH, loop.session.phase)
        assertEquals(0, planner.calls)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `first guide is cached until Start and resolves against its planning snapshot`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))

        fixture.loop.startGoal("Заказать такси")

        assertEquals(ScenarioPhase.CONFIRMING_GOAL, fixture.loop.session.phase)
        assertTrue(fixture.effects().any { it is ScenarioEffect.ConfirmGoal })
        assertFalse(fixture.effects().any { it is ScenarioEffect.ShowGuidance })

        fixture.loop.confirmGoal()

        val shown = fixture.emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(ScenarioPhase.GUIDING_STEP, fixture.loop.session.phase)
        assertEquals(screen("screen-1", "destination", "Куда", 10).signature(), shown.resolvedTarget?.fingerprint)
        assertEquals("destination", shown.resolvedTarget?.resourceId)
        assertEquals(1, fixture.planner.calls)
    }

    @Test
    fun `cached guide is rejected when same signature becomes unsettled or sensitive before Start`() {
        val safe = stableScreen()
        val mutations = listOf(
            safe.copy(settled = false) to ScenarioPhase.WAITING_FOR_MANUAL_SCROLL,
            safe.copy(
                elements = safe.elements + element(6, "password", "Пароль", 700, password = true),
            ) to ScenarioPhase.PAUSED_FOR_AUTH,
        )

        mutations.forEach { (mutated, expectedPhase) ->
            assertEquals(safe.signature(), mutated.signature())
            val current = AtomicReference(safe)
            val planner = RepeatingPlanner(guide("Нажмите «Куда».", resourceId = "destination"))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
            loop.startGoal("Заказать такси")
            current.set(mutated)

            loop.confirmGoal()

            assertEquals(expectedPhase, loop.session.phase)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
            assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
        }
    }

    @Test
    fun `screen change before Start discards cached guide and replans`() {
        val screen1 = screen("screen-1", "destination", "Куда", 10)
        val screen2 = screen("screen-2", "restaurant_search", "Ресторан", 200)
        val current = AtomicReference(screen1)
        val planner = QueuePlanner(
            guide("Нажмите «Куда».", resourceId = "destination"),
            guide("Нажмите «Ресторан».", resourceId = "restaurant_search"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Заказать бургер")
        current.set(screen2)
        loop.observeScreen(screen2)

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        loop.confirmGoal()
        val shown = emissions.last { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(screen2.signature(), shown.resolvedTarget?.fingerprint)
        assertEquals("restaurant_search", shown.resolvedTarget?.resourceId)
    }

    @Test
    fun `screen change while planner is in flight discards the stale response`() {
        val screen1 = screen("screen-1", "destination", "Куда", 10)
        val screen2 = screen("screen-2", "restaurant_search", "Ресторан", 200)
        val current = AtomicReference(screen1)
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                return if (calls == 1) {
                    current.set(screen2)
                    guide("Нажмите «Куда».", resourceId = "destination")
                } else {
                    guide("Нажмите «Ресторан».", resourceId = "restaurant_search")
                }
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Заказать бургер")

        assertEquals(2, calls)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        loop.confirmGoal()
        val shown = emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(screen2.signature(), shown.resolvedTarget?.fingerprint)
        assertEquals("restaurant_search", shown.resolvedTarget?.resourceId)
    }

    @Test
    fun `invented id gets one repair and an exact field label guides the nearby input`() {
        val label = element(0, "", "Госномер", 45).copy(
            clickable = false,
            bounds = testRect(45, 265, 413, 365),
        )
        val field = element(1, "", "", 45, editable = true).copy(
            bounds = testRect(45, 433, 742, 613),
        )
        val region = field.copy(index = 2, bounds = testRect(748, 433, 1035, 613))
        val raw = ScreenSnapshot("example.plate", true, listOf(label, field, region))
        val planner = QueuePlanner(
            guide("Нажмите поле госномера.", resourceId = "invented_plate_input"),
            guide("Нажмите поле под подписью «Госномер».", targetText = "Госномер"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Указать госномер")

        assertEquals(2, planner.calls)
        assertEquals(listOf(false, true), planner.repairFlags)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })

        loop.confirmGoal()
        val resolved = emissions.last { it.effect is ScenarioEffect.ShowGuidance }.resolvedTarget
        assertEquals(field.bounds.left, resolved?.left)
        assertEquals(field.bounds.top, resolved?.top)
        assertEquals(field.bounds.right, resolved?.right)
        assertEquals(field.bounds.bottom, resolved?.bottom)
    }

    @Test
    fun `two invented ids end in bounded manual recovery`() {
        val raw = screen("one", "visible", "Видимая кнопка", 10)
        val planner = QueuePlanner(
            guide("Нажмите кнопку.", resourceId = "invented_one"),
            guide("Нажмите кнопку.", resourceId = "invented_two"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualScroll })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `short mention cannot become a planner text target on VIN form`() {
        val raw = ScreenSnapshot("example.vin", true, listOf(
            element(0, "", "Госномер не указан", 45).copy(clickable = false),
            element(1, "", "", 45, editable = true),
        ))
        val planner = RepeatingPlanner(guide("Введите госномер.", targetText = "Госномер"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Указать госномер")

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `filled edit value cannot ground a planner text target`() {
        val raw = ScreenSnapshot("example.plate", true, listOf(
            element(0, "", "А777АА777", 45, editable = true),
        ))
        val planner = RepeatingPlanner(guide("Нажмите поле.", targetText = "А777АА777"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `nonactionable container id cannot ground a planner target`() {
        val raw = ScreenSnapshot("example.form", true, listOf(
            element(0, "form_container", "", 45).copy(clickable = false),
        ))
        val planner = RepeatingPlanner(guide("Нажмите форму.", resourceId = "form_container"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `same signature becoming unsettled or sensitive in flight rejects the stale response`() {
        val safe = stableScreen()
        val mutations = listOf(
            safe.copy(settled = false) to ScenarioPhase.WAITING_FOR_MANUAL_SCROLL,
            safe.copy(
                elements = safe.elements + element(6, "password", "Пароль", 700, password = true),
            ) to ScenarioPhase.PAUSED_FOR_AUTH,
        )

        mutations.forEach { (mutated, expectedPhase) ->
            assertEquals(safe.signature(), mutated.signature())
            val current = AtomicReference(safe)
            var calls = 0
            val planner = object : GuidancePlanner {
                override fun planNext(
                    screen: PlannerScreen,
                    goal: String,
                    stepNumber: Int,
                    lastSafeInstruction: String?,
                    repairFormat: Boolean,
                ): PlannerOutcome {
                    calls++
                    current.set(mutated)
                    return guide("Нажмите «Куда».", resourceId = "destination")
                }
            }
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

            loop.startGoal("Заказать такси")

            assertEquals(1, calls)
            assertEquals(expectedPhase, loop.session.phase)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        }
    }

    @Test
    fun `dialog appearing during planning invalidates the old target`() {
        val original = stableScreen()
        val current = AtomicReference(original)
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                if (calls == 1) {
                    current.set(original.copy(activeDialog = true))
                    return guide("Нажмите «Куда».", resourceId = "destination")
                }
                return PlannerOutcome.Failure(PlannerFailureKind.NETWORK, "test network pause")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Заказать такси")

        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `repeated in flight screen churn is bounded and asks for manual scroll`() {
        val screens = listOf(
            screen("screen-1", "one", "Один", 10),
            screen("screen-2", "two", "Два", 20),
            screen("screen-3", "three", "Три", 30),
        )
        val current = AtomicReference(screens[0])
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                if (calls < screens.size) current.set(screens[calls])
                return guide("Нажмите элемент.", resourceId = screen.elements.first().resourceId.orEmpty())
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(2, calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualScroll })
    }

    @Test
    fun `only an exact user selected app card produces a launch effect`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))

        fixture.loop.startGoal("Заказать такси", targetPackage = "ru.yandex.taxi")
        assertEquals(ScenarioPhase.WAITING_FOR_APP, fixture.loop.session.phase)
        assertEquals(0, fixture.planner.calls)

        fixture.loop.selectTargetApp("com.example.invented")
        assertTrue(fixture.effects().none { it is ScenarioEffect.LaunchTargetApp })

        fixture.loop.selectTargetApp("ru.yandex.taxi")
        assertEquals(
            listOf("ru.yandex.taxi"),
            fixture.effects().filterIsInstance<ScenarioEffect.LaunchTargetApp>().map { it.packageName },
        )
    }

    @Test
    fun `physical tap clears before changed screen replans and duplicate events do not advance twice`() {
        val screen1 = screen("screen-1", "destination", "Куда", 10)
        val screen2 = screen("screen-2", "restaurant", "Вкусно — и точка", 200)
        val current = AtomicReference(screen1)
        val planner = QueuePlanner(
            guide("Нажмите «Куда».", resourceId = "destination"),
            guide("Нажмите «Вкусно — и точка».", resourceId = "restaurant"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
        loop.startGoal("Заказать бургер")
        loop.confirmGoal()
        emissions.clear()

        loop.targetTapped()
        assertEquals(ScenarioPhase.WAITING_FOR_SCREEN_CHANGE, loop.session.phase)
        assertTrue(emissions.first().effect is ScenarioEffect.ClearHighlight)
        assertEquals(1, planner.calls)

        current.set(screen2)
        loop.observeScreen(screen2)
        loop.observeScreen(screen2)

        assertEquals(2, planner.calls)
        assertEquals(2, loop.session.stepNumber)
        assertEquals(1, emissions.count { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `physical tap on a settled same fingerprint screen analyzes again instead of hanging`() {
        val current = screen("screen-1", "destination", "Куда", 10)
        val planner = QueuePlanner(
            guide("Нажмите «Куда».", resourceId = "destination"),
            guide("Введите адрес вручную.", resourceId = "destination"),
        )
        val loop = UniversalGuidanceLoop(planner, { current }, clock(), emit = {})
        loop.startGoal("Заказать такси")
        loop.confirmGoal()

        loop.targetTapped()
        loop.physicalTapSettled(current)

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        assertEquals(2, loop.session.stepNumber)
    }

    @Test
    fun `physical tap timeout recovers locally without planning an unsettled tree`() {
        val current = AtomicReference(screen("screen-1", "destination", "Куда", 10))
        val planner = RepeatingPlanner(guide("Нажмите «Куда».", resourceId = "destination"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
        loop.startGoal("Заказать такси")
        loop.confirmGoal()
        loop.targetTapped()
        current.set(current.get().copy(settled = false))

        loop.physicalTapTimedOut()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualScroll })
    }

    @Test
    fun `unresolved target asks for manual scroll and only changed fingerprints count`() {
        val current = AtomicReference(screen("screen-1", "other", "Другое", 10))
        val planner = RepeatingPlanner(guide("Нажмите «Искомое».", resourceId = "missing"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти искомое")
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualScroll })

        loop.observeScreen(current.get())
        assertEquals(0, loop.session.scrollAttempts)

        repeat(3) { index ->
            val next = screen("screen-${index + 2}", "other-${index + 2}", "Другое", 20 + index)
            current.set(next)
            loop.observeScreen(next)
        }

        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertEquals(3, loop.session.scrollAttempts)
        val failure = emissions.map { it.effect }
            .filterIsInstance<ScenarioEffect.ShowRecoverableFailure>().last()
        assertEquals(ScenarioFailureReason.SCROLL_LIMIT, failure.reason)
        assertEquals(8, planner.calls)
    }

    @Test
    fun `sensitive and final screens short circuit before app routing sanitizer planner speech and highlight`() {
        listOf(
            screen("auth", "password", "Пароль", 10, password = true),
            screen("final", "pay", "Оплатить", 10),
        ).forEach { unsafe ->
            val current = AtomicReference(unsafe)
            val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = unsafe.elements[0].resourceId))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

            loop.startGoal(
                "Открыть Яндекс Go",
                targetPackage = "ru.yandex.taxi",
            )

            assertEquals(0, planner.calls)
            assertTrue(emissions.any { it.effect is ScenarioEffect.ClearHighlight })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowTargetAppChoice })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
            assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
            assertTrue(loop.session.phase in setOf(ScenarioPhase.PAUSED_FOR_AUTH, ScenarioPhase.SAFE_HANDOFF))
        }
    }

    @Test
    fun `bank app label blocks before the initial planner call`() {
        val raw = screen("bank-alias", "continue", "Продолжить", 10)
        val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = "continue"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { raw },
            clock = clock(),
            emit = emissions::add,
            appLabelProvider = { "Альфа-Банк" },
        )

        loop.startGoal("Продолжить")

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
    }

    @Test
    fun `bank app label blocks a cached guide at confirmation`() {
        val raw = screen("bank-alias", "continue", "Продолжить", 10)
        val appLabel = AtomicReference<String?>(null)
        val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = "continue"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { raw },
            clock = clock(),
            emit = emissions::add,
            appLabelProvider = { appLabel.get() },
        )
        loop.startGoal("Продолжить")
        appLabel.set("Альфа-Банк")

        loop.confirmGoal()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
    }

    @Test
    fun `bank app label blocks a provider result before it is accepted`() {
        val raw = screen("bank-alias", "continue", "Продолжить", 10)
        val appLabel = AtomicReference<String?>(null)
        val planner = object : GuidancePlanner {
            var calls = 0
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                appLabel.set("Альфа-Банк")
                return guide("Нажмите кнопку.", resourceId = "continue")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { raw },
            clock = clock(),
            emit = emissions::add,
            appLabelProvider = { appLabel.get() },
        )

        loop.startGoal("Продолжить")

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
    }

    @Test
    fun `empty or unsettled trees recover locally before app routing or planner`() {
        listOf(
            ScreenSnapshot("example.app", settled = true, elements = emptyList()),
            screen("loading", "destination", "Куда", 10).copy(settled = false),
            screen("hidden", "destination", "Куда", 10).copy(
                elements = listOf(
                    element(0, "destination", "Куда", 10, visibleToUser = false),
                ),
            ),
        ).forEach { unreadable ->
            val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = "destination"))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { unreadable }, clock(), emissions::add)

            loop.startGoal("Открыть Яндекс Go", targetPackage = "ru.yandex.taxi")

            assertEquals(0, planner.calls)
            assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowTargetAppChoice })
            assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
        }
    }

    @Test
    fun `failed repeated recognition restores repeat or stop decision`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))
        fixture.loop.startGoal("", ambiguous = true)
        fixture.loop.repeatRecognition()

        fixture.loop.recognitionFailed()

        val decision = fixture.effects().filterIsInstance<ScenarioEffect.ShowDecision>().last()
        assertEquals(
            listOf(ScenarioChoice.REPEAT_RECOGNITION, ScenarioChoice.STOP),
            decision.choices,
        )
        assertEquals(ScenarioPhase.RECOGNIZING_GOAL, fixture.loop.session.phase)
    }

    @Test
    fun `planner receives sanitized screen and target resolves only from the same raw snapshot`() {
        val raw = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = listOf(
                element(0, "phone", "+7 999 123-45-67", 10, editable = true, hint = "Телефон"),
                element(1, "continue", "Продолжить", 120),
            ),
        )
        val planner = InspectingPlanner(guide("Нажмите «Продолжить».", resourceId = "continue"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")
        loop.confirmGoal()

        assertFalse(planner.lastScreen!!.toPromptText().contains("+7 999 123-45-67"))
        assertTrue(planner.lastScreen!!.toPromptText().contains("Телефон"))
        val target = emissions.single { it.effect is ScenarioEffect.ShowGuidance }.resolvedTarget
        assertNotNull(target)
        assertEquals(raw.signature(), target?.fingerprint)
        assertEquals("continue", target?.resourceId)
    }

    @Test
    fun `planner cannot resolve an invented target onto a hidden raw node`() {
        val raw = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = listOf(
                element(0, "visible", "Видимая кнопка", 10),
                element(1, "hidden", "Скрытая кнопка", 120, visibleToUser = false),
            ),
        )
        val planner = RepeatingPlanner(guide("Нажмите скрытую кнопку.", resourceId = "hidden"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.resolvedTarget?.resourceId == "hidden" })
    }

    @Test
    fun `Stop invalidates a late planner callback`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                return guide("Нажмите «Куда».", resourceId = "destination")
            }
        }
        val emissions = java.util.Collections.synchronizedList(mutableListOf<UniversalGuidanceEmission>())
        val loop = UniversalGuidanceLoop(planner, { screen("screen-1", "destination", "Куда", 10) }, clock(), emissions::add)
        val worker = Thread { loop.startGoal("Заказать такси") }

        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        loop.stop()
        release.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(ScenarioPhase.IDLE, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.CancelPendingWork })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `invalid planner response retries once and network does not retry`() {
        val invalidThenValid = QueuePlanner(
            PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema"),
            guide("Нажмите «Куда».", resourceId = "destination"),
        )
        val loop = UniversalGuidanceLoop(
            invalidThenValid,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emit = {},
        )
        loop.startGoal("Заказать такси")
        assertEquals(2, invalidThenValid.calls)
        assertEquals(listOf(false, true), invalidThenValid.repairFlags)

        val network = RepeatingPlanner(PlannerOutcome.Failure(PlannerFailureKind.NETWORK, "offline"))
        val networkLoop = UniversalGuidanceLoop(
            network,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emit = {},
        )
        networkLoop.startGoal("Заказать такси")
        assertEquals(1, network.calls)
        assertEquals(ScenarioPhase.PAUSED_FOR_NETWORK, networkLoop.session.phase)
    }

    @Test
    fun `fake executable planner result is rejected even when marked done`() {
        val base = (guide("Нажать без разрешения.", resourceId = "destination") as PlannerOutcome.Success).plan
        val planner = RepeatingPlanner(
            PlannerOutcome.Success(base.copy(actionType = "click", isDone = true))
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emissions::add,
        )

        loop.startGoal("Продолжить")

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowAnswer })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    private class Fixture(outcome: PlannerOutcome) {
        val planner = RepeatingPlanner(outcome)
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emissions::add,
        )

        fun effects() = emissions.map { it.effect }
    }

    private open class RepeatingPlanner(private val outcome: PlannerOutcome) : GuidancePlanner {
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
            return outcome
        }
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

    private class InspectingPlanner(private val outcome: PlannerOutcome) : GuidancePlanner {
        var lastScreen: PlannerScreen? = null
        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            lastScreen = screen
            return outcome
        }
    }

    companion object {
        private fun testRect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

        private fun clock(): () -> Long {
            var now = 0L
            return { ++now }
        }

        private fun answer(text: String) = PlannerOutcome.Success(
            PlannerResult(
                version = 1,
                intent = "goal",
                step = "answer",
                targetResourceId = "",
                targetIndex = -1,
                instruction = text,
                confidence = 0.95,
                reason = "answer",
                language = "ru-RU",
                isDone = true,
                actionType = "answer",
            )
        )

        private fun guide(
            text: String,
            resourceId: String = "",
            targetText: String = "",
        ) = PlannerOutcome.Success(
            PlannerResult(
                version = 1,
                intent = "goal",
                step = "guide",
                targetResourceId = resourceId,
                targetIndex = 0,
                instruction = text,
                confidence = 0.95,
                reason = "next safe target",
                language = "ru-RU",
                targetText = targetText,
                actionType = "guide",
            )
        )

        private fun screen(
            suffix: String,
            resourceId: String,
            text: String,
            left: Int,
            password: Boolean = false,
        ) = ScreenSnapshot(
            packageName = "example.$suffix",
            settled = true,
            elements = listOf(element(0, resourceId, text, left, password = password)),
        )

        private fun element(
            index: Int,
            resourceId: String,
            text: String,
            left: Int,
            editable: Boolean = false,
            hint: String = "",
            password: Boolean = false,
            visibleToUser: Boolean = true,
        ) = ScreenElement(
            index = index,
            resourceId = resourceId,
            text = text,
            className = if (editable) "EditText" else "Button",
            bounds = Rect(left, 20, left + 100, 80),
            editable = editable,
            clickable = !editable,
            hintText = hint,
            password = password,
            visibleToUser = visibleToUser,
        )

        private fun stableScreen() = ScreenSnapshot(
            packageName = "example.stable",
            settled = true,
            elements = listOf(
                element(0, "destination", "Куда", 10),
                element(1, "one", "Элемент один", 120),
                element(2, "two", "Элемент два", 230),
                element(3, "three", "Элемент три", 340),
                element(4, "four", "Элемент четыре", 450),
                element(5, "five", "Элемент пять", 560),
            ),
        )
    }
}
