package org.futo.inputmethod.latin.tapswipe

/**
 * Transient "peck mode is active" flag, used to show key borders while a word is being pecked out
 * and hide them again as soon as it is finished or a swipe joins the word.
 *
 * Deliberately *not* the persisted [org.futo.inputmethod.latin.uix.KeyBordersSetting]: writing that
 * would clobber the user's own preference, and every change rebuilds the whole drawable provider
 * (see `LatinIME.kt`, which recreates the theme when that setting changes) - far too heavy to run
 * twice per word. Instead `BasicThemeProvider` precomputes the bordered variants of the four styles
 * that depend on key borders and swaps between them at draw time when this flag is set.
 *
 * Only meaningful when the user's key-borders preference is *off*; with borders already on there is
 * nothing to force and the flag has no effect.
 */
object TapSwipePeckIndicator {
    /** Read on the UI thread during drawing, written on the UI thread from InputLogic. */
    @JvmStatic
    @Volatile
    var active: Boolean = false
        private set

    /**
     * @return true if the value changed, meaning the caller should invalidate the keyboard.
     */
    @JvmStatic
    fun set(value: Boolean): Boolean {
        if (active == value) return false
        active = value
        return true
    }
}
