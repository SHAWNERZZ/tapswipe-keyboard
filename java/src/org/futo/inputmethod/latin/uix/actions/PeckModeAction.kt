package org.futo.inputmethod.latin.uix.actions

import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.TapSwipeModeSetting
import org.futo.inputmethod.latin.tapswipe.TapSwipePeckRequest
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.DataStoreHelper

/**
 * Puts the word being typed, or the next one, in peck mode: tapped letters are committed exactly as
 * typed, learned as a new word, and get no auto space. For spelling out a name or a word the
 * keyboard does not know. Press again to cancel. See [TapSwipePeckRequest].
 */
val PeckModeAction = Action(
    icon = R.drawable.type,
    name = R.string.action_peck_mode_title,
    simplePressImpl = { _, _ ->
        // Peck mode belongs to the TapSwipe input model; without it there is nothing to switch.
        if (DataStoreHelper.getSetting(TapSwipeModeSetting)) TapSwipePeckRequest.toggle()
    },
    windowImpl = null,
)
