package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.Pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paged cards (0.38, DESIGN.md "การ์ดหลายหน้า"): which page shows, and what is unseen. */
class PagesTest {

    private val minute = 60_000L
    private fun gold() = Pages(listOf("gold", "oil"))

    @Test
    fun `the first page shows until something says otherwise`() {
        val p = gold()
        assertEquals("gold", p.current)
        assertEquals(1, p.index("oil"))
    }

    @Test
    fun `the first prices of a page are not news`() {
        val p = gold()
        assertFalse(p.news("oil", "o1", 0))
        assertEquals("gold", p.current)
        assertFalse(p.hasUnseen("oil"))
    }

    @Test
    fun `news on a page shows that page`() {
        val p = gold()
        p.news("oil", "o1", 0)
        assertTrue(p.news("oil", "o2", 10 * minute))
        assertEquals("oil", p.current)
        assertFalse(p.hasUnseen("oil"))
    }

    @Test
    fun `a finger's choice holds for two minutes and the news waits as unseen`() {
        val p = gold()
        p.news("oil", "o1", 0)
        p.choose("gold", 5 * minute)
        p.news("oil", "o2", 6 * minute)
        assertEquals("gold stays under the finger", "gold", p.current)
        assertTrue("the fuel tab says ใหม่", p.hasUnseen("oil"))
        p.choose("oil", 6 * minute + 1)
        assertFalse("seen once shown", p.hasUnseen("oil"))
    }

    @Test
    fun `after the two minutes news turns the page again`() {
        val p = gold()
        p.news("oil", "o1", 0)
        p.choose("gold", 0)
        p.news("oil", "o2", 3 * minute)
        assertEquals("oil", p.current)
    }

    @Test
    fun `the same signature again is not news`() {
        val p = gold()
        p.news("gold", "g1", 0)
        p.news("gold", "g2", minute)
        p.choose("oil", 2 * minute)
        assertFalse(p.news("gold", "g2", 3 * minute))
        assertEquals("oil", p.current)
    }

    @Test
    fun `a swipe steps one page and stops at the ends`() {
        val p = Pages(listOf("a", "b", "c"))
        p.step(1, 0); assertEquals("b", p.current)
        p.step(1, 0); p.step(1, 0); assertEquals("c", p.current)
        p.step(-5, 0); assertEquals("a", p.current)
    }

    @Test
    fun `showing a page when another goes away is not a finger's choice`() {
        val p = gold()
        p.show("oil")
        p.news("gold", "g1", 0)
        assertTrue(p.news("gold", "g2", 1))
        assertEquals("news still turns the page: nobody touched it", "gold", p.current)
    }

    @Test
    fun `unknown pages are ignored and a card needs a page`() {
        val p = gold()
        assertFalse(p.news("nope", "x", 0))
        p.choose("nope", 0)
        assertEquals("gold", p.current)
        try {
            Pages(emptyList()); throw AssertionError("expected a refusal")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
