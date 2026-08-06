package org.futo.inputmethod.v2keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the Nintype-style apostrophe-key layout parses to the structure it is supposed to,
 * without needing a device. `parseKeyboardYamlString` has no Android dependency, so this exercises
 * the exact deserialization path `LayoutManager` uses at runtime - it is not a hand-rolled parser.
 *
 * `Keyboard.rows` is private; `getEffectiveRows(numberRowMode)` is the only accessor, and it injects
 * a number row at index 0 and adds Shift/Delete to the last declared letter row (this layout defines
 * neither), so the indices below are the *effective* ones, not the three rows written in the YAML.
 *
 * This is a parse-and-shape check, not a rendering one: it cannot see pixels, hitboxes, or how the
 * key feels under a thumb. That still has to be judged on a device.
 */
class QwertyNintypeLayoutTest {

    private fun loadYaml(): String {
        val path = "java/assets/layouts/Default/qwerty_nintype.yaml"
        val file = File(path)
        require(file.exists()) { "expected to find $path relative to the module directory" }
        return file.readText()
    }

    private fun effectiveRows() =
        parseKeyboardYamlString(loadYaml()).getEffectiveRows(numberRowMode = 0)

    @Test
    fun `the layout parses without throwing`() {
        effectiveRows()
    }

    @Test
    fun `the number row is injected and the bottom row is the default`() {
        val rows = effectiveRows()
        assertTrue(rows.first().isNumberRow)
        assertTrue(rows.last().isBottomRow)
    }

    @Test
    fun `the top row is untouched stock qwerty`() {
        // rows[0] = injected number row, rows[1] = q w e r t y u i o p as declared.
        assertEquals(10, effectiveRows()[1].keys.size)
    }

    @Test
    fun `the home row gains exactly one key over stock qwerty`() {
        // rows[2] = a s d f g h j k l (9 letters), plus the apostrophe entry = 10.
        assertEquals(10, effectiveRows()[2].keys.size)
    }

    @Test
    fun `the apostrophe key is last on the home row, thin, and anchored to the edge`() {
        val homeRow = effectiveRows()[2]
        val last = homeRow.keys.last()

        assertTrue("expected the last home-row key to be a BaseKey, was ${last::class}",
            last is BaseKey)
        val key = last as BaseKey

        assertEquals("'", key.spec)
        assertEquals("'", key.swipeLetter)
        assertEquals(KeyWidth.Custom1, key.attributes.width)
        assertEquals(true, key.attributes.anchored)
    }

    @Test
    fun `Custom1 is declared and sized to fill the existing margin, not add a new one`() {
        val custom1 = parseKeyboardYamlString(loadYaml()).overrideWidths[KeyWidth.Custom1]

        assertTrue("Custom1 must be declared", custom1 != null)
        // Stock QWERTY's home row (9 keys at 10% each) already leaves a 10% margin, split 5%/5%
        // between the two edges. 0.05 exactly consumes the right half of that, so every other key
        // on the row keeps the position it has today.
        assertEquals(0.05f, custom1!!, 1e-6f)
    }

    @Test
    fun `the bottom letter row gets shift and delete added, same as stock qwerty would`() {
        // rows[3] = z x c v b n m (7 letters), with $shift prepended and $delete appended by
        // getEffectiveRows - the same thing that would happen to stock qwerty.yaml's identical row,
        // so this is confirming nothing here is special-cased away.
        assertEquals(9, effectiveRows()[3].keys.size)
    }

    /**
     * Guards against a change here silently reintroducing what motivated the whole design: adding a
     * plain 10th home-row key would have made every letter one column narrower, since
     * `computeRegularKeyWidth` divides by the widest letter row's key count. The apostrophe key must
     * stay off that count by not using Regular width.
     */
    @Test
    fun `the apostrophe key does not change the row that regular width is computed from`() {
        val apostrophe = effectiveRows()[2].keys.last() as BaseKey

        assertTrue("the apostrophe key must not use Regular width, or it would count toward " +
                "computeRegularKeyWidth's key-count denominator and shrink every letter key",
            apostrophe.attributes.width != KeyWidth.Regular)
    }
}
