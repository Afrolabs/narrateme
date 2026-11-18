package com.tapeaids.narrateme.core

import java.io.File
import java.io.IOException

/** Stores narrated pages in [dir], one sub-folder per page holding its photo and narration. */
class PageStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    /** Saves a new page from [photo] and moves [recordedAudio] into the store. */
    fun save(photo: ByteArray, recordedAudio: File): Page {
        dir.mkdirs()
        var stamp = clock()
        while (File(dir, stamp.toString()).exists()) stamp++
        val id = stamp.toString()
        // Build the page in a temp folder and rename it, so a half-written page is never listed.
        val tmp = File(dir, "$id.tmp").apply { deleteRecursively(); mkdirs() }
        File(tmp, PHOTO_NAME).writeBytes(photo)
        val audio = File(tmp, AUDIO_NAME)
        if (!recordedAudio.renameTo(audio)) {
            recordedAudio.copyTo(audio, overwrite = true)
            recordedAudio.delete()
        }
        val final = File(dir, id)
        if (!tmp.renameTo(final)) throw IOException("Could not move $tmp to $final")
        return pageIn(final)!!
    }

    /** All complete pages, oldest first. */
    fun list(): List<Page> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.endsWith(".tmp") }
            .sortedBy { it.name.toLongOrNull() ?: Long.MAX_VALUE }
            .mapNotNull(::pageIn)

    fun clear() {
        dir.deleteRecursively()
    }

    private fun pageIn(folder: File): Page? {
        val photo = File(folder, PHOTO_NAME)
        val audio = File(folder, AUDIO_NAME)
        return if (photo.isFile && audio.isFile) Page(folder.name, photo, audio) else null
    }

    companion object {
        const val PHOTO_NAME = "page.jpg"
        const val AUDIO_NAME = "narration.wav"
    }
}
