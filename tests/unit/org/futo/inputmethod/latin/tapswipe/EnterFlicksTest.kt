package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.v2keyboard.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the enter-key flick assignment.
 *
 * This is settings data that outlives the build that wrote it, so most of what matters here is how
 * it copes with strings it did not write: shortened, lengthened, or naming options that no longer
 * exist. Losing one direction is recoverable; silently resetting all eight is not.
 */
class EnterFlicksTest {

    private val known = setOf("question", "exclamation", "paren_open", "paren_close", "paste")

    @Test
    fun `an assignment survives a round trip`() {
        val assignment = mapOf(
            Direction.North to "question",
            Direction.SouthEast to "paste"
        )
        assertEquals(assignment, EnterFlicks.parse(EnterFlicks.serialize(assignment), known))
    }

    @Test
    fun `the defaults survive a round trip`() {
        val ids = EnterFlicks.DEFAULTS.values.toSet()
        assertEquals(
            EnterFlicks.DEFAULTS,
            EnterFlicks.parse(EnterFlicks.serialize(EnterFlicks.DEFAULTS), ids)
        )
    }

    @Test
    fun `nothing assigned is not the same as nothing stored`() {
        assertEquals(emptyMap<Direction, String>(), EnterFlicks.parse(null, known))
        assertEquals(emptyMap<Direction, String>(), EnterFlicks.parse("", known))
        // A fully-empty assignment still serialises to slot separators, and must read back empty
        // rather than as an error.
        assertEquals(emptyMap<Direction, String>(), EnterFlicks.parse(EnterFlicks.serialize(emptyMap()), known))
    }

    /**
     * The reason slots are positional. A build that knew about fewer directions writes a shorter
     * string; the directions it did know must still come back.
     */
    @Test
    fun `a string from a build with fewer directions keeps what it did store`() {
        val truncated = "question,exclamation"
        val parsed = EnterFlicks.parse(truncated, known)
        assertEquals("question", parsed[Direction.North])
        assertEquals("exclamation", parsed[Direction.South])
        assertEquals(2, parsed.size)
    }

    @Test
    fun `extra slots from a later build are ignored`() {
        val tooMany = EnterFlicks.serialize(mapOf(Direction.North to "question")) + ",future,options"
        assertEquals(mapOf(Direction.North to "question"), EnterFlicks.parse(tooMany, known))
    }

    /**
     * An option that no longer exists must cost only its own direction. Rejecting the whole string
     * would wipe assignments the user can still use.
     */
    @Test
    fun `an unknown option drops only its own slot`() {
        val stored = EnterFlicks.serialize(mapOf(
            Direction.North to "question",
            Direction.South to "removed_in_a_later_build"
        ))
        val parsed = EnterFlicks.parse(stored, known)
        assertEquals(mapOf(Direction.North to "question"), parsed)
    }

    @Test
    fun `every direction has exactly one slot`() {
        assertEquals(Direction.entries.size, EnterFlicks.SLOT_ORDER.size)
        assertEquals(Direction.entries.toSet(), EnterFlicks.SLOT_ORDER.toSet())
    }

    /** Defaults are only useful if they name options that exist; a typo would silently do nothing. */
    @Test
    fun `defaults only name cardinal directions`() {
        val cardinals = setOf(Direction.North, Direction.South, Direction.East, Direction.West)
        assertTrue(EnterFlicks.DEFAULTS.keys.all { it in cardinals })
    }
}
