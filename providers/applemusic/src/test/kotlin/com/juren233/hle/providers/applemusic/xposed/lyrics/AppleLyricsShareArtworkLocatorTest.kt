/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsShareArtworkLocatorTest {
    private fun locate(image: BufferedImage, scale: Double = 1.0): AppleLyricsShareArtworkLocator.Box? {
        val width = (image.width * scale).toInt()
        val height = (image.height * scale).toInt()
        return AppleLyricsShareArtworkLocator.locate(width, height) { x, y ->
            val sx = ((x + 0.5) / scale).toInt().coerceAtMost(image.width - 1)
            val sy = ((y + 0.5) / scale).toInt().coerceAtMost(image.height - 1)
            image.getRGB(sx, sy) ushr 24
        }
    }

    /** Apple Music 7.0.0 (1614) card, as rendered on a real device. */
    private fun realCard(): BufferedImage = checkNotNull(
        javaClass.getResourceAsStream("/lyrics-share/apple-card-962x726.png"),
    ).use { ImageIO.read(it) }

    @Test
    fun `finds the artwork on a real Apple card at every scale`() {
        val card = realCard()
        listOf(0.5, 0.75, 1.0, 1.33).forEach { scale ->
            val box = locate(card, scale)
            assertNotNull("scale $scale", box)
            box!!
            val width = card.width * scale
            val height = card.height * scale
            // Same relative place at every size: bottom-left thumbnail, square, ~15 % of the width.
            assertEquals("left $scale", 0.064, box.left / width, 0.004)
            assertEquals("right $scale", 0.22, box.right / width, 0.004)
            assertEquals("bottom gap $scale", 0.064, (height - box.bottom) / width, 0.004)
            val side = (box.right - box.left).toDouble()
            assertEquals("square $scale", 1.0, (box.bottom - box.top) / side, 0.03)
            // Every sampled pixel is artwork: fully opaque.
            for (y in box.top until box.bottom) for (x in box.left until box.right) {
                val sx = ((x + 0.5) / scale).toInt().coerceAtMost(card.width - 1)
                val sy = ((y + 0.5) / scale).toInt().coerceAtMost(card.height - 1)
                assertTrue("opaque $scale", card.getRGB(sx, sy) ushr 24 >= 250)
            }
        }
    }

    private fun syntheticCard(
        width: Int,
        height: Int,
        bodyAlpha: Int = 51,
        artwork: IntArray? = intArrayOf(),
        textRuns: List<IntArray> = emptyList(),
    ): (Int, Int) -> Int {
        val side = width * 168 / 962
        val left = width * 52 / 962
        val top = height - width * 53 / 962 - side
        return { x, y ->
            val inArtwork = artwork != null && x in left until left + side && y in top until top + side
            val inText = textRuns.any { (x0, x1, y0, y1) -> x in x0 until x1 && y in y0 until y1 }
            if (inArtwork || inText) 255 else bodyAlpha
        }
    }

    @Test
    fun `card height does not shift the artwork`() {
        listOf(500, 726, 849, 914, 1400).forEach { height ->
            val box = AppleLyricsShareArtworkLocator.locate(962, height, syntheticCard(962, height))
            assertNotNull("height $height", box)
            // Thumbnail top (bottom-anchored) plus the 6 % inset.
            assertEquals("height $height", (height - 53 - 168 + 10).toDouble(), box!!.top.toDouble(), 2.0)
            assertEquals("height $height", (52 + 10).toDouble(), box.left.toDouble(), 2.0)
        }
    }

    @Test
    fun `text strokes and thin rules are not taken for the artwork`() {
        // A long underline-like rule and short bold glyph runs, but no artwork.
        val runs = listOf(
            intArrayOf(50, 900, 100, 110),
            intArrayOf(50, 120, 200, 260),
            intArrayOf(140, 210, 200, 260),
        )
        assertNull(AppleLyricsShareArtworkLocator.locate(962, 726, syntheticCard(962, 726, artwork = null, textRuns = runs)))
    }

    @Test
    fun `an all-opaque card is rejected`() {
        assertNull(AppleLyricsShareArtworkLocator.locate(962, 900, syntheticCard(962, 900, bodyAlpha = 255)))
    }
}
