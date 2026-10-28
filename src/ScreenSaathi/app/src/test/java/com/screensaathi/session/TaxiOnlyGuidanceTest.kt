package com.screensaathi.session

import android.graphics.Rect
import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.screen.PlannerScreen
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.ScreenViewport
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiOnlyGuidanceTest {

    @Test
    fun `authentication and final order controls are highlighted through the planner`() {
        val screens = listOf(
            snapshot(element(3, "password", "", editable = true, password = true, hint = "Пароль")),
            snapshot(element(9, "order", "Заказать")),
        )

        screens.forEach { screen ->
            val index = screen.elements.single().index
            val planner = FixedPlanner(guide(index = index, text = screen.elements.single().text))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { screen }, clock(), emissions::add)

            loop.startGoal("Заказать такси")

            assertEquals(1, planner.calls)
            assertTrue(emissions.any { it.effect is ScenarioEffect.ShowGuidance && it.resolvedTarget != null })
            assertFalse(emissions.any { it.effect is ScenarioEffect.ShowSafeHandoff })
            assertFalse(emissions.any { it.effect is ScenarioEffect.ShowDecision })
        }
    }

    @Test
    fun `first taxi guide renders immediately without app launch confirmation or speech`() {
        val screen = snapshot(element(5, "destination", "Куда поедете?", editable = true))
        val planner = FixedPlanner(guide(index = 5, text = "Куда поедете?"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { screen }, clock(), emissions::add)

        loop.startGoal("Заказать такси", targetPackage = "some.other.package")

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.ShowGuidance })
        assertFalse(emissions.any { it.effect is ScenarioEffect.ConfirmGoal })
        assertFalse(emissions.any { it.effect is ScenarioEffect.ShowTargetAppChoice })
        assertFalse(emissions.any { it.effect is ScenarioEffect.SpeakText })
    }

    @Test
    fun `model index breaks a semantic tie on the same planning snapshot`() {
        val first = element(1, "shared", "Продолжить", top = 100)
        val second = element(2, "shared", "Продолжить", top = 400)
        val screen = snapshot(first, second)
        val planner = FixedPlanner(
            guide(index = 2, resourceId = "shared", text = "Продолжить"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { screen }, clock(), emissions::add)

        loop.startGoal("Заказать такси")

        val target = emissions.single { it.effect is ScenarioEffect.ShowGuidance }.resolvedTarget
        assertEquals(second.bounds.top, target?.top)
        assertEquals(second.bounds.bottom, target?.bottom)
    }

    @Test
    fun `taxi label uses its containing card instead of a full screen ancestor`() {
        val fullScreenAncestor = element(2, "root", "", top = 0).copy(
            bounds = rect(0, 0, 1080, 2200),
        )
        val taxiLabel = element(3, "", "Такси", top = 300).copy(
            bounds = rect(666, 648, 901, 682),
            clickable = false,
            clickableAncestorIndex = fullScreenAncestor.index,
        )
        val taxiCard = element(4, "", "", top = 250).copy(
            bounds = rect(540, 463, 1028, 706),
        )
        val screen = snapshot(fullScreenAncestor, taxiLabel, taxiCard)
        val planner = FixedPlanner(guide(index = -1, text = "Такси"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { screen }, clock(), emissions::add)

        loop.startGoal("Заказать такси")

        val target = emissions.single { it.effect is ScenarioEffect.ShowGuidance }.resolvedTarget
        assertEquals(taxiCard.bounds.left, target?.left)
        assertEquals(taxiCard.bounds.top, target?.top)
        assertEquals(taxiCard.bounds.right, target?.right)
        assertEquals(taxiCard.bounds.bottom, target?.bottom)
    }

    private class FixedPlanner(private val outcome: PlannerOutcome) : GuidancePlanner {
        var calls = 0

        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            calls++
            return outcome
        }
    }

    private fun guide(
        index: Int,
        resourceId: String = "",
        text: String = "",
    ) = PlannerOutcome.Success(
        PlannerResult(
            version = 1,
            intent = "Заказать такси",
            step = "taxi_step",
            targetResourceId = resourceId,
            targetIndex = index,
            instruction = "Нажмите выделенный элемент.",
            confidence = 0.95,
            reason = "next taxi step",
            language = "ru-RU",
            targetText = text,
            actionType = "guide",
        ),
    )

    private fun snapshot(vararg elements: ScreenElement) = ScreenSnapshot(
        packageName = "ru.yandex.taxi",
        settled = true,
        elements = elements.toList(),
        viewport = ScreenViewport(0, 0, 1080, 2200),
    )

    private fun element(
        index: Int,
        resourceId: String,
        text: String,
        top: Int = 100,
        editable: Boolean = false,
        password: Boolean = false,
        hint: String = "",
    ) = ScreenElement(
        index = index,
        resourceId = resourceId,
        text = text,
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        bounds = Rect().apply {
            left = 40
            this.top = top
            right = 1000
            bottom = top + 180
        },
        editable = editable,
        clickable = true,
        hintText = hint,
        password = password,
    )

    private fun clock(): () -> Long {
        var now = 0L
        return { ++now }
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
