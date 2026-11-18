package com.tapeaids.narrateme.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `list is empty when nothing is stored`() {
        assertEquals(emptyList<Page>(), PageStore(File(tmp.root, "missing")).list())
    }

    @Test
    fun `save then list round-trips photo and audio`() {
        val store = PageStore(tmp.newFolder())
        val audio = recording(byteArrayOf(9, 8, 7))
        val saved = store.save(byteArrayOf(1, 2), audio)
        val loaded = store.list().single()
        assertEquals(saved, loaded)
        assertArrayEquals(byteArrayOf(1, 2), loaded.photo.readBytes())
        assertArrayEquals(byteArrayOf(9, 8, 7), loaded.audio.readBytes())
        assertFalse("recording should be moved, not copied", audio.exists())
    }

    @Test
    fun `each save adds a page, oldest first, even within the same millisecond`() {
        val store = PageStore(tmp.newFolder(), clock = { 1000L })
        val first = store.save(byteArrayOf(1), recording(byteArrayOf(1)))
        val second = store.save(byteArrayOf(2, 2), recording(byteArrayOf(3, 3, 3)))
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(first, second), store.list())
        assertArrayEquals(byteArrayOf(2, 2), store.list()[1].photo.readBytes())
    }

    @Test
    fun `incomplete and temporary folders are not listed`() {
        val dir = tmp.newFolder()
        File(dir, "5").mkdirs()
        File(dir, "5/${PageStore.PHOTO_NAME}").writeBytes(byteArrayOf(1))
        File(dir, "6.tmp").mkdirs()
        assertEquals(emptyList<Page>(), PageStore(dir).list())
    }

    @Test
    fun `clear removes every page`() {
        val store = PageStore(tmp.newFolder())
        store.save(byteArrayOf(1), recording(byteArrayOf(1)))
        store.clear()
        assertEquals(emptyList<Page>(), store.list())
    }
}
