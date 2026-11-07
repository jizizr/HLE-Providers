/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `i1.apply` result rewrite that keeps Apple Music 1614's progress dialog (Q) from launching
 * its platform Intent. Q's observer takes the holder's Intent and calls startActivityForResult
 * with it; the Intent is Snapchat-shaped (an image SEND to the snapchat creativekit deep link)
 * even for the injected `hleSaveImage` / `hleShareImage` destinations, whose ids match none of
 * the branches that attach a rendered image. Verified on a phone with Snapchat installed: tapping
 * our entry launched Snapchat with no image attached.
 */
class AppleLyricsShareResultGateTest {
    @Test
    fun `an active session always rewrites the result so the platform Intent never launches`() {
        // Pairs of (alreadySaved, hasRenderedFile): first delivery, re-delivery after a
        // successful export, and renders that produced no file.
        listOf(false to true, true to true, false to false, true to false).forEach { (saved, file) ->
            val decision = AppleLyricsShareResultGate.decide(
                sessionActive = true,
                alreadySaved = saved,
                hasRenderedFile = file,
            )
            assertTrue("swallowIntent must hold for saved=$saved, file=$file", decision.swallowIntent)
        }
    }

    @Test
    fun `without a session Apple's own result passes through untouched`() {
        val decision = AppleLyricsShareResultGate.decide(
            sessionActive = false,
            alreadySaved = false,
            hasRenderedFile = true,
        )
        assertFalse(decision.compose)
        assertFalse(decision.swallowIntent)
    }

    @Test
    fun `the export is composed at most once per session`() {
        assertTrue(
            AppleLyricsShareResultGate.decide(
                sessionActive = true, alreadySaved = false, hasRenderedFile = true,
            ).compose,
        )
        // Re-delivery after a successful export: no double save.
        assertFalse(
            AppleLyricsShareResultGate.decide(
                sessionActive = true, alreadySaved = true, hasRenderedFile = true,
            ).compose,
        )
        // Render produced no file: nothing to compose, but the Intent is still swallowed.
        assertFalse(
            AppleLyricsShareResultGate.decide(
                sessionActive = true, alreadySaved = false, hasRenderedFile = false,
            ).compose,
        )
    }
}
