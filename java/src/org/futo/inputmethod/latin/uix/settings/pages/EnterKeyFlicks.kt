package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.TapSwipeEnterFlickMapSetting
import org.futo.inputmethod.latin.TapSwipeEnterFlicksSetting
import org.futo.inputmethod.latin.TapSwipeHidePeriodKeySetting
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.tapswipe.EnterFlickCatalog
import org.futo.inputmethod.latin.tapswipe.EnterFlickOption
import org.futo.inputmethod.latin.tapswipe.EnterFlicks
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.WarningTip
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.useSharedPrefsBool
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.v2keyboard.Direction

/**
 * Assigning punctuation and actions to the eight directions off the enter key.
 *
 * ### Why a grid rather than a list
 *
 * Eight dropdowns labelled "Up", "Up and right" and so on would be the cheaper screen, and a worse
 * one: what someone needs to answer here is "where is my question mark", which is a spatial question.
 * Laid out as a keypad the answer is where the thumb will go, so the screen reads the way the
 * gesture feels. It also makes the empty diagonals obvious as available space rather than as
 * settings someone forgot to fill in.
 *
 * The centre cell shows the enter key itself, both to anchor the directions and to make the point
 * that a plain tap is unchanged.
 */

/** Grid order, reading rows top to bottom. Null is the centre - the enter key itself. */
private val GRID: List<Direction?> = listOf(
    Direction.NorthWest, Direction.North, Direction.NorthEast,
    Direction.West,      null,            Direction.East,
    Direction.SouthWest, Direction.South, Direction.SouthEast,
)

private fun directionName(direction: Direction): Int = when (direction) {
    Direction.North -> R.string.enter_flicks_dir_north
    Direction.South -> R.string.enter_flicks_dir_south
    Direction.West -> R.string.enter_flicks_dir_west
    Direction.East -> R.string.enter_flicks_dir_east
    Direction.NorthWest -> R.string.enter_flicks_dir_north_west
    Direction.NorthEast -> R.string.enter_flicks_dir_north_east
    Direction.SouthWest -> R.string.enter_flicks_dir_south_west
    Direction.SouthEast -> R.string.enter_flicks_dir_south_east
}

@Composable
private fun OptionFace(option: EnterFlickOption?, large: Boolean) {
    when {
        option == null -> Text(
            "",
            style = if (large) Typography.Heading.Medium else Typography.Body.RegularMl
        )

        // Punctuation is its own best label - showing the character is showing the outcome.
        option.text != null -> Text(
            option.text,
            style = if (large) Typography.Heading.Medium else Typography.Heading.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            option.iconRes?.let {
                Icon(
                    painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            option.nameRes?.let {
                Text(
                    stringResource(it),
                    style = Typography.Small,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun DirectionCell(
    direction: Direction?,
    option: EnterFlickOption?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val isCentre = direction == null
    val background = when {
        isCentre -> MaterialTheme.colorScheme.secondaryContainer
        option != null -> MaterialTheme.colorScheme.surfaceVariant
        // An unassigned direction is drawn as an empty slot rather than a key, so the grid shows at
        // a glance which directions are actually doing something.
        else -> MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = Modifier
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(if (isCentre || !enabled) Modifier else Modifier.clickable { onClick() }),
        contentAlignment = Alignment.Center
    ) {
        if (isCentre) {
            Text(
                "↵",
                style = Typography.Heading.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        } else {
            OptionFace(option, large = false)
        }
    }
}

@Composable
private fun PickerDialog(
    direction: Direction,
    current: EnterFlickOption?,
    onPick: (EnterFlickOption?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        icon = { },
        title = {
            Text(
                stringResource(
                    R.string.enter_flicks_pick_title,
                    stringResource(directionName(direction))
                ),
                style = Typography.Body.MediumMl
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Clearing a direction is the first entry rather than a separate control: it is the
                // same kind of decision as picking one, and someone reassigning a direction they
                // regret should not have to hunt for how to undo it.
                DialogRow(
                    label = stringResource(R.string.enter_flicks_none),
                    selected = current == null,
                    onClick = { onPick(null) }
                )
                EnterFlickCatalog.ALL.forEach { option ->
                    DialogRow(
                        label = option.text ?: option.nameRes?.let { stringResource(it) } ?: option.id,
                        selected = option.id == current?.id,
                        onClick = { onPick(option) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun DialogRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primaryContainer
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            style = Typography.Body.RegularMl,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
fun EnterKeyFlicksScreen(navController: NavHostController? = null) {
    val enabled = useDataStoreValue(TapSwipeEnterFlicksSetting)
    val (stored, setStored) = useDataStore(TapSwipeEnterFlickMapSetting)
    val hidePeriod = useDataStoreValue(TapSwipeHidePeriodKeySetting)
    val doubleSpacePeriod =
        useSharedPrefsBool(Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, true).value

    val assignment = remember(stored) { EnterFlicks.parse(stored, EnterFlickCatalog.KNOWN_IDS) }
    var editing by remember { mutableStateOf<Direction?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenTitle(
            stringResource(R.string.enter_flicks_title),
            showBack = true,
            navController = navController
        )

        SettingToggleDataStore(
            title = stringResource(R.string.enter_flicks_enable),
            subtitle = stringResource(R.string.enter_flicks_enable_subtitle),
            setting = TapSwipeEnterFlicksSetting
        )

        if (enabled) {
            Tip(stringResource(R.string.enter_flicks_longpress_note))

            Text(
                stringResource(R.string.enter_flicks_intro),
                style = Typography.Small,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                GRID.chunked(3).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { direction ->
                            Box(modifier = Modifier.weight(1f)) {
                                DirectionCell(
                                    direction = direction,
                                    option = direction?.let {
                                        EnterFlickCatalog.option(assignment[it])
                                    },
                                    enabled = true,
                                    onClick = { editing = direction }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    setStored(EnterFlicks.serialize(EnterFlicks.DEFAULTS))
                }) { Text(stringResource(R.string.enter_flicks_reset)) }
            }

            SettingToggleDataStore(
                title = stringResource(R.string.enter_flicks_hide_period),
                subtitle = stringResource(R.string.enter_flicks_hide_period_subtitle),
                setting = TapSwipeHidePeriodKeySetting
            )

            // Only a warning once it is actually a problem. Double-space period defaults to on, so
            // for most people this is a note about why nothing broke rather than something to fix.
            if (hidePeriod && !doubleSpacePeriod) {
                WarningTip(stringResource(R.string.enter_flicks_hide_period_warning))
            } else if (hidePeriod) {
                Tip(stringResource(R.string.enter_flicks_hide_period_recommend))
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    editing?.let { direction ->
        PickerDialog(
            direction = direction,
            current = EnterFlickCatalog.option(assignment[direction]),
            onPick = { option ->
                val updated = assignment.toMutableMap()
                if (option == null) updated.remove(direction) else updated[direction] = option.id
                setStored(EnterFlicks.serialize(updated))
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}
