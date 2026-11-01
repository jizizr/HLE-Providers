/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicLyricTrackCoordinatorTest {
    @Test
    fun `HD replaces queue local MediaSession ID with verified SongInfo ID`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        assertTrue(
            coordinator.onMetadata(
                track("4137", "Spotlight (聚光灯)", "蔡徐坤"),
            ) is QQMusicLyricTrackDecision.AwaitingVerifiedId,
        )

        val decision = coordinator.onQueueSnapshot(
            snapshot("451887939", "Spotlight（聚光灯）", "蔡徐坤"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("451887939", decision.track.id)
        assertEquals("Spotlight (聚光灯)", decision.track.title)
        assertEquals(202_000L, decision.track.duration)
        // 快照数字 ID 已被应用内数据验证，换算器可直接透传
        assertTrue(decision.track.idTrusted)
    }

    @Test
    fun `HD refuses stale SongInfo identity while tracks are crossing`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        coordinator.onQueueSnapshot(snapshot("100", "Song A", "Artist A"))
        val first = coordinator.onMetadata(track("4081", "Song B", "Artist B"))

        assertTrue(first is QQMusicLyricTrackDecision.AwaitingVerifiedId)

        val second = coordinator.onQueueSnapshot(snapshot("200", "Song B", "Artist B"))
            as QQMusicLyricTrackDecision.Load
        assertEquals("200", second.track.id)
    }

    @Test
    fun `HD loads current SongInfo when queue changes before metadata callback`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        coordinator.onMetadata(track("4073", "Song A", "Artist A"))
        assertTrue(
            coordinator.onQueueSnapshot(
                snapshot("100", "Song A", "Artist A"),
            ) is QQMusicLyricTrackDecision.Load,
        )

        val next = coordinator.onQueueSnapshot(
            snapshot("200", "Song B", "Artist B"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("200", next.track.id)
        assertEquals("Song B", next.track.title)
        assertEquals(0L, next.track.duration)
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("200", "Song B", "Artist B")),
        )
    }

    @Test
    fun `HD can load current SongInfo before any media metadata callback`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        val decision = coordinator.onQueueSnapshot(
            snapshot("261863461", "Dreamland", "Glass Animals"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("261863461", decision.track.id)
        assertEquals("Dreamland", decision.track.title)
        assertEquals("Glass Animals", decision.track.artist)
    }

    @Test
    fun `HD rejects invalid current SongInfo instead of requesting unrelated lyrics`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("0", "Dreamland", "Glass Animals")),
        )
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("261863461", "Dreamland", "")),
        )
    }

    @Test
    fun `repeated HD snapshot does not trigger another lyric download`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)
        val snapshot = snapshot("451887939", "Spotlight (聚光灯)", "蔡徐坤")

        coordinator.onMetadata(track("4137", "Spotlight (聚光灯)", "蔡徐坤"))
        assertTrue(coordinator.onQueueSnapshot(snapshot) is QQMusicLyricTrackDecision.Load)
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot),
        )
    }

    @Test
    fun `changing HD queue local MediaSession ID does not reload the same song`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)
        val first = coordinator.onQueueSnapshot(
            snapshot("451887939", "Spotlight (聚光灯)", "蔡徐坤"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("451887939", first.track.id)

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onMetadata(track("4137", "Spotlight (聚光灯)", "蔡徐坤")),
        )

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onMetadata(
                track("4209", "Spotlight (聚光灯)", "蔡徐坤"),
            ),
        )
    }

    @Test
    fun `mobile keeps using MediaSession song ID`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MOBILE_PACKAGE)

        val decision = coordinator.onMetadata(
            track("368304013", "Hug me", "蔡徐坤"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("368304013", decision.track.id)
        // 手机版 MEDIA_ID 原值未经应用内数据验证，换算器必须搜索校验（issue #62）
        assertFalse(decision.track.idTrusted)
    }

    @Test
    fun `MIUI replaces songmid with matching SongInfo numeric ID`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        coordinator.onMetadata(track("003iXtVK2B6Zk6", "this is what winter feels like", "JVKE"))
        val decision = coordinator.onQueueSnapshot(
            snapshot("463324123", "this is what winter feels like", "JVKE"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("463324123", decision.track.id)
        assertEquals("this is what winter feels like", decision.track.title)
        assertEquals("JVKE", decision.track.artist)
        assertEquals(202_000L, decision.track.duration)
        assertTrue(decision.track.idTrusted)
    }

    @Test
    fun `MIUI strips SDK flag bits from SongInfo id`() {
        // 4.44.0.9 真机实测：SongInfomation.getId() = songId | 0x2000000000000000
        // （陈粒《虚拟》107762076 → 2305843009321456028），带标志位请求歌词返回空内容
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        coordinator.onMetadata(track("0019lfLV2Rl8VH", "虚拟", "陈粒"))
        val decision = coordinator.onQueueSnapshot(
            snapshot("2305843009321456028", "虚拟", "陈粒"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("107762076", decision.track.id)
    }

    @Test
    fun `MIUI trusts snapshot identity even when it lags behind metadata`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        // 切歌瞬间快照仍是另一首：无条件信任快照（SystemUI 校验拒绝误显示，
        // 下一轮轮询自愈），发布快照的数字 ID 与权威真名
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(
                snapshot("319782348", "A Different Song", "Glass Animals"),
            ),
        )
        val decision = coordinator.onMetadata(
            track("002sXsnt1doEOH", "The Other Side of Paradise（天堂彼岸）", "Glass Animals"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("319782348", decision.track.id)
        assertEquals("A Different Song", decision.track.title)
    }

    @Test
    fun `MIUI ignores invalid SongInfo id and keeps songmid`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("0", "this is what winter feels like", "JVKE")),
        )
        val decision = coordinator.onMetadata(
            track("003iXtVK2B6Zk6", "this is what winter feels like", "JVKE"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("003iXtVK2B6Zk6", decision.track.id)
        // 无快照可用：数字/歌曲 ID 均未获应用内验证，songmid 兜底换算自带标题校验
        assertFalse(decision.track.idTrusted)
    }

    @Test
    fun `MIUI snapshot without metadata waits for media callback`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("463324123", "this is what winter feels like", "JVKE")),
        )

        // 元数据回调到达后，与已缓存的快照同曲：直接用数字 ID
        val decision = coordinator.onMetadata(
            track("003iXtVK2B6Zk6", "this is what winter feels like", "JVKE"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("463324123", decision.track.id)
    }

    @Test
    fun `repeated MIUI snapshot does not trigger another lyric download`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        coordinator.onMetadata(track("003iXtVK2B6Zk6", "this is what winter feels like", "JVKE"))
        assertTrue(
            coordinator.onQueueSnapshot(
                snapshot("463324123", "this is what winter feels like", "JVKE"),
            ) is QQMusicLyricTrackDecision.Load,
        )
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("463324123", "this is what winter feels like", "JVKE")),
        )
    }

    private fun track(id: String, title: String, artist: String, duration: Long = 202_000L) =
        QQMusicLyricTrack(
            id = id,
            title = title,
            artist = artist,
            duration = duration,
        )

    private fun snapshot(
        id: String,
        title: String,
        artist: String,
        songMid: String? = null,
        duration: Long = 0L,
    ) = QQMusicQueueSnapshot(
        current = QQMusicTrackSnapshot(id, title, artist, songMid, duration),
        next = null,
    )

    @Test
    fun `MIUI binds truncated title by containment and adopts full snapshot name`() {
        // 真机实测：App 元数据标题 golden hour，QQ 真名 In your golden hour
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        coordinator.onMetadata(track("002jsOVo03DMQG", "golden hour", "JVKE"))
        val decision = coordinator.onQueueSnapshot(
            snapshot("236084449", "In your golden hour", "JVKE"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("236084449", decision.track.id)
        assertEquals("In your golden hour", decision.track.title)
        assertEquals("JVKE", decision.track.artist)
    }

    @Test
    fun `MIUI binds polluted title by duration and artist match`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        // 标题被车载歌词改写成歌词行、真名无法从元数据恢复：时长+歌手绑定
        coordinator.onMetadata(
            track("001N3vAw4KogM1", "看着我坠啊坠啊坠落到云里", "陈粒", duration = 250_000L),
        )
        val decision = coordinator.onQueueSnapshot(
            snapshot("107762076", "虚拟", "陈粒", duration = 250_000L),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("107762076", decision.track.id)
        assertEquals("虚拟", decision.track.title)
    }

    @Test
    fun `MIUI adopts snapshot even when metadata identity is fully unrelated`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        // 歌手/时长/标题全不同也采用快照（播放器当前曲即权威），元数据仅作兜底
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(
                snapshot("107762076", "虚拟", "陈粒", duration = 250_000L),
            ),
        )
        val decision = coordinator.onMetadata(
            track("0039MnYb0qxYhV", "晴天", "周杰伦", duration = 269_000L),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("107762076", decision.track.id)
        assertEquals("虚拟", decision.track.title)
    }

    @Test
    fun `MIUI binds by songmid and adopts authoritative identity under pollution`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        // 冷启动落到歌曲中段：元数据标题是首采样歌词行，策略无法恢复真名
        coordinator.onMetadata(track("001AzzRc42NBVo", "看着我坠啊坠啊坠落到云里", "陈粒"))
        val decision = coordinator.onQueueSnapshot(
            // SongInfomation.getId() = songId | 0x2000000000000000（438910555 → 带标志位）
            snapshot(
                "2305843009652604507",
                "唯一",
                "陈粒",
                songMid = "001AzzRc42NBVo",
            ),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("438910555", decision.track.id)
        assertEquals("唯一", decision.track.title)
        assertEquals("陈粒", decision.track.artist)
    }

    @Test
    fun `MIUI snapshot adoption does not depend on songmid field`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(
                snapshot(
                    "2305843009652604507",
                    "唯一",
                    "陈粒",
                    songMid = "001AzzRc42NBVo",
                ),
            ),
        )
        // songMid 字段运行时常为空，绑定不依赖它：元数据回调直接采用快照数字 ID
        val decision = coordinator.onMetadata(
            track("0039MnYb0qxYhV", "晴天", "周杰伦"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("438910555", decision.track.id)
        assertEquals("唯一", decision.track.title)
    }

    @Test
    fun `MIUI songmid binding tolerates polluted title but requires same song`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)

        // songmid 相等即绑定，即使元数据标题被污染成歌词行、歌手字段格式不同
        coordinator.onMetadata(track("001AzzRc42NBVo", "You cut me open", "Love Somebody-LAUV"))
        val decision = coordinator.onQueueSnapshot(
            snapshot(
                "2305843009652604507",
                "唯一",
                "陈粒",
                songMid = "001azzrc42nbvo",
            ),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("438910555", decision.track.id)
        assertEquals("唯一", decision.track.title)
    }

    @Test
    fun `MIUI queue first does not attach previous session ID and metadata rebinds lyrics`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)
        coordinator.onMetadata(
            track("old-session", "Beautiful Stranger", "Laufey")
                .copy(sessionMediaId = "old-session"),
        )

        val early = coordinator.onQueueSnapshot(
            snapshot("104883226", "再见", "G.E.M.邓紫棋"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("104883226", early.track.id)
        assertEquals(null, early.track.sessionMediaId)

        val rebound = coordinator.onMetadata(
            track("new-session", "再见", "G.E.M.邓紫棋")
                .copy(sessionMediaId = "new-session"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("104883226", rebound.track.id)
        assertEquals("new-session", rebound.track.sessionMediaId)
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onMetadata(
                track("new-session", "再见", "G.E.M.邓紫棋")
                    .copy(sessionMediaId = "new-session"),
            ),
        )
    }

    @Test
    fun `MIUI keeps session ID when snapshot has longer version of the same title`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MIUI_PACKAGE)
        coordinator.onMetadata(
            track("session-1", "golden hour", "JVKE")
                .copy(sessionMediaId = "session-1"),
        )
        val decision = coordinator.onQueueSnapshot(
            snapshot("236084449", "In your golden hour", "JVKE"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("session-1", decision.track.sessionMediaId)
    }

    @Test
    fun `MIUI publication identity changes with session while HD identity does not`() {
        val first = track("songmid", "再见", "G.E.M.邓紫棋")
            .copy(sessionMediaId = "old-session")
        val rebound = first.copy(id = "104883226", sessionMediaId = "new-session")
        assertTrue(
            first.publishedIdentityKey(QQMusicRuntimePlan.MIUI_PACKAGE) !=
                rebound.publishedIdentityKey(QQMusicRuntimePlan.MIUI_PACKAGE),
        )
        assertEquals(
            first.publishedIdentityKey(QQMusicRuntimePlan.HD_PACKAGE),
            rebound.publishedIdentityKey(QQMusicRuntimePlan.HD_PACKAGE),
        )
    }
}
