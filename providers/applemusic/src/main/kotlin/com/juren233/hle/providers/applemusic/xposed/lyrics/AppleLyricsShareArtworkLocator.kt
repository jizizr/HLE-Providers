/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

/**
 * Finds the artwork thumbnail inside Apple's rendered lyric card by content, not by coordinates.
 *
 * Apple Music 7.0.0 (1614) renders the card body as a translucent overlay (alpha ≈ 51) with
 * opaque white text; the artwork is the only solid, roughly square block of fully opaque pixels.
 * Verified on three real cards (962×726, 962×849, 962×914) and on each scaled to 0.5×–1.33×: the
 * block was found at the same relative place, 100 % opaque, matching the artwork colours.
 * Pure logic over an alpha lookup so it runs in JVM unit tests.
 */
internal object AppleLyricsShareArtworkLocator {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Alpha at or above this counts as opaque. */
    private const val OPAQUE_ALPHA = 250

    /** Shortest opaque run, as a share of the card width; text glyph runs stay below it. */
    private const val MIN_RUN_FRACTION = 0.10

    /** Longest accepted block width; an all-opaque card must not be taken for the artwork. */
    private const val MAX_RUN_FRACTION = 0.40

    private const val MIN_ASPECT = 0.8
    private const val MAX_ASPECT = 1.25

    /** Trimmed from each side so rounded corners and the hairline border are not sampled. */
    private const val INSET_FRACTION = 0.06

    fun locate(width: Int, height: Int, alpha: (x: Int, y: Int) -> Int): Box? {
        if (width <= 0 || height <= 0) return null
        val minRun = maxOf(8, (width * MIN_RUN_FRACTION).toInt())
        val maxRun = (width * MAX_RUN_FRACTION).toInt()

        var best: Box? = null
        var bestArea = 0L
        var blockTop = -1
        val runs = ArrayList<IntArray>()
        var previous: IntArray? = null

        fun closeBlock(endY: Int) {
            if (blockTop < 0 || runs.isEmpty()) return
            // The median run is the full-width core; rounded corners shorten the edge rows.
            val core = runs.sortedBy { it[1] - it[0] }[runs.size / 2]
            val blockWidth = core[1] - core[0]
            val blockHeight = endY - blockTop
            val aspect = blockHeight.toDouble() / blockWidth
            if (blockWidth in minRun..maxRun && aspect in MIN_ASPECT..MAX_ASPECT) {
                val area = blockWidth.toLong() * blockHeight
                if (area > bestArea) {
                    bestArea = area
                    val inset = maxOf(1, (blockWidth * INSET_FRACTION).toInt())
                    best = Box(core[0] + inset, blockTop + inset, core[1] - inset, endY - inset)
                }
            }
            blockTop = -1
            runs.clear()
        }

        for (y in 0 until height) {
            val run = longestOpaqueRun(y, width, alpha)
            val qualifies = run != null && run[1] - run[0] >= minRun
            val continues = qualifies && previous != null &&
                minOf(run!![1], previous!![1]) - maxOf(run[0], previous!![0]) >= minRun
            if (qualifies && continues) {
                runs += run!!
            } else {
                closeBlock(y)
                if (qualifies) {
                    blockTop = y
                    runs += run!!
                }
            }
            previous = if (qualifies) run else null
        }
        closeBlock(height)
        return best?.takeIf { it.right > it.left && it.bottom > it.top }
    }

    /** [start, end) of the longest run of opaque pixels in row [y], or null when none. */
    private fun longestOpaqueRun(y: Int, width: Int, alpha: (Int, Int) -> Int): IntArray? {
        var bestStart = 0
        var bestEnd = 0
        var start = -1
        for (x in 0..width) {
            val opaque = x < width && alpha(x, y) >= OPAQUE_ALPHA
            if (opaque && start < 0) start = x
            if (!opaque && start >= 0) {
                if (x - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = x
                }
                start = -1
            }
        }
        return if (bestEnd > bestStart) intArrayOf(bestStart, bestEnd) else null
    }
}
