package org.futo.inputmethod.latin.tapswipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the list of things a direction can be assigned.
 *
 * The failures worth catching here are all silent ones. A duplicate id means one option shadows
 * another; a default naming an option that does not exist means that direction quietly does nothing
 * on a fresh install, and looks like a bug in gesture detection rather than a typo in a list.
 */
class EnterFlickCatalogTest {

    @Test
    fun `ids are unique`() {
        val ids = EnterFlickCatalog.ALL.map { it.id }
        assertEquals("duplicate option id", ids.size, ids.toSet().size)
    }

    @Test
    fun `every default names a real option`() {
        EnterFlicks.DEFAULTS.forEach { (direction, id) ->
            assertNotNull("default for $direction names unknown option '$id'",
                EnterFlickCatalog.option(id))
        }
    }

    @Test
    fun `the defaults are what was intended`() {
        assertEquals("?", EnterFlickCatalog.option(
            EnterFlicks.DEFAULTS[org.futo.inputmethod.v2keyboard.Direction.North])?.text)
        assertEquals("!", EnterFlickCatalog.option(
            EnterFlicks.DEFAULTS[org.futo.inputmethod.v2keyboard.Direction.South])?.text)
        assertEquals("(", EnterFlickCatalog.option(
            EnterFlicks.DEFAULTS[org.futo.inputmethod.v2keyboard.Direction.West])?.text)
        assertEquals(")", EnterFlickCatalog.option(
            EnterFlicks.DEFAULTS[org.futo.inputmethod.v2keyboard.Direction.East])?.text)
    }

    /** Every option needs something to show, or it is an unlabelled row in the picker. */
    @Test
    fun `every option can be displayed`() {
        EnterFlickCatalog.ALL.forEach {
            assertTrue("option '${it.id}' has neither text nor a name",
                it.text != null || it.nameRes != null)
        }
    }

    @Test
    fun `every option produces a key`() {
        EnterFlickCatalog.ALL.forEach {
            assertTrue("option '${it.id}' has an empty spec", it.toKey().spec.isNotEmpty())
        }
    }

    /**
     * The catalog is what [EnterFlicks.parse] validates against, so the two must be talking about
     * the same set - otherwise a stored assignment would be dropped as unknown.
     */
    @Test
    fun `known ids match the catalog`() {
        assertEquals(EnterFlickCatalog.ALL.map { it.id }.toSet(), EnterFlickCatalog.KNOWN_IDS)
    }
}
