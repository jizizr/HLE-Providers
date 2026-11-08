/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gallery file name of a saved lyric poster: "title-artist-yyyyMMdd_HHmmss.ext". */
internal object AppleLyricsShareFileName {
    /** Used in place of the title when Apple's item has none. */
    private const val UNTITLED = "Apple Music Lyrics"

    /** Characters that are invalid in file names on Android's shared storage. */
    private val INVALID = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val WHITESPACE = Regex("\\s+")

    /** Linux NAME_MAX is 255 bytes; the title and artist are trimmed to fit with the suffix. */
    private const val MAX_NAME_BYTES = 255

    fun build(title: String?, artist: String?, timeMillis: Long, extension: String): String {
        val suffix = "-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timeMillis)) + "." + extension
        val parts = listOfNotNull(clean(title) ?: UNTITLED, clean(artist))
        return fit(parts.joinToString("-"), MAX_NAME_BYTES - suffix.toByteArray().size) + suffix
    }

    private fun clean(value: String?): String? = value
        ?.replace(WHITESPACE, " ")
        ?.replace(INVALID, "_")
        ?.trim()
        ?.trim('.')
        ?.takeIf { it.isNotEmpty() }

    /** Cuts whole code points so a multi-byte character is never split. */
    private fun fit(value: String, maxBytes: Int): String {
        if (value.toByteArray().size <= maxBytes) return value
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val size = String(Character.toChars(codePoint)).toByteArray().size
            if (bytes + size > maxBytes) break
            result.appendCodePoint(codePoint)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return result.toString().trimEnd()
    }
}
