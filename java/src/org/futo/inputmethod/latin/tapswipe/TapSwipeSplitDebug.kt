package org.futo.inputmethod.latin.tapswipe

/**
 * The last automatic-split decision, in words, so a failing scenario can quote it.
 *
 * The scenario runner's report is the only diagnostic channel available when logcat is not, and a
 * message that says only "expected two words" cannot be acted on. This carries the boundaries that
 * were considered, what each one read, and which was chosen, so one pasted report is enough to say
 * where the search went.
 *
 * Written on the main thread from the decode-apply path and read from the runner's checks, which run
 * on the same thread. Volatile anyway, because the runner's waiting happens off it.
 */
object TapSwipeSplitDebug {

    @JvmStatic
    @Volatile
    var lastDecision: String = ""

    /**
     * How many words this process has actually broken in two.
     *
     * Reported as: with the setting off, words still seemed to split now and then. The split itself
     * cannot run in that state, so this exists to settle the question rather than argue it. A count
     * that stays at zero while the symptom appears means something else is inserting the space, and
     * the phantom space that arms after every gesture is the candidate.
     *
     * Never reset, so a rare event hours ago is still visible.
     */
    @JvmStatic
    @Volatile
    var performedCount: Int = 0
        private set

    /** The last word actually broken in two, for the same reason as [performedCount]. */
    @JvmStatic
    @Volatile
    var lastPerformed: String = "none"
        private set

    @JvmStatic
    fun notePerformed(head: String, at: Int) {
        performedCount++
        lastPerformed = "'$head' at stroke $at"
    }

    /**
     * Automatic spaces written by the ordinary typing path while TapSwipe was on.
     *
     * The other half of the same question. A space owed from a gesture is normally suppressed while
     * a word is open, and the guard reads the session. If the session has been dropped, the space
     * lands in the middle of the word instead, and the text then looks exactly as though the word
     * had been split deliberately.
     *
     * With [performedCount] confirmed at zero while words still appeared to split, this is what to
     * read next. A count that rises alongside the symptom names the cause.
     */
    @JvmStatic
    @Volatile
    var autoSpaceCount: Int = 0
        private set

    @JvmStatic
    @Volatile
    var lastAutoSpace: String = "none"
        private set

    @JvmStatic
    fun noteAutoSpace(context: String) {
        autoSpaceCount++
        lastAutoSpace = context
    }

    /** Called before a fresh attempt, so a stale line is never quoted as if it were this one. */
    @JvmStatic
    fun clear() {
        lastDecision = ""
    }

    @JvmStatic
    fun note(line: String) {
        lastDecision = line
    }

    /** Quotable suffix for a failure message, or empty when nothing has been decided yet. */
    @JvmStatic
    fun describe(): String = if (lastDecision.isEmpty()) "" else " [split: $lastDecision]"
}
