/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original 1614 DEX descriptors, not JADX display names; APK checks run in AppleMusicProfileBinaryTest. */
class AppleMusic7001614ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.0", 1614L)
    private val previous = AppleMusicVersion("7.0.0-beta", 1613L)
    private fun target(point: AppleMusicHookPoint, version: AppleMusicVersion = this.version) =
        AppleMusicHookProfiles.exactTargets(version, point).single()

    @Test
    fun `1614 has an isolated profile based on the verified 1613 map`() {
        assertEquals("am-7.0.0-beta-1614", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals("am-7.0.0-beta-1613", AppleMusicHookProfiles.profileFor(previous)?.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.0", 1615L)))
        AppleMusicHookPoint.entries.filterNot { it in lyricsSharePoints }.forEach { point ->
            assertEquals(
                "Lost targets: $point",
                AppleMusicHookProfiles.exactTargets(previous, point).size,
                AppleMusicHookProfiles.exactTargets(version, point).size,
            )
        }
    }

    private val lyricsSharePoints = AppleMusicHookPoint.entries.filter { it.name.startsWith("LYRICS_SHARE_") }

    @Test
    fun `lyrics share export targets exist only in the verified 1614 profile`() {
        assertEquals(8, lyricsSharePoints.size)
        lyricsSharePoints.forEach { point ->
            assertEquals("1614 $point", 1, AppleMusicHookProfiles.exactTargets(version, point).size)
            assertTrue("1613 $point", AppleMusicHookProfiles.exactTargets(previous, point).isEmpty())
        }
        val list = target(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_LIST)
        assertEquals("com.apple.android.music.common.actionsheet.U", list.className)
        assertEquals("a", list.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_DESTINATION_LIST_FIELD))
        assertEquals(
            target(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_ICON).className,
            list.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_DESTINATION_ITEM_CLASS),
        )
        val click = target(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_CLICK)
        assertEquals("z", click.methodName)
        assertEquals(listOf("int", "android.view.View", "com.apple.android.music.model.CollectionItemView"), click.parameterTypeNames)
        assertEquals("getTitle", click.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_ITEM_TITLE_METHOD))
        assertEquals(
            "com.apple.android.music.model.PlaybackItem",
            click.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_ITEM_PLAYBACK_CLASS),
        )
        assertEquals("getArtistName", click.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_ITEM_ARTIST_METHOD))
        assertEquals(
            "d",
            target(AppleMusicHookPoint.LYRICS_SHARE_FILE_PROVIDER_OPEN)
                .runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_FILE_PROVIDER_URI_METHOD),
        )
    }

    /**
     * The result rewrite answers `i1.apply` with an empty `mediaservices.utils.b` holder so the
     * progress dialog closes without launching its Snapchat-shaped platform Intent. That only
     * works if the holder's no-arg constructor is registered (DEX-verified: `b.<init>()V` is
     * `b(null)`, i.e. an empty holder).
     */
    @Test
    fun `the result holder no-arg constructor is registered for the empty-result rewrite`() {
        val rendered = target(AppleMusicHookPoint.LYRICS_SHARE_RENDERED_IMAGE)
        assertEquals("com.apple.android.music.utils.i1", rendered.className)
        assertEquals(
            "com.apple.android.mediaservices.utils.b",
            rendered.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_RESULT_CLASS),
        )
        assertEquals(
            "",
            rendered.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_RESULT_EMPTY_CONSTRUCTOR),
        )
    }

    @Test
    fun `1614 moved groups do not overwrite the 1613 targets`() {
        assertEquals(
            "com.apple.android.music.utils.l1",
            target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS, previous).className,
        )
        assertEquals("w", target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT, previous).methodName)
        assertEquals("k", target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND, previous).methodName)
        assertEquals("x", target(AppleMusicHookPoint.SEARCH_ADAPTER_MODEL, previous).methodName)
    }

    @Test
    fun `moved groups use the 1614 binary names`() {
        assertEquals(
            "com.apple.android.music.utils.m1",
            target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS).className,
        )
        assertEquals(
            "s",
            target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS)
                .runtimeMemberName(AppleMusicRuntimeMember.APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD),
        )
        val submit = target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT)
        assertEquals("ea.a", submit.className)
        assertEquals("x", submit.methodName)
        assertEquals("w", submit.runtimeMemberName(AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD))
        assertEquals("l", target(AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND).methodName)
        assertEquals("y", target(AppleMusicHookPoint.SEARCH_ADAPTER_MODEL).methodName)
        val adapters = AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
        assertEquals(
            listOf("com.apple.android.music.player.x", "com.apple.android.music.player.S0"),
            adapters.map { it.className },
        )
        adapters.forEach { adapter ->
            assertEquals("x", adapter.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD))
            assertEquals("y", adapter.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD))
            assertEquals("P", adapter.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD))
        }
    }
}
