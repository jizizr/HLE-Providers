package com.juren233.hle.providers.applemusic.xposed.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsShareCardComposerTest {
    @Test
    fun `layout keeps card centred and QR inside the footer margin`() {
        val spec = AppleLyricsShareCardComposer.layout(cardWidth = 962, cardHeight = 800, withQr = true)

        assertEquals(spec.cardLeft, spec.width - spec.cardLeft - 962)
        assertEquals(spec.padding, spec.width - spec.qrLeft - spec.qrTile)
        assertEquals(spec.padding, spec.height - spec.qrTop - spec.qrTile)
        assertTrue(spec.qrTop > spec.cardTop + 800)
        assertTrue(spec.textRight > spec.textLeft)
        assertTrue(spec.qrTile - spec.qrInset * 2 >= 150)
    }

    @Test
    fun `layout without QR only frames the card`() {
        val spec = AppleLyricsShareCardComposer.layout(cardWidth = 1000, cardHeight = 600, withQr = false)

        assertEquals(600 + spec.padding * 2, spec.height)
        assertEquals(1000 + spec.padding * 2, spec.width)
    }

    @Test
    fun `layout scales with render density`() {
        val small = AppleLyricsShareCardComposer.layout(500, 500, withQr = true)
        val large = AppleLyricsShareCardComposer.layout(1000, 1000, withQr = true)

        assertEquals(small.qrTile * 2f, large.qrTile.toFloat(), 2f)
        assertEquals(small.titleSize * 2f, large.titleSize, 0.01f)
    }

    @Test
    fun `palette darkens the card colour and keeps readable text`() {
        val lightGrey = 0xFFCCCCCC.toInt()
        val (background, foreground) = AppleLyricsShareCardComposer.palette(lightGrey)

        assertTrue(AppleLyricsShareCardComposer.luminance(background) < AppleLyricsShareCardComposer.luminance(lightGrey))
        assertEquals(0xFFFFFFFF.toInt(), foreground)
    }
}
