package org.futo.inputmethod.latin;

import android.test.suitebuilder.annotation.LargeTest;
import android.view.inputmethod.BaseInputConnection;

import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.uix.DataStoreHelper;
import org.futo.inputmethod.latin.uix.SettingsKey;

/**
 * End-to-end scenarios for the TapSwipe input model.
 *
 * These drive the real {@link org.futo.inputmethod.latin.inputlogic.InputLogic} against a real
 * EditText, which is the only way to cover this feature's actual failure mode: sequencing bugs in
 * the seam between evidence changing and a derived result being applied. Unit tests over the
 * session cannot see those.
 *
 * **Every test here corresponds to a bug that shipped.** The names say what went wrong.
 *
 *     ./gradlew connectedUnstableDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=org.futo.inputmethod.latin.TapSwipeInputTests
 */
@LargeTest
public class TapSwipeInputTests extends InputTestsBase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        setTapSwipeEnabled(true);
    }

    @Override
    protected void tearDown() throws Exception {
        setTapSwipeEnabled(false);
        super.tearDown();
    }

    private void setBoolSetting(final SettingsKey<Boolean> setting, final boolean value) {
        org.futo.inputmethod.latin.uix.SettingsKt.setSettingBlocking(
                getContext(), setting.getKey(), value);
        // The value reaches InputLogic through DataStoreHelper's in-memory cache, which is fed by
        // a flow collector - so it lands asynchronously rather than on the write.
        for (int i = 0; i < 100; i++) {
            if (DataStoreHelper.getSetting(setting) == value) return;
            sleep(20);
            runMessages();
        }
        fail("setting " + setting.getKey() + " never propagated to DataStoreHelper");
    }

    private void setTapSwipeEnabled(final boolean enabled) {
        setBoolSetting(SwipeDecoderDictionaryKt.getTapSwipeModeSetting(), enabled);
    }

    private String text() {
        return mEditText.getText().toString();
    }

    // ------------------------------------------------------------------ core model

    /**
     * Fix 1. A second swipe used to replace the first word instead of extending it, because
     * validate-on-read fired in the window before the first decode was written back.
     */
    public void testSecondSwipeExtendsTheWordRatherThanReplacingIt() {
        gesture("hel");
        gesture("lo");
        type(" ");
        assertEquals("two swipes must build one word", "hello ", text());
    }

    /**
     * Fix 4 and 8. The headline case. A tap after a swipe scheduled an ordinary typing query whose
     * autocorrection was committed instead of the decoded word, so this displayed "but" and
     * committed "by".
     */
    public void testSwipeThenTapCommitsWhatIsDisplayed() {
        gesture("bu");
        type("t");
        final String composing = text();
        type(" ");
        assertEquals("commit must match what was composed", composing.trim() + " ", text());
        assertFalse("must not commit the pre-tap word", text().trim().equals("by"));
    }

    /** Fix 2. A swipe starting used to hard-commit the tapped prefix, losing it from the word. */
    public void testTapThenSwipeKeepsTheTappedPrefix() {
        type("h");
        gesture("el");
        type(" ");
        assertFalse("the tapped prefix must not vanish", text().trim().isEmpty());
        assertTrue("expected the tap to survive into the word, got: " + text(),
                text().toLowerCase().startsWith("h"));
    }

    /** Lifting a finger must never finalize; only a separator does. */
    public void testFingerLiftDoesNotCommit() {
        gesture("hel");
        assertNotSame("the word must still be composing after a finger lift",
                -1, BaseInputConnection.getComposingSpanStart(mEditText.getText()));
    }

    /** Fix 9. The caps mode was clobbered on the second stroke, so the re-decode came back lower. */
    public void testSentenceStartStaysCapitalisedAcrossTwoStrokes() {
        gesture("hel");
        gesture("lo");
        type(" ");
        final String out = text();
        assertTrue("expected a capitalised word at sentence start, got: " + out,
                Character.isUpperCase(out.charAt(0)));
    }

    // ------------------------------------------------------------------ backspace

    /**
     * Fix 10. Backspace after a swipe+tap appended the re-decode instead of replacing it,
     * producing "ButBy".
     */
    public void testStrokeUndoReplacesRatherThanAppends() {
        gesture("bu");
        type("t");
        final String beforeUndo = text();
        type(Constants.CODE_DELETE);
        final String afterUndo = text();

        assertFalse("undo must not append to the previous candidate: " + afterUndo,
                afterUndo.length() > beforeUndo.length());
        assertFalse("undo produced a concatenation: " + afterUndo,
                afterUndo.toLowerCase().contains("butby"));
    }

    /** Stroke undo must keep the earlier strokes; the word shrinks rather than disappearing. */
    public void testStrokeUndoKeepsTheRestOfTheWord() {
        gesture("bu");
        type("t");
        type(Constants.CODE_DELETE);
        assertFalse("the whole word must not be wiped by one backspace", text().isEmpty());
    }

    /** Backspacing every stroke clears the word and leaves nothing behind. */
    public void testUndoingEveryStrokeClearsTheWord() {
        gesture("hel");
        type(Constants.CODE_DELETE);
        type(Constants.CODE_DELETE);
        assertEquals("field should be empty again", "", text());
    }

    /** A pecked word deletes one character at a time, not the whole word. */
    public void testBackspaceInAPeckedWordRemovesOneCharacter() {
        type("cat");
        type(Constants.CODE_DELETE);
        assertEquals("ca", text());
    }

    /**
     * Fix 10, second half. Backspacing a pecked word used to leave the session's record stale, so
     * the next validate-on-read discarded the taps still belonging to the word.
     */
    public void testPeckBackspaceThenSwipeStillFusesTheTaps() {
        type("ca");
        type(Constants.CODE_DELETE);
        gesture("at");
        type(" ");
        assertFalse("word was lost after peck-backspace-swipe", text().trim().isEmpty());
    }

    // ------------------------------------------------------------------ session hygiene

    /**
     * The runaway-word bug class: strokes outliving their word. A committed word must not leak
     * evidence into the next one.
     */
    public void testStrokesDoNotLeakIntoTheNextWord() {
        gesture("hel");
        type(" ");
        gesture("cat");
        type(" ");
        final String[] words = text().trim().split("\\s+");
        assertEquals("expected exactly two words, got: " + text(), 2, words.length);
        assertTrue("second word grew from the first: " + text(), words[1].length() <= 6);
    }

    /** Fix 7. A finalizer must clear the session even with a decode still in flight. */
    public void testRapidSwipeSpaceSwipeProducesTwoWords() {
        gesture("hel");
        type(" ");
        gesture("bu");
        type(" ");
        assertEquals("expected two words, got: " + text(),
                2, text().trim().split("\\s+").length);
    }

    /** With TapSwipe off, nothing here should change stock behaviour. */
    public void testDisablingTapSwipeRestoresStockBehaviour() {
        setTapSwipeEnabled(false);
        type("hello");
        type(" ");
        assertEquals("hello ", text());
    }
}
