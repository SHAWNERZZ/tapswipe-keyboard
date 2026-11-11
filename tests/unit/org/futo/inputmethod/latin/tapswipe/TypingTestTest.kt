package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.futo.inputmethod.latin.tapswipe.TypingTestScore.Kind
import java.io.File

class TypingTestJsonTest {
    @Test
    fun `builds one line with typed values`() {
        val s = TypingTestJson().put("a", "x").put("n", 3).put("b", true).put("f", 1.5f)
            .putInts("i", intArrayOf(1, 2)).putStrings("s", listOf("p", "q")).build()
        assertEquals("""{"a":"x","n":3,"b":true,"f":1.5,"i":[1,2],"s":["p","q"]}""", s)
    }

    @Test
    fun `escapes quotes, backslashes, and control characters`() {
        val s = TypingTestJson().put("t", "a\"b\\c\nd\u0001").build()
        assertEquals("""{"t":"a\"b\\c\nd\u0001"}""", s)
    }

    @Test
    fun `rounds floats to two decimals and writes whole numbers plainly`() {
        assertEquals("""{"x":[1.23,2,-0.5]}""",
            TypingTestJson().putFloats("x", floatArrayOf(1.2345f, 2.0f, -0.5f)).build())
    }

    @Test
    fun `null string and raw values`() {
        assertEquals("""{"a":null,"r":{"k":1}}""",
            TypingTestJson().put("a", null as String?).putRaw("r", """{"k":1}""").build())
    }
}

class TypingTestScoreTest {
    @Test
    fun `word list splits phrases and skips comments`() {
        val words = TypingTestScore.parseWordList("# note\nsee me\n\n  this   is \n#x\nhello")
        assertEquals(listOf("see", "me", "this", "is", "hello"), words)
    }

    @Test
    fun `the word under the cursor is not finished`() {
        assertEquals(0, TypingTestScore.finishedWordCount(""))
        assertEquals(0, TypingTestScore.finishedWordCount("hel"))
        assertEquals(1, TypingTestScore.finishedWordCount("hello "))
        assertEquals(1, TypingTestScore.finishedWordCount("hello wor"))
        assertEquals(2, TypingTestScore.finishedWordCount("hello world\n"))
    }

    @Test
    fun `comparison ignores case and end punctuation`() {
        assertTrue(TypingTestScore.same("I", "i"))
        assertTrue(TypingTestScore.same("this", "This."))
        assertFalse(TypingTestScore.same("this", "these"))
    }

    @Test
    fun `a fused pair costs one word, not every word after it`() {
        val a = TypingTestScore.align(listOf("see", "me", "now", "please"), listOf("seem", "now", "please"))
        assertEquals(2, a.count { it.kind == Kind.MATCH })
        assertEquals(2, a.count { it.kind != Kind.MATCH })
    }

    @Test
    fun `a split word costs one word, not every word after it`() {
        val a = TypingTestScore.align(listOf("area", "is", "big"), listOf("are", "a", "is", "big"))
        assertEquals(2, a.count { it.kind == Kind.MATCH })
        assertEquals("is", a.last { it.kind == Kind.MATCH && it.prompt == "is" }.typed)
    }

    @Test
    fun `totals count right words and wpm`() {
        // 2 right words: "the" + space and "cat" + space = 8 chars = 1.6 words, in 6 seconds = 16 wpm.
        val t = TypingTestScore.totals(listOf("the", "cat", "sat"), "The cat sad ", 6000)
        assertEquals(2, t.correctWords)
        assertEquals(1, t.wrongWords)
        assertEquals(16.0, t.wpm, 0.001)
        assertEquals(2.0 / 3, t.accuracy, 0.001)
    }

    @Test
    fun `every word list in the assets parses to words`() {
        val dir = File("java/assets-tapswipe/typing-tests")
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }!!
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val words = TypingTestScore.parseWordList(f.readText())
            assertTrue("${f.name} is empty", words.isNotEmpty())
            assertTrue("${f.name} has a non-word", words.all { w -> w.all { it.isLetter() || it == '\'' } })
        }
    }
}

class TypingTestRecorderIdTest {
    @Test
    fun `reads the id from private ime options`() {
        val m = TypingTestRecorder.markerFor("typingtest-1")
        assertEquals("typingtest-1", TypingTestRecorder.idFrom(m))
        assertEquals("typingtest-1", TypingTestRecorder.idFrom("other.option=1,$m"))
        assertNull(TypingTestRecorder.idFrom(null))
        assertNull(TypingTestRecorder.idFrom("nothing here"))
    }

    @Test
    fun `rejects ids that are not safe file names`() {
        assertNull(TypingTestRecorder.idFrom(TypingTestRecorder.MARKER + "=../evil"))
        assertNull(TypingTestRecorder.idFrom(TypingTestRecorder.MARKER + "="))
        assertFalse(TypingTestRecorder.isSafeId("a/b"))
        assertTrue(TypingTestRecorder.isSafeId("typingtest-20261111-120000-common-100"))
    }
}
