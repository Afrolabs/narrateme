package com.tapeaids.narrateme.core

import java.io.File

/** A narrated page: the narrator's photo of it and their recording. */
data class Page(val id: String, val photo: File, val audio: File)

/** How well a new photo matched one stored page. Higher is better; 0 means no match. */
data class PageScore(val page: Page, val score: Int)

/**
 * Picks the page a photo shows from per-page scores, or null if none is convincing.
 * The best score must reach [minScore] and beat the runner-up by [margin]x, so that
 * two similar pages don't produce a coin-flip.
 */
fun pickMatch(scores: List<PageScore>, minScore: Int = 25, margin: Double = 1.5): Page? {
    val sorted = scores.sortedByDescending { it.score }
    val best = sorted.firstOrNull() ?: return null
    if (best.score < minScore) return null
    val runnerUp = sorted.getOrNull(1)?.score ?: 0
    return if (best.score >= runnerUp * margin) best.page else null
}
