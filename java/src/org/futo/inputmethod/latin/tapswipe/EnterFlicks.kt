package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.v2keyboard.Direction

/**
 * What each direction off the enter key does.
 *
 * Nintype put punctuation on slides from the return key, which is a good trade on a phone: the key
 * is large, easy to hit without looking, and its normal job - submitting or breaking a line - is
 * something you do once per message rather than once per word. That leaves eight directions doing
 * nothing.
 *
 * Rather than fix the four Nintype happened to use, every direction is assignable, because which
 * punctuation is worth a gesture is genuinely personal - someone writing code wants brackets where
 * someone writing messages wants a question mark.
 *
 * ### Why ids rather than characters
 *
 * A slot stores an [EnterFlickOption] id, not the text it produces. Options are not all characters:
 * some are editor actions with no text at all, and a character option may later gain behaviour
 * beyond emitting itself. An id keeps stored settings meaningful across those changes, and keeps
 * this file free of anything that needs a device to resolve.
 */
object EnterFlicks {

    /** Directions in a stable order, so a stored string keeps its meaning. */
    val SLOT_ORDER: List<Direction> = listOf(
        Direction.North,
        Direction.South,
        Direction.West,
        Direction.East,
        Direction.NorthWest,
        Direction.NorthEast,
        Direction.SouthWest,
        Direction.SouthEast,
    )

    /**
     * Nintype's four, on the four cardinal directions.
     *
     * Up and down are its question mark and exclamation mark. Left and right are brackets, opening
     * on the left and closing on the right, which is not what Nintype did but is the only mapping
     * nobody has to memorise. The diagonals are deliberately empty: they are the hardest directions
     * to hit deliberately, so filling them by default would cost accuracy on the four that matter
     * to give away punctuation nobody asked for.
     */
    val DEFAULTS: Map<Direction, String> = mapOf(
        Direction.North to "question",
        Direction.South to "exclamation",
        Direction.West to "paren_open",
        Direction.East to "paren_close",
    )

    private const val SEPARATOR = ","

    /**
     * Encodes an assignment as one slot per [SLOT_ORDER] entry, empty for unassigned.
     *
     * Positional rather than named (`"N=question"`) so that adding a direction later cannot silently
     * reorder what is already stored - a shorter stored string is simply missing its tail, which
     * [parse] handles.
     */
    fun serialize(assignment: Map<Direction, String>): String =
        SLOT_ORDER.joinToString(SEPARATOR) { assignment[it] ?: "" }

    /**
     * Reads back a [serialize]d assignment, ignoring anything unrecognised.
     *
     * Tolerant on purpose. This string is settings data that outlives any particular build: it may
     * have been written when the option list was different, or truncated by a version that knew
     * about fewer directions. An unreadable slot means that direction does nothing, which is a
     * recoverable state the user can see and fix, where refusing the whole string would silently
     * reset every other direction they had set.
     *
     * @param known ids that currently exist; anything else is dropped
     */
    fun parse(stored: String?, known: Set<String>): Map<Direction, String> {
        if (stored.isNullOrEmpty()) return emptyMap()

        val slots = stored.split(SEPARATOR)
        val result = mutableMapOf<Direction, String>()
        for ((i, direction) in SLOT_ORDER.withIndex()) {
            val id = slots.getOrNull(i)?.trim() ?: continue
            if (id.isNotEmpty() && id in known) result[direction] = id
        }
        return result
    }
}
