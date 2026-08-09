package org.futo.inputmethod.keyboard.internal

import android.content.Context
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.TapSwipeWpmSetting
import org.futo.inputmethod.latin.tapswipe.TapSwipeMode
import org.futo.inputmethod.latin.tapswipe.TapSwipeUiState
import org.futo.inputmethod.latin.uix.DataStoreHelper

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
 * position expresses priority. Each entry returns the text to show, or null for "nothing right
 * now", and receives a Context so it can resolve string resources or format a value.
 *
 * Keep labels short. The space bar is wide but not unlimited, and the caller falls back to the
 * language name if a label does not fit, which would make a status silently vanish on narrow
 * layouts or split keyboards rather than truncating visibly.
 */
object SpacebarStatus {

    /** One possible status. Returns null when it does not currently apply. */
    fun interface Status {
        fun label(context: Context): String?
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
        // Peck mode outranks the speed readout: it is a transient state that changes what typing
        // does, where speed is ambient and will still be there afterwards.
        Status { ctx ->
            if (TapSwipeUiState.mode == TapSwipeMode.PECK) {
                ctx.getString(R.string.spacebar_status_peck)
            } else null
        },

        Status { ctx ->
            if (!DataStoreHelper.getSetting(TapSwipeWpmSetting)) return@Status null
            WpmTracker.wpm()?.let { ctx.getString(R.string.spacebar_status_wpm, it) }
        },
    )

    /** @return text for the current status, or null if there is nothing to report. */
    @JvmStatic
    fun currentLabel(context: Context): String? {
        for (status in statuses) {
            val label = status.label(context)
            if (label != null) return label
        }
        return null
    }
}
