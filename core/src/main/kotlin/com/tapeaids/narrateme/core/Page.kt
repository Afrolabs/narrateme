package com.tapeaids.narrateme.core

import java.io.File

/** A narrated page: the narrator's photo of it and their recording. */
data class Page(val photo: File, val audio: File)

/** Decides which stored page, if any, a new photo shows. */
fun interface PageMatcher {
    fun match(photo: ByteArray): Page?
}

/**
 * Placeholder for page recognition: there is only ever one page, so any photo
 * matches it. Replace with real image matching later.
 */
class SinglePageMatcher(private val store: PageStore) : PageMatcher {
    override fun match(photo: ByteArray): Page? = store.load()
}
