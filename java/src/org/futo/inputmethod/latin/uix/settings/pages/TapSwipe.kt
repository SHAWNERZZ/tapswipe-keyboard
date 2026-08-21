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
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.SwipeSensitivitySetting
import org.futo.inputmethod.latin.TapSwipeLegacyTapRunSetting
import org.futo.inputmethod.latin.TapSwipeMasterModeSetting
import org.futo.inputmethod.latin.TapSwipeModeSetting
import org.futo.inputmethod.latin.TapSwipeAdaptiveGeometrySetting
import org.futo.inputmethod.latin.TapSwipeRealTapPositionSetting
import org.futo.inputmethod.latin.TapSwipeNintypeGesturesSetting
import org.futo.inputmethod.latin.TapSwipeWpmSetting
import org.futo.inputmethod.latin.TapSwipePeckCadenceSetting
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.theme.Typography
import kotlin.math.roundToInt

private val tapSwipeEnabled: @Composable () -> Boolean = { useDataStoreValue(TapSwipeModeSetting) }

private val adaptiveGeometryEnabled: @Composable () -> Boolean = {
    useDataStoreValue(TapSwipeModeSetting) && useDataStoreValue(TapSwipeAdaptiveGeometrySetting)
}

private val masterModeEnabled: @Composable () -> Boolean = {
    useDataStoreValue(TapSwipeModeSetting) && useDataStoreValue(TapSwipeMasterModeSetting)
}

/**
 * Settings for the TapSwipe input model. This whole screen is an addition by this fork.
 *
 * Layout notes:
 * - The master toggle is the only thing visible while TapSwipe is off. Everything below is
 *   meaningless in that state, and a wall of dimmed controls is worse than none.
 * - Toggles carry icons. `SettingItem` reserves a 48dp icon column whether or not one is given, so
 *   an icon-less toggle looks arbitrarily indented beside a slider, which draws full width.
 *   Filling the slot makes the indent read as deliberate instead of accidental.
 * - Each control sits directly under what it depends on - Master Mode, then its legacy-typing
 *   threshold - so the relationship is visible without reading the descriptions.
 */
val TapSwipeMenu = UserSettingsMenu(
    title = R.string.tapswipe_settings_title,
    navPath = "tapswipe", registerNavPath = true,
    settings = listOf(
        // The master switch. Always visible; everything else hangs off it.
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_enable,
            subtitle = R.string.tapswipe_settings_enable_subtitle,
            setting = TapSwipeModeSetting,
            icon = { Icon(painterResource(R.drawable.swipe_icon), contentDescription = null) }
        ),

        // ---- Appearance ----
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_master_mode,
            subtitle = R.string.tapswipe_settings_master_mode_subtitle,
            setting = TapSwipeMasterModeSetting,
            icon = { Icon(painterResource(R.drawable.blur), contentDescription = null) }
        ).copy(visibilityCheck = tapSwipeEnabled),

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

        // Learning sits directly above the page that shows what it learned, so the toggle and its
        // evidence read as one thing rather than two unrelated rows.
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_adaptive_geometry,
            subtitle = R.string.tapswipe_settings_adaptive_geometry_subtitle,
            setting = TapSwipeAdaptiveGeometrySetting,
            icon = { Icon(painterResource(R.drawable.move), contentDescription = null) }
        ).copy(visibilityCheck = tapSwipeEnabled),

        userSettingNavigationItem(
            title = R.string.tapswipe_settings_learned_geometry,
            style = NavigationItemStyle.HomeSecondary,
            navigateTo = "tapswipeGeometry",
            icon = R.drawable.activity
        ).copy(visibilityCheck = adaptiveGeometryEnabled),

        // Tuning, but a toggle rather than a slider, so it sits with the other toggles.
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_real_tap_position,
            subtitle = R.string.tapswipe_settings_real_tap_position_subtitle,
            setting = TapSwipeRealTapPositionSetting,
            icon = { Icon(painterResource(R.drawable.circle), contentDescription = null) }
        ).copy(visibilityCheck = tapSwipeEnabled),

        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_nintype_gestures,
            subtitle = R.string.tapswipe_settings_nintype_gestures_subtitle,
            setting = TapSwipeNintypeGesturesSetting,
            icon = { Icon(painterResource(R.drawable.direction_arrows), contentDescription = null) }
        ).copy(visibilityCheck = tapSwipeEnabled),

        // Its own screen. Backspace carries four gestures now, and a flat list cannot show which
        // of them a given setting belongs to. Not gated on tapSwipeEnabled: these live in
        // PointerTracker and work the same whether or not the input model is on.
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

        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_wpm,
            subtitle = R.string.tapswipe_settings_wpm_subtitle,
            setting = TapSwipeWpmSetting,
            icon = { Icon(painterResource(R.drawable.activity), contentDescription = null) }
        ),

        // ---- Tuning ----
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

        UserSetting(
            name = R.string.tapswipe_settings_sensitivity,
            subtitle = R.string.tapswipe_settings_sensitivity_subtitle,
            visibilityCheck = tapSwipeEnabled
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

        // ---- Footnotes ----
        userSettingDecorationOnly {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.tapswipe_settings_learning_note),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
            Text(
                stringResource(R.string.tapswipe_settings_apostrophe_layout_note),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
            Text(
                stringResource(R.string.tapswipe_settings_about),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(24.dp))
        }.copy(visibilityCheck = tapSwipeEnabled)
    )
)
