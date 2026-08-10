package org.futo.inputmethod.v2keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for how the bottom row is reshaped by settings.
 *
 * The row is built once and then measured by two separate code paths that must agree, so a mistake
 * here does not show up as a wrong key - it shows up as a keyboard drawn against one row and sized
 * against another. Cheap to pin down here, expensive to notice on a device.
 */
class BottomRowConfigTest {

    private fun keys(config: BottomRowConfig) = bottomRowFor(config).bottom!!

    private fun hasComma(config: BottomRowConfig) = keys(config).any {
        it is ContextualKey && (it.fallbackKey as? BaseKey)?.spec == ","
    }

    private fun hasPeriod(config: BottomRowConfig) = keys(config).any { it is PeriodKey }

    /** The enter key, whether or not it has been wrapped in flicks. */
    private fun enterOf(config: BottomRowConfig): EnterKey {
        val last = keys(config).last()
        return when (last) {
            is EnterKey -> last
            is FlickKey -> last.primary as EnterKey
            else -> throw AssertionError("bottom row does not end with an enter key: $last")
        }
    }

    @Test
    fun `by default the row is unchanged`() {
        val config = BottomRowConfig.Default
        assertTrue(hasComma(config))
        assertTrue(hasPeriod(config))
        assertTrue(keys(config).last() is EnterKey)
        assertEquals(KeyWidth.FunctionalKey, enterOf(config).attributes.width)
    }

    @Test
    fun `hiding the comma removes only the comma`() {
        val config = BottomRowConfig(hideCommaKey = true)
        assertFalse(hasComma(config))
        assertTrue(hasPeriod(config))
    }

    /**
     * The point of the whole width token. The enter key has to actually claim the period's width,
     * or the spacebar silently absorbs it and the key that gained eight gestures stays small.
     */
    @Test
    fun `hiding the period widens the enter key`() {
        val config = BottomRowConfig(hidePeriodKey = true)
        assertFalse(hasPeriod(config))
        assertEquals(KeyWidth.WideFunctionalKey, enterOf(config).attributes.width)
    }

    @Test
    fun `keeping the period leaves the enter key its normal width`() {
        assertEquals(
            KeyWidth.FunctionalKey,
            enterOf(BottomRowConfig(hidePeriodKey = false)).attributes.width
        )
    }

    @Test
    fun `no flicks leaves an ordinary enter key`() {
        assertTrue(keys(BottomRowConfig(enterFlicks = emptyMap())).last() is EnterKey)
    }

    @Test
    fun `assigned directions become a flick key`() {
        val question = BaseKey("?")
        val bang = BaseKey("!")
        val flick = keys(
            BottomRowConfig(
                enterFlicks = mapOf(Direction.North to question, Direction.South to bang)
            )
        ).last() as FlickKey

        assertEquals(question, flick.up)
        assertEquals(bang, flick.down)
        // Unassigned directions must stay null rather than picking up a neighbour's key.
        assertNull(flick.left)
        assertNull(flick.right)
        assertNull(flick.upLeft)
        assertNull(flick.upRight)
        assertNull(flick.downLeft)
        assertNull(flick.downRight)
    }

    @Test
    fun `every direction can be assigned`() {
        val assignment = Direction.entries.associateWith { BaseKey("?") }
        val flick = keys(BottomRowConfig(enterFlicks = assignment)).last() as FlickKey

        listOf(
            flick.up, flick.down, flick.left, flick.right,
            flick.upLeft, flick.upRight, flick.downLeft, flick.downRight
        ).forEach { assertNotNull("a direction was dropped", it) }
    }

    /**
     * Flicks and the wider key are independent settings, and the width lives on the key nested
     * inside the flick wrapper - which is easy to set on the wrapper by mistake, where it would be
     * ignored without any error.
     */
    @Test
    fun `flicks and a hidden period combine`() {
        val config = BottomRowConfig(
            hidePeriodKey = true,
            enterFlicks = mapOf(Direction.North to BaseKey("?"))
        )
        assertTrue(keys(config).last() is FlickKey)
        assertEquals(KeyWidth.WideFunctionalKey, enterOf(config).attributes.width)
    }

    @Test
    fun `hiding both keys still leaves the rest of the row`() {
        val config = BottomRowConfig(hideCommaKey = true, hidePeriodKey = true)
        assertFalse(hasComma(config))
        assertFalse(hasPeriod(config))
        assertTrue(keys(config).any { it is SpaceKey })
        assertTrue(keys(config).any { it is SymbolsKey })
    }
}
