package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.TapSwipeMasterModeSetting
import org.futo.inputmethod.latin.TapSwipeModeSetting
import org.futo.inputmethod.latin.TapSwipePeckCadenceSetting
import org.futo.inputmethod.latin.TapSwipePeckMinTapsSetting
import org.futo.inputmethod.latin.SwipeSensitivitySetting
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.theme.Typography
import kotlin.math.roundToInt

/**
 * Settings for the TapSwipe input model. This whole screen is an addition by this fork and has no
 * counterpart upstream.
 */
val TapSwipeMenu = UserSettingsMenu(
    title = R.string.tapswipe_settings_title,
    navPath = "tapswipe", registerNavPath = true,
    settings = listOf(
        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_enable,
            subtitle = R.string.tapswipe_settings_enable_subtitle,
            setting = TapSwipeModeSetting
        ),

        UserSetting(
            name = R.string.tapswipe_settings_peck_threshold,
            subtitle = R.string.tapswipe_settings_peck_threshold_subtitle,
            visibilityCheck = { useDataStoreValue(TapSwipeModeSetting) }
        ) {
            SettingSlider(
                title = stringResource(R.string.tapswipe_settings_peck_threshold),
                subtitle = stringResource(R.string.tapswipe_settings_peck_threshold_subtitle),
                setting = TapSwipePeckMinTapsSetting,
                range = 1.0f..10.0f,
                transform = { it.roundToInt() },
                indicator = { "$it taps" },
                steps = 8
            )
        },

        UserSetting(
            name = R.string.tapswipe_settings_peck_cadence,
            subtitle = R.string.tapswipe_settings_peck_cadence_subtitle,
            visibilityCheck = { useDataStoreValue(TapSwipeModeSetting) }
        ) {
            SettingSlider(
                title = stringResource(R.string.tapswipe_settings_peck_cadence),
                subtitle = stringResource(R.string.tapswipe_settings_peck_cadence_subtitle),
                setting = TapSwipePeckCadenceSetting,
                range = 100.0f..600.0f,
                transform = { it.roundToInt() },
                indicator = { "$it ms between taps" },
                steps = 9
            )
        },

        userSettingToggleDataStore(
            title = R.string.tapswipe_settings_master_mode,
            subtitle = R.string.tapswipe_settings_master_mode_subtitle,
            setting = TapSwipeMasterModeSetting,
            disabled = { !useDataStoreValue(TapSwipeModeSetting) }
        ),

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

        userSettingDecorationOnly {
            Text(
                stringResource(R.string.tapswipe_settings_learning_note),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        },

        userSettingDecorationOnly {
            Text(
                stringResource(R.string.tapswipe_settings_about),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    )
)
