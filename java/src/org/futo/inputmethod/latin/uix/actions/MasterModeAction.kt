package org.futo.inputmethod.latin.uix.actions

import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.TapSwipeMasterModeSetting
import org.futo.inputmethod.latin.TapSwipeModeSetting
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.setSetting

/**
 * Toggles TapSwipe Master Mode (letter keys as dots) straight from the action bar.
 *
 * Master Mode is the kind of thing you want to flip mid-sentence — showing a stranger the keyboard,
 * or hunting for a key you rarely use — and walking into settings for that is too slow. The setting
 * itself remains the source of truth; this just writes to it, so the two never disagree.
 */
val MasterModeAction = Action(
    icon = R.drawable.blur,
    name = R.string.action_master_mode_title,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        // No-op unless the input model is on; Master Mode has nothing to hide otherwise.
        if (DataStoreHelper.getSetting(TapSwipeModeSetting)) {
            val next = !DataStoreHelper.getSetting(TapSwipeMasterModeSetting)
            manager.getLifecycleScope().launch {
                context.setSetting(TapSwipeMasterModeSetting, next)
            }
        }
    },
    windowImpl = null,
)
