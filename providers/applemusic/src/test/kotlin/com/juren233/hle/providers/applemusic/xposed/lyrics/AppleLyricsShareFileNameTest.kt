/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsShareFileNameTest {
    private val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261108_213045")!!.time

    @Test
    fun `title artist and local time`() {
        assertEquals("像风一样-薛之谦-20261108_213045.jpg", AppleLyricsShareFileName.build("像风一样", "薛之谦", time, "jpg"))
    }

    @Test
    fun `missing artist is left out`() {
        assertEquals("YUKON-20261108_213045.png", AppleLyricsShareFileName.build("YUKON", "  ", time, "png"))
    }

    @Test
    fun `missing title keeps the generic name`() {
        assertEquals("Apple Music Lyrics-20261108_213045.jpg", AppleLyricsShareFileName.build(null, null, time, "jpg"))
    }

    @Test
    fun `invalid file name characters are replaced`() {
        assertEquals(
            "AC_DC_ Live - Remastered-A_B-20261108_213045.jpg",
            AppleLyricsShareFileName.build("AC/DC: Live\n- Remastered", "A?B", time, "jpg"),
        )
    }

    @Test
    fun `very long names fit in 255 bytes without splitting characters`() {
        val name = AppleLyricsShareFileName.build("长".repeat(200), "歌手", time, "jpg")
        assertTrue(name.toByteArray().size <= 255)
        assertTrue(name.endsWith("长-20261108_213045.jpg"))
    }
}
