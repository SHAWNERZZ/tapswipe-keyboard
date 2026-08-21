package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.futo.inputmethod.latin.BackspaceTap
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.TapSwipeBackspaceSwipeUpSetting
import org.futo.inputmethod.latin.TapSwipeBackspaceTapSetting
import org.futo.inputmethod.latin.TapSwipeWordTrailsSetting
import org.futo.inputmethod.latin.uix.settings.DropDownPickerSettingItem
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.theme.Typography

/**
 * Everything the backspace key does, grouped by the gesture that triggers it.
 *
 * Grouped this way because that is the question someone arrives with. They know what their thumb
 * did and want to know why the text went. A list organized by feature name makes them read every
 * row to find the one that matches.
 *
 * The upstream settings for the slide and the hold live in the Typing menu and stay there. The note
 * at the bottom points at them, because the two sets describe one key and someone reading this
 * screen needs to know the other half exists.
 */

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = Typography.SmallMl,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
fun BackspaceGesturesScreen(navController: NavHostController? = null) {
    ScrollableList {
        ScreenTitle(
            stringResource(R.string.backspace_gestures_title),
            showBack = true,
            navController = navController
        )

        SectionHeader(stringResource(R.string.backspace_gestures_tap))

        val (tapMode, setTapMode) = useDataStore(TapSwipeBackspaceTapSetting)
        // Resolved up front. getDisplayName is called outside a composable context, so it cannot
        // reach stringResource itself.
        val tapNames = mapOf(
            BackspaceTap.LAST_GESTURE to stringResource(R.string.backspace_tap_last_gesture),
            BackspaceTap.WHOLE_WORD to stringResource(R.string.backspace_tap_whole_word),
            BackspaceTap.ONE_CHARACTER to stringResource(R.string.backspace_tap_one_character)
        )
        DropDownPickerSettingItem(
            label = stringResource(R.string.backspace_gestures_tap_removes),
            options = tapNames.keys.toList(),
            selection = tapMode,
            onSet = { setTapMode(it) },
            getDisplayName = { tapNames[it] ?: "" }
        )

        if (tapMode == BackspaceTap.LAST_GESTURE) {
            Tip(stringResource(R.string.backspace_tap_last_gesture_note))
        }

        SectionHeader(stringResource(R.string.backspace_gestures_swipe_up))

        SettingToggleDataStore(
            title = stringResource(R.string.tapswipe_settings_backspace_swipe_up),
            subtitle = stringResource(R.string.tapswipe_settings_backspace_swipe_up_subtitle),
            setting = TapSwipeBackspaceSwipeUpSetting,
            icon = { Icon(painterResource(R.drawable.delete), contentDescription = null) }
        )

        SectionHeader(stringResource(R.string.backspace_gestures_trails))

        SettingToggleDataStore(
            title = stringResource(R.string.tapswipe_settings_word_trails),
            subtitle = stringResource(R.string.tapswipe_settings_word_trails_subtitle),
            setting = TapSwipeWordTrailsSetting,
            icon = { Icon(painterResource(R.drawable.swipe_icon), contentDescription = null) }
        )

        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.backspace_gestures_slide_note),
            style = Typography.Small,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}
