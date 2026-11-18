package com.tapeaids.narrateme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PickMatchTest {
    private fun page(id: String) = Page(id, File("$id.jpg"), File("$id.wav"))
    private val a = page("a")
    private val b = page("b")

    @Test
    fun `no pages means no match`() {
        assertNull(pickMatch(emptyList()))
    }

    @Test
    fun `a clear winner above the minimum is picked`() {
        assertEquals(a, pickMatch(listOf(PageScore(b, 10), PageScore(a, 80))))
    }

    @Test
    fun `a single page must still reach the minimum`() {
        assertNull(pickMatch(listOf(PageScore(a, 24))))
        assertEquals(a, pickMatch(listOf(PageScore(a, 25))))
    }

    @Test
    fun `two close scores are too ambiguous to pick`() {
        assertNull(pickMatch(listOf(PageScore(a, 60), PageScore(b, 50))))
    }
}
