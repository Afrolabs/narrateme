package com.tapeaids.narrateme.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PageStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun recording(bytes: ByteArray): File =
        tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun `load is null when nothing is stored`() {
        assertNull(PageStore(tmp.newFolder()).load())
    }

    @Test
    fun `save then load round-trips photo and audio`() {
        val store = PageStore(tmp.newFolder())
        val audio = recording(byteArrayOf(9, 8, 7))
        val saved = store.save(byteArrayOf(1, 2), audio)
        val loaded = store.load()!!
        assertEquals(saved, loaded)
        assertArrayEquals(byteArrayOf(1, 2), loaded.photo.readBytes())
        assertArrayEquals(byteArrayOf(9, 8, 7), loaded.audio.readBytes())
        assertFalse("recording should be moved, not copied", audio.exists())
    }

    @Test
    fun `second save replaces both files`() {
        val store = PageStore(tmp.newFolder())
        store.save(byteArrayOf(1), recording(byteArrayOf(1)))
        store.save(byteArrayOf(2, 2), recording(byteArrayOf(3, 3, 3)))
        val page = store.load()!!
        assertArrayEquals(byteArrayOf(2, 2), page.photo.readBytes())
        assertArrayEquals(byteArrayOf(3, 3, 3), page.audio.readBytes())
    }

    @Test
    fun `load is null when only the photo exists`() {
        val dir = tmp.newFolder()
        File(dir, PageStore.PHOTO_NAME).writeBytes(byteArrayOf(1))
        assertNull(PageStore(dir).load())
    }

    @Test
    fun `single page matcher returns whatever is stored`() {
        val store = PageStore(tmp.newFolder())
        val matcher = SinglePageMatcher(store)
        assertNull(matcher.match(byteArrayOf(0)))
        val page = store.save(byteArrayOf(1), recording(byteArrayOf(2)))
        assertEquals(page, matcher.match(byteArrayOf(42)))
    }
}
