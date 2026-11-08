package com.juren233.hle.providers.applemusic.xposed.lyrics

import com.juren233.hyperlyricsenhanced.contract.AppleLyricsSharePoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsShareCardComposerTest {
    @Test
    fun `layout keeps card centred and QR inside the footer margin`() {
        val spec = AppleLyricsSharePoster.layout(cardWidth = 962, cardHeight = 800, withQr = true)

        assertEquals(spec.cardLeft, spec.width - spec.cardLeft - 962)
        assertEquals(spec.padding, spec.width - spec.qrLeft - spec.qrTile)
        assertEquals(spec.padding, spec.height - spec.qrTop - spec.qrTile)
        assertTrue(spec.qrTop > spec.cardTop + 800)
        assertTrue(spec.textRight > spec.textLeft)
        assertTrue(spec.qrTile - spec.qrInset * 2 >= 150)
    }

    @Test
    fun `layout without QR only frames the card`() {
        val spec = AppleLyricsSharePoster.layout(cardWidth = 1000, cardHeight = 600, withQr = false)

        assertEquals(600 + spec.padding * 2, spec.height)
        assertEquals(1000 + spec.padding * 2, spec.width)
    }

    @Test
    fun `layout scales with render density`() {
        val small = AppleLyricsSharePoster.layout(500, 500, withQr = true)
        val large = AppleLyricsSharePoster.layout(1000, 1000, withQr = true)

        assertEquals(small.qrTile * 2f, large.qrTile.toFloat(), 2f)
        assertEquals(small.titleSize * 2f, large.titleSize, 0.01f)
    }

    @Test
    fun `palette darkens the card colour and keeps readable text`() {
        val lightGrey = 0xFFCCCCCC.toInt()
        val background = AppleLyricsSharePoster.coverColorBackground(lightGrey)
        val foreground = AppleLyricsSharePoster.foregroundFor(background)

        assertTrue(AppleLyricsSharePoster.relativeLuminance(background) < AppleLyricsSharePoster.relativeLuminance(lightGrey))
        assertEquals(0xFFFFFFFF.toInt(), foreground)
    }

    @Test
    fun `foreground picks the higher contrast colour over the backdrop`() {
        assertEquals(0xFFFFFFFF.toInt(), AppleLyricsSharePoster.foregroundFor(0xFF202830.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), AppleLyricsSharePoster.foregroundFor(0xFF7A3B55.toInt()))
        assertEquals(0xFF1C1C1E.toInt(), AppleLyricsSharePoster.foregroundFor(0xFFF2E6B8.toInt()))
        assertEquals(0xFF1C1C1E.toInt(), AppleLyricsSharePoster.foregroundFor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `cover crop trims the overflow of a tall backdrop around its centre`() {
        // 1200x2400 story background into a 1078x1250 poster: full width, middle band of rows.
        val crop = AppleLyricsSharePoster.coverCrop(1200, 2400, 1078, 1250)

        assertEquals(0, crop.left)
        assertEquals(1200, crop.right)
        assertTrue(kotlin.math.abs(crop.top - (2400 - crop.bottom)) <= 1)
        assertEquals(1078f / 1250f, (crop.right - crop.left).toFloat() / (crop.bottom - crop.top), 0.002f)
    }

    @Test
    fun `cover crop trims the sides of a wide backdrop and keeps matching ratios whole`() {
        val wide = AppleLyricsSharePoster.coverCrop(2000, 1000, 500, 500)
        assertEquals(0, wide.top)
        assertEquals(1000, wide.bottom)
        assertEquals(wide.left, 2000 - wide.right)
        assertEquals(1000, wide.right - wide.left)

        val same = AppleLyricsSharePoster.coverCrop(1200, 2400, 600, 1200)
        assertEquals(AppleLyricsSharePoster.Crop(0, 0, 1200, 2400), same)
    }

    @Test
    fun `cover crop leaves degenerate sizes uncropped`() {
        assertEquals(
            AppleLyricsSharePoster.Crop(0, 0, 100, 200),
            AppleLyricsSharePoster.coverCrop(100, 200, 0, 50),
        )
    }

    @Test
    fun `footer text is sampled beside the QR tile`() {
        val spec = AppleLyricsSharePoster.layout(cardWidth = 962, cardHeight = 874, withQr = true)
        val area = AppleLyricsSharePoster.footerTextSampleArea(spec)

        assertEquals(spec.textLeft, area.left)
        assertEquals(spec.textRight, area.right)
        assertTrue(area.right <= spec.qrLeft)
        assertEquals(spec.qrTile, area.bottom - area.top)
    }

    @Test
    fun `footer text follows the host language`() {
        assertEquals("Scan to listen", AppleLyricsSharePoster.qrTitle(chinese = false))
        assertEquals("Open in Apple Music", AppleLyricsSharePoster.qrSubtitle(chinese = false))
        assertEquals("扫码收听完整歌曲", AppleLyricsSharePoster.qrTitle(chinese = true))
    }
}
