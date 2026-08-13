package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * JVM tests for not destroying the user's learned geometry.
 *
 * Separate from [TapSwipeTouchModelTest], which covers what the model *learns*. Everything here is
 * about the file: this is the only copy of weeks of adaptation, it cannot be reconstructed, and
 * every failure mode is silent - nobody notices an empty model being written until the typing gets
 * worse. So the rules are stated as tests rather than trusted to review.
 */
class TapSwipeModelDurabilityTest {

    private val layout = "P:qwerty"
    private val now = 1_700_000_000_000L

    private lateinit var dir: File

    @Before
    fun setUp() {
        TapSwipeTouchModel.resetForTests()
        dir = File(
            System.getProperty("java.io.tmpdir"),
            "tapswipe-durability-${System.nanoTime()}"
        ).apply { mkdirs() }
    }

    private fun feed(cp: Int, n: Int) {
        repeat(n) { TapSwipeTouchModel.record(layout, cp, 0.02f, 0.01f, 1f, now) }
    }

    private fun modelFile() = File(dir, "tapswipe-touch-model.json")
    private fun backupFile() = File(dir, "tapswipe-touch-model.json.backup")

    /** Writes a model to disk and returns the process to a cold state, as a restart would. */
    private fun persistThenForget(samples: Int) {
        TapSwipeTouchModel.ensureLoaded(dir)
        feed('e'.code, samples)
        TapSwipeTouchModel.save(dir)
        TapSwipeTouchModel.resetForTests()
    }

    // ---------------------------------------------------------------- the destruction paths

    /**
     * The regression this file exists for. A read that fails for any reason other than "no file"
     * used to leave an empty model marked as loaded, which armed the next save to write that
     * emptiness over a file that may have been perfectly fine.
     */
    @Test
    fun `an unreadable model is never overwritten`() {
        persistThenForget(20)
        modelFile().writeText("{ this is not json")

        TapSwipeTouchModel.ensureLoaded(dir)
        feed('e'.code, 5)
        TapSwipeTouchModel.save(dir)

        assertTrue("writes must be blocked after a failed read",
            TapSwipeTouchModel.isWriteBlockedForTests())
        assertEquals("nothing may be written over an unreadable model",
            0, TapSwipeTouchModel.saveAttempts)
        assertEquals("the file on disk must be left exactly as it was",
            "{ this is not json", modelFile().readText())
    }

    /**
     * The same protection from the other direction: whatever emptied the model, if nobody asked for
     * a reset then the copy on disk is worth more than the copy in memory.
     */
    @Test
    fun `an empty model is not written over existing data`() {
        persistThenForget(20)

        TapSwipeTouchModel.ensureLoaded(dir)
        // Emptied by something other than reset() - a bug, or a bucket that never loaded.
        val before = modelFile().readText()
        repeat(20) { TapSwipeTouchModel.retract(layout, 'e'.code, 0.02f, 0.01f, 1f, now) }
        assertEquals("the model must actually be empty for this to test anything",
            0, TapSwipeTouchModel.totalSamples())

        TapSwipeTouchModel.save(dir)

        assertEquals("an unrequested empty model must not reach disk", before, modelFile().readText())
    }

    /** A deliberate reset is the one case where emptiness is the user's intent. */
    @Test
    fun `a reset does reach disk`() {
        persistThenForget(20)

        TapSwipeTouchModel.ensureLoaded(dir)
        TapSwipeTouchModel.reset()
        TapSwipeTouchModel.save(dir)

        assertEquals(1, TapSwipeTouchModel.saveAttempts)
        TapSwipeTouchModel.resetForTests()
        TapSwipeTouchModel.ensureLoaded(dir)
        assertEquals("the reset must survive a restart", 0, TapSwipeTouchModel.totalSamples())
    }

    /** And the authorisation it grants is spent, not left standing for the rest of the process. */
    @Test
    fun `a reset does not authorise later accidental emptying`() {
        persistThenForget(20)
        TapSwipeTouchModel.ensureLoaded(dir)
        TapSwipeTouchModel.reset()
        TapSwipeTouchModel.save(dir)

        feed('e'.code, 20)
        TapSwipeTouchModel.save(dir)
        val real = modelFile().readText()

        // Emptied again, this time without asking.
        TapSwipeTouchModel.retract(layout, 'e'.code, 0.02f, 0.01f, 20f, now)
        TapSwipeTouchModel.save(dir)

        assertEquals("the earlier reset must not still be authorising writes",
            real, modelFile().readText())
    }

    // ---------------------------------------------------------------- recovery

    @Test
    fun `a good model is backed up when it loads`() {
        persistThenForget(20)

        TapSwipeTouchModel.ensureLoaded(dir)

        assertTrue("loading a real model should snapshot it", backupFile().exists())
    }

    @Test
    fun `a missing model is recovered from the backup`() {
        persistThenForget(20)
        TapSwipeTouchModel.ensureLoaded(dir)   // writes the backup
        TapSwipeTouchModel.resetForTests()
        modelFile().delete()

        TapSwipeTouchModel.ensureLoaded(dir)

        assertTrue("learning should have been recovered", TapSwipeTouchModel.totalSamples() > 0)
        assertFalse("recovery from a clean backup should not block writes",
            TapSwipeTouchModel.isWriteBlockedForTests())
    }

    @Test
    fun `a corrupt model falls back to the backup but still refuses to write`() {
        persistThenForget(20)
        TapSwipeTouchModel.ensureLoaded(dir)
        TapSwipeTouchModel.resetForTests()
        modelFile().writeText("truncated{")

        TapSwipeTouchModel.ensureLoaded(dir)

        assertTrue("the backup should have been used", TapSwipeTouchModel.totalSamples() > 0)
        // The main file is still the newer copy and this process cannot tell what it holds, so
        // writing the backup's contents over it would silently discard the difference.
        assertTrue("writes must stay blocked", TapSwipeTouchModel.isWriteBlockedForTests())
    }

    @Test
    fun `an empty model is not backed up over a real one`() {
        persistThenForget(20)
        TapSwipeTouchModel.ensureLoaded(dir)
        val backup = backupFile().readText()
        TapSwipeTouchModel.resetForTests()

        // A fresh install state: no model file. This must not replace a real backup with nothing.
        modelFile().delete()
        TapSwipeTouchModel.ensureLoaded(dir)

        assertEquals("the backup must survive a first-run load", backup, backupFile().readText())
    }

    @Test
    fun `a first run writes nothing and blocks nothing`() {
        TapSwipeTouchModel.ensureLoaded(dir)

        assertFalse(TapSwipeTouchModel.isWriteBlockedForTests())
        assertEquals(0, TapSwipeTouchModel.totalSamples())

        feed('e'.code, 20)
        TapSwipeTouchModel.save(dir)
        assertEquals("a genuine first run must be able to save", 1, TapSwipeTouchModel.saveAttempts)
    }

    /** Re-reading is the retry a transient failure deserves, and clears the block when it works. */
    @Test
    fun `a successful re-read lifts the write block`() {
        persistThenForget(20)
        val good = modelFile().readText()
        modelFile().writeText("not json")
        TapSwipeTouchModel.ensureLoaded(dir)
        assertTrue(TapSwipeTouchModel.isWriteBlockedForTests())

        modelFile().writeText(good)
        TapSwipeTouchModel.resetForTests()
        TapSwipeTouchModel.ensureLoaded(dir)

        assertFalse(TapSwipeTouchModel.isWriteBlockedForTests())
        assertTrue(TapSwipeTouchModel.totalSamples() > 0)
    }

    // ---------------------------------------------------------------- visibility

    /**
     * Evidence under another layout key is not lost, and the settings screen needs to be able to
     * say so - a bucket the page is not looking at is indistinguishable from deletion otherwise.
     */
    @Test
    fun `samples are reported per layout bucket`() {
        TapSwipeTouchModel.ensureLoaded(dir)
        repeat(7) { TapSwipeTouchModel.record("P:abcdef", 'a'.code, 0.01f, 0f, 1f, now) }
        repeat(3) { TapSwipeTouchModel.record("L:abcdef", 'a'.code, 0.01f, 0f, 1f, now) }

        val byLayout = TapSwipeTouchModel.samplesByLayout()

        assertEquals(7, byLayout["P:abcdef"])
        assertEquals(3, byLayout["L:abcdef"])
        assertFalse("empty buckets are noise on that screen", byLayout.containsKey("P:unused"))
    }
}
