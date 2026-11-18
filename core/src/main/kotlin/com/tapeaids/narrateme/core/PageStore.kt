package com.tapeaids.narrateme.core

import java.io.File
import java.io.IOException

/** Stores the prototype's single page in [dir]. A new save replaces the old page. */
class PageStore(private val dir: File) {
    private val photoFile get() = File(dir, PHOTO_NAME)
    private val audioFile get() = File(dir, AUDIO_NAME)

    /** Saves [photo] and moves [recordedAudio] into the store. */
    fun save(photo: ByteArray, recordedAudio: File): Page {
        dir.mkdirs()
        val photoTmp = File(dir, "$PHOTO_NAME.tmp").apply { writeBytes(photo) }
        val audioTmp = File(dir, "$AUDIO_NAME.tmp")
        audioTmp.delete()
        if (!recordedAudio.renameTo(audioTmp)) {
            recordedAudio.copyTo(audioTmp, overwrite = true)
            recordedAudio.delete()
        }
        replace(photoTmp, photoFile)
        replace(audioTmp, audioFile)
        return Page(photoFile, audioFile)
    }

    fun load(): Page? =
        if (photoFile.isFile && audioFile.isFile) Page(photoFile, audioFile) else null

    private fun replace(from: File, to: File) {
        to.delete()
        if (!from.renameTo(to)) throw IOException("Could not move $from to $to")
    }

    companion object {
        const val PHOTO_NAME = "page.jpg"
        const val AUDIO_NAME = "narration.wav"
    }
}
