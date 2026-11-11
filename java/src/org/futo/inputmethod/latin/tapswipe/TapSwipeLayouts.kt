package org.futo.inputmethod.latin.tapswipe

import android.content.Context
import kotlinx.coroutines.flow.first
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.TapSwipeApostropheKeyMigratedSetting
import org.futo.inputmethod.latin.TapSwipeApostropheKeySetting
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.futo.inputmethod.latin.uix.setSetting

/**
 * Picks the layout the keyboard is actually built from, after TapSwipe settings are applied.
 *
 * The keyboard is built by `KeyboardLayoutSet` and measured by `KeyboardSizingCalculator`. Both call
 * [current], so they always agree on which layout is in use. If they disagreed, the keyboard would be
 * drawn against one set of rows and sized against another.
 *
 * The layout files live in `java/assets-tapswipe/layouts/TapSwipe/`, outside the upstream layouts
 * submodule. They are not listed in `mapping.yaml`, so they never appear in the language picker. The
 * apostrophe key setting is the one control.
 */
object TapSwipeLayouts {
    const val QWERTY = "qwerty"
    const val QWERTY_APOSTROPHE = "qwerty_nintype"

    /**
     * The swap runs both ways. Before 2026-11-11 the apostrophe layout was picked as a language
     * layout, so a subtype can still name it. Without the reverse swap, turning the setting off would
     * do nothing for that user.
     *
     * @param name the layout the subtype asks for
     * @param apostropheKey whether the apostrophe key setting is on
     * @return the layout to build
     */
    @JvmStatic
    fun effectiveLayoutName(name: String, apostropheKey: Boolean): String = when {
        apostropheKey && name == QWERTY -> QWERTY_APOSTROPHE
        !apostropheKey && name == QWERTY_APOSTROPHE -> QWERTY
        else -> name
    }

    /** As [effectiveLayoutName], reading the setting. */
    @JvmStatic
    fun current(name: String): String =
        effectiveLayoutName(name, DataStoreHelper.getSetting(TapSwipeApostropheKeySetting))

    /** @return true if any stored subtype names the apostrophe layout */
    @JvmStatic
    fun subtypesUseApostropheLayout(subtypes: Set<String>): Boolean =
        subtypes.any { it.contains("KeyboardLayoutSet=$QWERTY_APOSTROPHE:") || it.endsWith("KeyboardLayoutSet=$QWERTY_APOSTROPHE") }

    /**
     * One time only: turns the setting on for a user who already picked the apostrophe layout as a
     * language layout, so the update does not take their key away.
     *
     * Transition window: until this write lands, [current] reads the setting as off and swaps the
     * subtype's layout back to plain qwerty. The write then fires the setting flow that `LatinIME`
     * collects, and the keyboard rebuilds with the key. The window is one datastore write long, on
     * the first start after the update.
     */
    suspend fun migrate(context: Context) {
        if (context.getSettingFlow(TapSwipeApostropheKeyMigratedSetting).first()) return
        if (subtypesUseApostropheLayout(context.getSettingFlow(SubtypesSetting).first())) {
            context.setSetting(TapSwipeApostropheKeySetting, true)
        }
        context.setSetting(TapSwipeApostropheKeyMigratedSetting, true)
    }
}
