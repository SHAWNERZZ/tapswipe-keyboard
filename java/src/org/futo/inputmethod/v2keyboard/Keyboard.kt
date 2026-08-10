package org.futo.inputmethod.v2keyboard
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind
import org.futo.inputmethod.keyboard.internal.KeyboardParams
import org.futo.inputmethod.latin.settings.Settings

object RowKeyListSerializer : SpacedListSerializer<Key>(KeyPathSerializer)

const val NumberRowHeight: Double = 0.8

enum class RowNumberRowMode(val displayByDefault: Boolean, val displayWhenExplicitlyActive: Boolean, val displayWhenExplicitlyInactive: Boolean) {
    Default(true, true, true),
    Filler(false, true, false),
    Hideable(false, false, true),
}

typealias KeyList = @Serializable(with = RowKeyListSerializer::class) List<Key>

/**
 * A keyboard row. Only one of [numbers], [letters], or [bottom] must be defined. The row type is
 * determined by which one of those is defined.
 */
@Serializable
data class Row(
    /**
     * If defined, this is a number row. Number rows by default have grow keys, no background,
     * and smaller height. They may also be hidden depending on the user settings and the value
     * of [Keyboard.numberRowMode].
     *
     * See [DefaultNumberRow]
     */
    val numbers: KeyList? = null,

    /**
     * If defined, this is a letters row. Letter rows by default are splittable.
     */
    val letters: KeyList? = null,

    /**
     * If defined, this is a bottom row. Bottom row should typically contain:
     * $symbols $action $space $contextual $enter
     *
     * See [DefaultBottomRow]
     */
    val bottom:  KeyList? = null,

    /**
     * (optional) The height multiplier for this row
     */
    val rowHeight: Double = if(numbers == null) { 1.0 } else { NumberRowHeight },

    /**
     * (optional) Whether or not this row is splittable. Enabled for letter rows by default.
     */
    val splittable: Boolean = letters != null,

    /**
     * (optional) How this row should behave with respect to the number row. Valid values:
     * * `Default` - always display this row
     * * `Filler` - only display when the number row is explicitly active
     * * `Hideable` - only display when the number row is explicitly inactive
     */
    val numRowMode: RowNumberRowMode = RowNumberRowMode.Default,

    /**
     * (optional) Default key attributes for keys in this row. Values set here supersede values
     * set in `Keyboard.attributes`.
     */
    val attributes: KeyAttributes = KeyAttributes(
        style = when {
            numbers != null -> KeyVisualStyle.NoBackground
            bottom  != null -> KeyVisualStyle.Functional
            else  -> null
        },

        width = when {
            numbers != null -> KeyWidth.Grow
            else  -> null
        },
    )
) {
    val keys: List<Key>
        get() = bottom ?: numbers ?: letters!!

    val isNumberRow: Boolean
        get() = numbers != null

    val isLetterRow: Boolean
        get() = letters != null

    val isBottomRow: Boolean
        get() = bottom != null

    init {

        // Ensure only one is defined
        assert(
            (numbers != null && letters == null && bottom == null)
               || (numbers == null && letters != null && bottom == null)
               || (numbers == null && letters == null && bottom != null)
        ) {
            "Only one of numbers, letters, or actions can be defined per row. In yaml, make sure you did not miss a `-` to start a new row."
        }
    }
}

val DefaultNumberRow = Row(
    numbers = "1234567890".mapIndexed { i, c ->
        CaseSelector(
            normal = BaseKey(c.toString()),
            shiftedManually = BaseKey("!@#$%^&*()"[i].toString()),
            shiftLocked = BaseKey(c.toString())
        )
    }
)

val DefaultNumberRowClassic = Row(
    numbers = "1234567890".mapIndexed { i, c ->
        CaseSelector(
            normal = BaseKey(c.toString()),
            shiftedManually = BaseKey("!@#$%^&*()"[i].toString()),
            shiftLocked = BaseKey(c.toString())
        )
    },
    rowHeight = 1.0,
    attributes = KeyAttributes(
        width = KeyWidth.Grow
    )
)

/**
 * How the default bottom row should be built.
 *
 * Every field here is a fork addition driven by a user setting. Grouped into one object rather than
 * passed as loose booleans because they interact - hiding the period changes how wide the enter key
 * should be, and that is only true when there is an enter key with flicks worth widening for.
 *
 * @param hideCommaKey a gesture provides the comma instead
 * @param hidePeriodKey the enter key takes the period's width
 * @param enterFlicks what each direction off the enter key does; empty leaves it an ordinary key
 */
data class BottomRowConfig(
    val hideCommaKey: Boolean = false,
    val hidePeriodKey: Boolean = false,
    val enterFlicks: Map<Direction, Key> = emptyMap(),
) {
    companion object {
        val Default = BottomRowConfig()
    }
}

/**
 * The standard bottom row, adjusted for [config].
 *
 * When a key is dropped nothing replaces it. The space key is [KeyWidth.Grow], so it takes any
 * width nobody else claims - which is the point for the comma, where the gesture buys back space
 * rather than leaving a hole. The period is the exception: [KeyWidth.WideFunctionalKey] hands its
 * width to the enter key instead, since that is the key that just gained eight new things to do and
 * has to be hit accurately to do them.
 */
fun bottomRowFor(config: BottomRowConfig): Row {
    val enterWidth = if (config.hidePeriodKey) {
        KeyWidth.WideFunctionalKey
    } else {
        KeyWidth.FunctionalKey
    }

    // FlickKey passes its own attributes to the direction keys, never to the primary, so the width
    // has to be set on the enter key nested inside it rather than on the wrapper.
    val enterKey = EnterKey(attributes = KeyAttributes(width = enterWidth))
    val enter: Key = if (config.enterFlicks.isEmpty()) {
        enterKey
    } else {
        FlickKey(
            primary = enterKey,
            up        = config.enterFlicks[Direction.North],
            down      = config.enterFlicks[Direction.South],
            left      = config.enterFlicks[Direction.West],
            right     = config.enterFlicks[Direction.East],
            upLeft    = config.enterFlicks[Direction.NorthWest],
            upRight   = config.enterFlicks[Direction.NorthEast],
            downLeft  = config.enterFlicks[Direction.SouthWest],
            downRight = config.enterFlicks[Direction.SouthEast],
        )
    }

    return Row(
        bottom = listOfNotNull(
            TemplateSymbolsKey,
            if (config.hideCommaKey) null else ContextualKey(fallbackKey = BaseKey(",")),
            TemplateActionKey,
            TemplateSpaceKey,
            TemplateOptionalZWNJKey,
            if (config.hidePeriodKey) null else TemplatePeriodKey,
            enter
        )
    )
}

val DefaultBottomRow = bottomRowFor(BottomRowConfig.Default)

enum class NumberRowMode {
    UserConfigurable,
    AlwaysEnabled,
    AlwaysDisabled
}

fun NumberRowMode.isActive(userSetting: Boolean) =
    when(this) {
        NumberRowMode.UserConfigurable -> userSetting
        NumberRowMode.AlwaysEnabled -> true
        NumberRowMode.AlwaysDisabled -> false
    }

enum class BottomRowHeightMode {
    Fixed,
    Flexible
}

enum class BottomRowWidthMode(val separateFunctional: Boolean) {
    SeparateFunctional(true),
    Identical(false)
}

enum class RowHeightMode(val clampHeight: Boolean) {
    ClampHeight(true),
    FillHeight(false)
}

object SpacedLanguageListSerializer : SpacedListSerializer<String>(String.serializer(), { it.split(" ") })
typealias SpacedStringList = @Serializable(with = SpacedLanguageListSerializer::class) List<String>


/**
 * Override the symbols and other layouts for a specific layout.
 */
@Serializable
data class LayoutSetOverrides(
    val symbols: String = "symbols",
    val symbolsShifted: String = "symbols_shift",
    val number: String = "number",
    val phone: String = "phone",
    val phoneShifted: String = "phone_shift"
)

@Serializable
data class SubKeyboard(
    val rows: List<Row>,
    val attributes: KeyAttributes = KeyAttributes()
)

/**
 * A keyboard layout definition, the entry point for the layout yaml files.
 */
@Serializable
data class Keyboard(
    /**
     * The human-readable name of the layout. If the layout is for a specific language, this should
     * be written in the relevant language.
     */
    val name: String,

    /**
     * The rows defined for the layout. Defining the number row, bottom row, or the functional
     * keys (shift/backspace) is optional here. If they are missing, defaults will automatically be
     * added to `effectiveRows`.
     */
    private val rows: List<Row>,

    /**
     * List of languages this layout is intended for. It will be displayed as an option for the
     * specified languages.
     */
    val languages: SpacedStringList = listOf(),

    /**
     * (optional) A human-readable description of the layout. Authorship/origin information may
     * be added here. This is intended to be displayed to the user when they are selecting layouts.
     */
    val description: String = "",

    /**
     * (optional) Override the symbols layout or other layouts for this layout set.
     */
    val layoutSetOverrides: LayoutSetOverrides = LayoutSetOverrides(),

    /**
     * (optional) Whether the number row should be user-configurable, always displayed, or never.
     */
    val numberRowMode: NumberRowMode = NumberRowMode.UserConfigurable,

    /**
     * (optional) Whether the bottom row should always maintain a consistent height, or whether
     * it should grow and shrink.
     */
    val bottomRowHeightMode: BottomRowHeightMode = BottomRowHeightMode.Fixed,

    /**
     * (optional) Whether the bottom row should follow key widths of other rows, or should maintain
     * separate widths for consistency.
     */
    val bottomRowWidthMode: BottomRowWidthMode = BottomRowWidthMode.SeparateFunctional,

    /**
     * (optional) Default attributes to use for all rows
     */
    val attributes: KeyAttributes = KeyAttributes(),

    /**
     * (optional) Definitions of custom key widths. Values are between 0.0 and 1.0, with 1.0
     * representing 100% of the keyboard width.
     */
    val overrideWidths: Map<KeyWidth, Float> = mapOf(),

    /**
     * (optional) Whether or not rows should fill the vertical space, or have vertical gaps added.
     */
    val rowHeightMode: RowHeightMode = RowHeightMode.ClampHeight,

    /**
     * (optional) Whether or not the ZWNJ key should be shown in place of the contextual key.
     */
    val useZWNJKey: Boolean = false,

    /**
     * (optional) Minimum width for functional keys.
     */
    val minimumFunctionalKeyWidth: Float = 0.125f,

    /**
     * (optional) Minimum width for functional keys in the bottom row.
     */
    val minimumBottomRowFunctionalKeyWidth: Float = 0.15f,

    /**
     * (optional) Alternative pages for this layout, use in conjunction with $alt0, $alt1, $alt2
     */
    val altPages: List<List<Row>> = listOf(),


    /**
     * (optional) Which combiners to use for this layout
     */
    val combiners: List<CombinerKind> = listOf(CombinerKind.DeadKey),

    /**
     * Whether or not automatic shifting should apply for this keyboard, when input starts or a
     * sentence is finished.
     */
    val autoShift: Boolean = true,

    /**
     * (optional) Whether rows should be mirrored (reversed) in one-handed mode. When enabled and
     * the keyboard is in left-handed one-hand mode, each row's keys are reversed so that keys
     * like backspace move to the opposite (more reachable) side.
     */
    val mirrorInOneHanded: Boolean = false,

    /**
     * (optional) Whether the split keyboard mode should be offered for this layout. Set to false
     * for layouts whose rows don't split well (e.g. due to rowSpan keys or unusual arrangements).
     */
    val supportsSplit: Boolean = true,

    val subKeyboards: Map<KeyboardLayoutKind, SubKeyboard> = emptyMap(),
    val imeHint: String? = null


    //val element: KeyboardElement = KeyboardElement.Alphabet,
    //val rowWidthMode: RowWidthMode = RowWidthMode.PadSides,
    //val script: Script = Script.Latin,
    //val longPressKeysMode: LongPressKeysMode = LongPressKeysMode.UserConfigurable,
) {
    var id: String = ""

    private fun ensureRowsValid(rows: List<Row>) {
        assert(rows.first().isNumberRow) { "The first row in a keyboard must be the number row" }
        assert(rows.last().isBottomRow)  { "The last row in a keyboard must be the bottom row" }
        assert(rows.count { it.isNumberRow } == 1) { "Keyboard can only contain one number row" }
        assert(rows.count { it.isBottomRow } == 1) { "Keyboard can only contain one bottom row" }
        assert(rows.count { it.isLetterRow } in 1..8) { "Keyboard must contain between 1 and 8 letter rows" }
    }

    /**
     * @param bottomRow how to build the default bottom row. Only affects the *default* bottom row -
     *   a layout that defines its own is left alone, since removing or rebuilding a key someone
     *   placed deliberately would be presumptuous.
     */
    @JvmOverloads
    fun getEffectiveRows(
        numberRowMode: Int,
        bottomRow: BottomRowConfig = BottomRowConfig.Default
    ) = rows.toMutableList().apply {
        if(find { it.isNumberRow } == null) {
            add(0, when(numberRowMode) {
                Settings.NUMBER_ROW_MODE_CLASSIC -> DefaultNumberRowClassic
                else -> DefaultNumberRow
            })
        }

        if(find { it.isBottomRow } == null) {
            // If action row is not explicitly defined, shift and delete are implicitly added to last row
            // (unless a row has explicitly defined shift or delete key)

            if(!any {
                it.isLetterRow && it.letters != null && (
                        it.letters.contains(TemplateShiftKey)
                                || it.letters.contains(TemplateDeleteKey))
            }) {
                val ultimateRow = removeAt(size - 1)
                assert(ultimateRow.isLetterRow)

                val updatedRow = ultimateRow.copy(
                    letters = ultimateRow.letters!!.toMutableList().apply {
                        add(0, TemplateShiftKey)
                        add(TemplateDeleteKey)
                    }
                )

                add(updatedRow)
            }


            // Add default bottom row
            add(bottomRowFor(bottomRow))
        }

        ensureRowsValid(this)
    }.toList()

    fun build(context: Context, params: KeyboardParams, layoutParams: LayoutParams): org.futo.inputmethod.keyboard.Keyboard {
        val engine = LayoutEngine(context, this, params, layoutParams)
        return engine.build()
    }
}