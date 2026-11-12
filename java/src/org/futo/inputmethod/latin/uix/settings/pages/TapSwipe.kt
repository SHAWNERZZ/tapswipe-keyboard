package org.futo.inputmethod.latin.uix.settings.pages

import androidx.annotation.StringRes
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
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
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.SwipeSensitivitySetting
import org.futo.inputmethod.latin.TapSwipeLegacyTapRunSetting
import org.futo.inputmethod.latin.TapSwipeMasterModeSetting
import org.futo.inputmethod.latin.TapSwipeModeSetting
import org.futo.inputmethod.latin.TapSwipeAdaptiveGeometrySetting
import org.futo.inputmethod.latin.TapSwipeAutoSplitSetting
import org.futo.inputmethod.latin.TapSwipeWordTrailsSetting
import org.futo.inputmethod.latin.TapSwipeNintypeGesturesSetting
import org.futo.inputmethod.latin.TapSwipeApostropheKeySetting
import org.futo.inputmethod.latin.TapSwipeWpmSetting
import org.futo.inputmethod.latin.TapSwipePeckCadenceSetting
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.theme.Typography
import kotlin.math.roundToInt

private val tapSwipeEnabled: @Composable () -> Boolean = { useDataStoreValue(TapSwipeModeSetting) }

private val adaptiveGeometryEnabled: @Composable () -> Boolean = {
    useDataStoreValue(TapSwipeAdaptiveGeometrySetting)
}

private val masterModeEnabled: @Composable () -> Boolean = {
    useDataStoreValue(TapSwipeModeSetting) && useDataStoreValue(TapSwipeMasterModeSetting)
}

private fun section(@androidx.annotation.StringRes title: Int) = userSettingDecorationOnly {
    ScreenTitle(stringResource(title))
}

/**
 * Settings for the TapSwipe input model and this fork's other additions.
 *
 * Organized into sections a first-time user can read top to bottom (2026-11-12):
 * - Input model: the TapSwipe switch and what only it uses. Auto spacing sits in its own sub-menu,
 *   marked as work in progress.
 * - Swiping, Key geometry, Layout: work with or without the input model, so they are always shown.
 *   Swipe sensitivity and the Nintype gestures used to hide while the input model was off, although
 *   neither depends on it.
 * - Appearance: display-only options. Master Mode and the word's gestures need the input model, so
 *   they show disabled, with the reason, instead of disappearing.
 * - Tools: the typing test.
 *
 * Removed: "Use exact tap positions". It only ever applied inside the input model, where key centres
 * are strictly less information, and the learner needs the real positions. It is now always on.
 *
 * Toggles carry icons. `SettingItem` reserves a 48dp icon column whether or not one is given, so an
 * icon-less toggle looks arbitrarily indented beside a slider, which draws full width.
 */
val TapSwipeMenu = UserSettingsMenu(
    title = R.string.tapswipe_settings_title,
    navPath = "tapswipe", registerNavPath = true,
    settings = listOf(
        // ---- Input model ----
        section(R.string.tapswipe_section_input_model),
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_enable,
            subtitle = R.string.tapswipe_settings_enable_subtitle,
            setting = TapSwipeModeSetting,
            icon = { Icon(painterResource(R.drawable.swipe_icon), contentDescription = null) }
        ),
        UserSetting(
            name = R.string.tapswipe_settings_peck_cadence,
            subtitle = R.string.tapswipe_settings_peck_cadence_subtitle,
            visibilityCheck = tapSwipeEnabled
        ) {
            SettingSlider(
                title = stringResource(R.string.tapswipe_settings_peck_cadence),
                subtitle = stringResource(R.string.tapswipe_settings_peck_cadence_subtitle),
                setting = TapSwipePeckCadenceSetting,
                range = 100.0f..600.0f,
                transform = { it.roundToInt() },
                indicator = { "$it ms" },
                steps = 9
            )
        },
        userSettingNavigationItem(
            title = R.string.tapswipe_settings_auto_spacing,
            subtitle = R.string.tapswipe_settings_auto_spacing_subtitle,
            style = NavigationItemStyle.HomeSecondary,
            navigateTo = "tapswipeAutoSpacing",
            icon = R.drawable.scissors
        ).copy(visibilityCheck = tapSwipeEnabled),

        // ---- Swiping ----
        section(R.string.tapswipe_section_swiping),
        UserSetting(
            name = R.string.tapswipe_settings_sensitivity,
            subtitle = R.string.tapswipe_settings_sensitivity_subtitle
        ) {
            SettingSlider(
                title = stringResource(R.string.tapswipe_settings_sensitivity),
                subtitle = stringResource(R.string.tapswipe_settings_sensitivity_subtitle),
                setting = SwipeSensitivitySetting,
                range = 0.5f..4.0f,
                transform = { (it * 20.0f).roundToInt() / 20.0f },
                indicator = { "%.2fx".format(it) }
            )
        },
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_nintype_gestures,
            subtitle = R.string.tapswipe_settings_nintype_gestures_subtitle,
            setting = TapSwipeNintypeGesturesSetting,
            icon = { Icon(painterResource(R.drawable.direction_arrows), contentDescription = null) }
        ),

        // ---- Key geometry ----
        // Learning sits directly above the page that shows what it learned, so the toggle and its
        // evidence read as one thing rather than two unrelated rows.
        section(R.string.tapswipe_section_key_geometry),
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_adaptive_geometry,
            subtitle = R.string.tapswipe_settings_adaptive_geometry_subtitle,
            setting = TapSwipeAdaptiveGeometrySetting,
            icon = { Icon(painterResource(R.drawable.move), contentDescription = null) }
        ),
        userSettingNavigationItem(
            title = R.string.tapswipe_settings_learned_geometry,
            style = NavigationItemStyle.HomeSecondary,
            navigateTo = "tapswipeGeometry",
            icon = R.drawable.activity
        ).copy(visibilityCheck = adaptiveGeometryEnabled),

        // ---- Layout ----
        section(R.string.tapswipe_section_layout),
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_apostrophe_key,
            subtitle = R.string.tapswipe_settings_apostrophe_key_subtitle,
            setting = TapSwipeApostropheKeySetting,
            icon = { Icon(painterResource(R.drawable.direction_arrows), contentDescription = null) }
        ),
        // Its own screen. Backspace carries four gestures, and a flat list cannot show which of
        // them a given setting belongs to. These live in PointerTracker and work with or without
        // the input model.
        userSettingNavigationItem(
            title = R.string.tapswipe_settings_backspace,
            subtitle = R.string.tapswipe_settings_backspace_subtitle,
            style = NavigationItemStyle.HomeSecondary,
            navigateTo = "backspaceGestures",
            icon = R.drawable.delete
        ),
        // Its own screen rather than a toggle: the feature is the assignment, and there is no way to
        // show eight of those in a settings row.
        userSettingNavigationItem(
            title = R.string.tapswipe_settings_enter_flicks,
            subtitle = R.string.tapswipe_settings_enter_flicks_subtitle,
            style = NavigationItemStyle.HomeSecondary,
            navigateTo = "enterKeyFlicks",
            icon = R.drawable.direction_arrows
        ),

        // ---- Appearance ----
        section(R.string.tapswipe_section_appearance),
        inputModelToggle(
            title = R.string.tapswipe_settings_master_mode,
            subtitle = R.string.tapswipe_settings_master_mode_subtitle,
            setting = TapSwipeMasterModeSetting,
            icon = { Icon(painterResource(R.drawable.blur), contentDescription = null) }
        ),
        // Directly beneath Master Mode: it only does anything while the letters are hidden.
        UserSetting(
            name = R.string.tapswipe_settings_legacy_run,
            subtitle = R.string.tapswipe_settings_legacy_run_subtitle,
            visibilityCheck = masterModeEnabled
        ) {
            SettingSlider(
                title = stringResource(R.string.tapswipe_settings_legacy_run),
                subtitle = stringResource(R.string.tapswipe_settings_legacy_run_subtitle),
                setting = TapSwipeLegacyTapRunSetting,
                range = 0.0f..15.0f,
                transform = { it.roundToInt() },
                indicator = { if (it == 0) "Off" else "$it taps" },
                steps = 14
            )
        },
        inputModelToggle(
            title = R.string.tapswipe_settings_word_trails,
            subtitle = R.string.tapswipe_settings_word_trails_subtitle,
            setting = TapSwipeWordTrailsSetting,
            icon = { Icon(painterResource(R.drawable.swipe_icon), contentDescription = null) }
        ),
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_wpm,
            subtitle = R.string.tapswipe_settings_wpm_subtitle,
            setting = TapSwipeWpmSetting,
            icon = { Icon(painterResource(R.drawable.activity), contentDescription = null) }
        ),

        // ---- Tools ----
        section(R.string.tapswipe_section_tools),
        userSettingNavigationItem(
            title = R.string.tapswipe_settings_typing_test,
            subtitle = R.string.tapswipe_settings_typing_test_subtitle,
            style = NavigationItemStyle.HomeSecondary,
            navigateTo = "tapswipeTypingTest",
            icon = R.drawable.activity
        ),

        // ---- Footnotes ----
        userSettingDecorationOnly {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.tapswipe_settings_learning_note),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
            Text(
                stringResource(R.string.tapswipe_settings_about),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    )
)

/**
 * Automatic spacing: a sub-menu of the input model, marked as work in progress. The word split is
 * the only option so far; this is where the auto-spacing exploration will add more.
 */
val TapSwipeAutoSpacingMenu = UserSettingsMenu(
    title = R.string.tapswipe_settings_auto_spacing,
    navPath = "tapswipeAutoSpacing", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            Text(
                stringResource(R.string.tapswipe_settings_auto_spacing_wip),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        },
        // Sits with the input-interpretation toggles rather than under tuning: it changes what a
        // word is, which is the same kind of decision as where a tap is taken to have landed.
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_auto_split,
            subtitle = R.string.tapswipe_settings_auto_split_subtitle,
            setting = TapSwipeAutoSplitSetting,
            icon = { Icon(painterResource(R.drawable.scissors), contentDescription = null) }
        ),
    )
)

/**
 * A toggle that only works while the TapSwipe input model is on. With the input model off, the
 * toggle is greyed out and its subtitle says why, whatever its stored value. FUTO's own disabled
 * subtitle shows only while the value is off, so a setting left on would give no reason.
 */
private fun inputModelToggle(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    setting: SettingsKey<Boolean>,
    icon: @Composable () -> Unit
): UserSetting = UserSetting(
    name = title,
    subtitle = subtitle,
    component = {
        val off = !useDataStoreValue(TapSwipeModeSetting)
        SettingToggleDataStore(
            title = stringResource(title),
            setting = setting,
            subtitle = stringResource(
                if (off) R.string.tapswipe_settings_needs_input_model else subtitle),
            disabled = off,
            icon = icon
        )
    }
)
