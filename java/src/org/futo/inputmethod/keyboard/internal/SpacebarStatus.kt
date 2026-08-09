package org.futo.inputmethod.keyboard.internal

import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.tapswipe.TapSwipeMode
import org.futo.inputmethod.latin.tapswipe.TapSwipeUiState

/**
 * What the space bar should say right now, when there is something more useful to report than the
 * language name.
 *
 * The space bar is the largest piece of persistently visible, persistently wasted space on the
 * keyboard - it usually reads "English" to someone who has exactly one language installed and
 * already knows which one. That makes it the natural home for transient status, and this is the one
 * place that decides what goes there.
 *
 * ### Adding a status
 *
 * Add an entry to [statuses]. The list is ordered: the first entry with something to say wins, so
 * position expresses priority. Each entry returns a string resource id, or 0 for "nothing right
 * now" - a resource id rather than a String so labels stay translatable, since this object has no
 * Context to resolve them with.
 *
 * Keep labels short. The space bar is wide but not unlimited, and the caller falls back to the
 * language name if a label does not fit, which would make a status silently vanish on narrow
 * layouts or split keyboards rather than truncating visibly.
 */
object SpacebarStatus {

    /** One possible status. Returns a string resource id, or 0 when it does not currently apply. */
    fun interface Status {
        fun labelRes(): Int
    }

    /**
     * Ordered by priority - first with something to say wins.
     *
     * Kept as a plain list rather than a registration API on purpose: everything that could report
     * status here is part of the keyboard itself, so there is nothing to register at runtime, and a
     * static list means the priority order is readable in one place instead of depending on which
     * component initialised first.
     */
    private val statuses: List<Status> = listOf(
        // Peck mode. Worth surfacing because it changes what typing *does* - no autocorrect, the
        // word committed verbatim - and it can now engage on its own after an idle pause, so the
        // user may not have knowingly triggered it.
        Status {
            if (TapSwipeUiState.mode == TapSwipeMode.PECK) R.string.spacebar_status_peck else 0
        },
    )

    /** @return string resource id for the current status, or 0 if there is nothing to report. */
    @JvmStatic
    fun currentLabelRes(): Int {
        for (status in statuses) {
            val res = status.labelRes()
            if (res != 0) return res
        }
        return 0
    }
}
