package app.wayfinder

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class StickMotionTest {
    @Test fun physicalDirectionsAndDiagonalsAreIndependent() {
        val a = StickMotion.amounts(-32767, -32767, 32767, 32767)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f, 0f, 0f, 1f, 0f, 1f), a, 0f)
        assertArrayEquals(FloatArray(8), StickMotion.amounts(0, 0, 0, 0), 0f)
        assertEquals(1f, StickMotion.amounts(Int.MIN_VALUE, 0, 0, 0)[StickDirection.LL.ordinal], 0f)
    }

    @Test fun directionOrderMatchesNativeBitmask() {
        assertEquals(listOf("LU", "LD", "LL", "LR", "RU", "RD", "RL", "RR"),
            StickDirection.values().map { it.name })
        val allDirections: Int = StickDirection.values().fold(0) { mask, d -> mask or (1 shl d.ordinal) }
        assertEquals(255, allDirections)
    }

    @Test fun keyHysteresisPreventsThresholdChatterAndReleasesAtNeutral() {
        var held = false
        val samples = listOf(.20f, .44f, .45f, .44f, .35f, .301f, .30f, .35f, .5f, 0f)
        val expected = listOf(false, false, true, true, true, true, false, false, true, false)
        for ((amount, want) in samples.zip(expected)) {
            held = StickMotion.keyHeld(held, amount)
            assertEquals("deflection $amount", want, held)
        }
    }

    @Test fun cursorSpeedHasADeadzoneAndScalesContinuously() {
        assertEquals(0f, StickMotion.mouseAmount(0f), 0f)
        assertEquals(0f, StickMotion.mouseAmount(.18f), 0f)
        assertEquals(.5f, StickMotion.mouseAmount(.59f), .0001f)
        assertEquals(1f, StickMotion.mouseAmount(1f), 0f)
        assertEquals(1f, StickMotion.mouseAmount(2f), 0f)
    }

    @Test fun cursorDirectionsCancelAndDiagonalSpeedIsBounded() {
        assertEquals(0f to 0f, StickMotion.mouseVector(1f, 1f, 1f, 1f))
        assertEquals(0f to -1f, StickMotion.mouseVector(1f, 0f, 0f, 0f))
        assertEquals(-1f to 0f, StickMotion.mouseVector(0f, 0f, 1f, 0f))
        assertEquals(.25f to .5f, StickMotion.mouseVector(0f, .5f, 0f, .25f))
        val (x, y) = StickMotion.mouseVector(2f, 0f, 0f, 3f)
        assertTrue(x > 0f && y < 0f)
        assertEquals(1f, sqrt(x * x + y * y), .0001f)
    }
}
