package org.futo.inputmethod.latin.tapswipe

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.AllActionsMap
import org.futo.inputmethod.v2keyboard.BaseKey
import org.futo.inputmethod.v2keyboard.KeyAttributes
import org.futo.inputmethod.v2keyboard.MoreKeyMode

/**
 * One thing a direction off the enter key can be set to.
 *
 * @param id stable token written to settings; changing one orphans whatever users had assigned
 * @param spec how the keyboard builds this as a key - see [EnterFlickCatalog]
 * @param text what to show in the picker, for options that are literally a character
 * @param nameRes what to show in the picker instead, for options that are not
 * @param iconRes optional picker icon
 */
data class EnterFlickOption(
    val id: String,
    val spec: String,
    val text: String? = null,
    @StringRes val nameRes: Int? = null,
    @DrawableRes val iconRes: Int? = null,
) {
    /**
     * The key this becomes in a layout.
     *
     * Must be a [BaseKey]: the flick key type casts each direction to one and silently drops
     * anything else, so returning a different key kind would produce a direction that does nothing
     * with no error to explain why.
     */
    fun toKey(): BaseKey = BaseKey(
        spec = spec,
        // A flick direction is reached by dragging, so there is no press to long-press on, and no
        // room to show a popup for one.
        attributes = KeyAttributes(moreKeyMode = MoreKeyMode.OnlyExplicit, showPopup = false)
    )
}

/**
 * Everything a direction off the enter key may be set to.
 *
 * Deliberately a fixed list rather than free text entry. A gesture is worth spending only on
 * something used often enough to be worth memorising a direction for, and an open field invites
 * assigning strings that would be better served by the clipboard.
 *
 * ### Punctuation
 *
 * The characters here are the ones that interrupt typing most: they sit on the symbols page, so
 * reaching one today costs a layout switch, a tap, and a switch back. Letters are absent on purpose
 * - they cost one tap already.
 *
 * ### Actions
 *
 * The enter key's long-press menu does not survive being made flickable, and it held shift+enter
 * and field navigation. Rather than lose them, they are offered here as assignments, so anyone who
 * relied on them can put them back on a direction of their choosing. The rest of the editor actions
 * come along because they cost nothing to offer once the mechanism exists.
 */
object EnterFlickCatalog {

    private fun punctuation(id: String, text: String) = EnterFlickOption(
        id = id,
        // A lone character is its own spec: the parser derives both label and code from it. The
        // exceptions are the two characters the spec syntax reserves, escaped below.
        spec = when (text) {
            "|" -> "\\|"
            "\\" -> "\\\\"
            else -> text
        },
        text = text
    )

    /**
     * An editor action, addressed by the same id the action registry uses.
     *
     * Resolved through [AllActionsMap] rather than hardcoding a code, because action codes are
     * assigned by position in that map - writing the number down here would silently point at a
     * different action the moment one is inserted.
     */
    private fun action(actionId: String): EnterFlickOption? {
        val action = AllActionsMap[actionId] ?: return null
        return EnterFlickOption(
            id = actionId,
            spec = "!icon/action_$actionId|!code/action_$actionId",
            nameRes = action.name,
            iconRes = action.icon
        )
    }

    /** Ordered as it is displayed: punctuation first, since that is what most people want here. */
    val ALL: List<EnterFlickOption> = listOfNotNull(
        punctuation("question", "?"),
        punctuation("exclamation", "!"),
        punctuation("paren_open", "("),
        punctuation("paren_close", ")"),
        punctuation("quote", "\""),
        punctuation("apostrophe", "'"),
        punctuation("colon", ":"),
        punctuation("semicolon", ";"),
        punctuation("dash", "-"),
        punctuation("underscore", "_"),
        punctuation("slash", "/"),
        punctuation("at", "@"),
        punctuation("hash", "#"),
        punctuation("ampersand", "&"),
        punctuation("asterisk", "*"),
        punctuation("plus", "+"),
        punctuation("equals", "="),
        punctuation("percent", "%"),
        punctuation("ellipsis", "…"),
        punctuation("bracket_open", "["),
        punctuation("bracket_close", "]"),
        punctuation("brace_open", "{"),
        punctuation("brace_close", "}"),
        punctuation("comma", ","),
        punctuation("period", "."),

        // Recovered from the long-press menu that flicks displace.
        EnterFlickOption(
            id = "shift_enter",
            spec = "!icon/enter_key|!code/key_shift_enter",
            nameRes = R.string.enter_flick_shift_enter
        ),
        EnterFlickOption(
            id = "tab",
            spec = "!icon/tab_key|!code/key_tab",
            nameRes = R.string.enter_flick_tab
        ),

        action("paste"),
        action("clipboard_history"),
        action("undo"),
        action("redo"),
        action("cut"),
        action("copy"),
        action("select_all"),
        action("emoji"),
        action("voice_input"),
        action("switch_language"),
        action("text_edit"),
        action("left"),
        action("right"),
        action("up"),
        action("down"),
    )

    private val byId: Map<String, EnterFlickOption> = ALL.associateBy { it.id }

    val KNOWN_IDS: Set<String> = byId.keys

    fun option(id: String?): EnterFlickOption? = id?.let { byId[it] }
}
