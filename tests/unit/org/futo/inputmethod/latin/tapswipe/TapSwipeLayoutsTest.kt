package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TapSwipeLayoutsTest {

    @Test
    fun `qwerty becomes the apostrophe layout when the setting is on`() {
        assertEquals("qwerty_nintype", TapSwipeLayouts.effectiveLayoutName("qwerty", true))
    }

    @Test
    fun `qwerty stays qwerty when the setting is off`() {
        assertEquals("qwerty", TapSwipeLayouts.effectiveLayoutName("qwerty", false))
    }

    @Test
    fun `a subtype that names the apostrophe layout follows the setting`() {
        assertEquals("qwerty_nintype", TapSwipeLayouts.effectiveLayoutName("qwerty_nintype", true))
        assertEquals("qwerty", TapSwipeLayouts.effectiveLayoutName("qwerty_nintype", false))
    }

    @Test
    fun `other layouts are never swapped`() {
        for (name in listOf("qwertz", "dvorak", "colemak", "pcqwerty", "custom0")) {
            assertEquals(name, TapSwipeLayouts.effectiveLayoutName(name, true))
            assertEquals(name, TapSwipeLayouts.effectiveLayoutName(name, false))
        }
    }

    @Test
    fun `migration finds the apostrophe layout in stored subtypes`() {
        assertTrue(TapSwipeLayouts.subtypesUseApostropheLayout(
            setOf("en_US:KeyboardLayoutSet=qwerty_nintype:")))
        assertFalse(TapSwipeLayouts.subtypesUseApostropheLayout(
            setOf("en_US:KeyboardLayoutSet=qwerty:", "de_DE:KeyboardLayoutSet=qwertz:")))
        assertFalse(TapSwipeLayouts.subtypesUseApostropheLayout(emptySet()))
    }

    @Test
    fun `the swapped-in layout file is in this repository, not in a submodule`() {
        val file = File("java/assets-tapswipe/layouts/TapSwipe/${TapSwipeLayouts.QWERTY_APOSTROPHE}.yaml")
        assertTrue("missing ${file.path}", file.exists())
    }
}
