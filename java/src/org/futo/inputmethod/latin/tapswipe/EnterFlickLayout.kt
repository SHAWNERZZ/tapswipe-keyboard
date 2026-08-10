package org.futo.inputmethod.latin.tapswipe

import org.futo.inputmethod.latin.TapSwipeEnterFlickMapSetting
import org.futo.inputmethod.latin.TapSwipeEnterFlicksSetting
import org.futo.inputmethod.latin.TapSwipeHidePeriodKeySetting
import org.futo.inputmethod.latin.TapSwipeNintypeGesturesSetting
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.v2keyboard.BottomRowConfig
import org.futo.inputmethod.v2keyboard.Direction
import org.futo.inputmethod.v2keyboard.Key

/**
 * Reads the settings that reshape the bottom row into the form the layout engine wants.
 *
 * One place rather than two: the keyboard is built by [org.futo.inputmethod.v2keyboard.LayoutEngine]
 * and measured by [org.futo.inputmethod.v2keyboard.KeyboardSizingCalculator], and the two must agree
 * exactly. If they disagree about which keys exist, the keyboard is drawn against one row and sized
 * against another.
 */
object EnterFlickLayout {

    /** @return the enter key's directions, or empty when the feature is off or nothing is assigned */
    @JvmStatic
    fun currentFlicks(): Map<Direction, Key> {
        if (!DataStoreHelper.getSetting(TapSwipeEnterFlicksSetting)) return emptyMap()

        val stored = DataStoreHelper.getSetting(TapSwipeEnterFlickMapSetting)
        return EnterFlicks.parse(stored, EnterFlickCatalog.KNOWN_IDS)
            .mapNotNull { (direction, id) ->
                EnterFlickCatalog.option(id)?.let { direction to it.toKey() }
            }.toMap()
    }

    /**
     * @return how the bottom row should be built right now
     */
    @JvmStatic
    fun currentBottomRow(): BottomRowConfig {
        val flicks = currentFlicks()
        return BottomRowConfig(
            hideCommaKey = DataStoreHelper.getSetting(TapSwipeNintypeGesturesSetting),
            // Hiding the period only makes sense as a trade for the flicks that replace it. Reading
            // the flicks rather than just the setting also means an assignment of nothing does not
            // silently cost the period key.
            hidePeriodKey = flicks.isNotEmpty() &&
                    DataStoreHelper.getSetting(TapSwipeHidePeriodKeySetting),
            enterFlicks = flicks
        )
    }
}
