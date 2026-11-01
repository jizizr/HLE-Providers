/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import java.util.Locale

internal data class QQMusicLyricTrack(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long,
    /**
     * MediaSession 原始 MEDIA_ID（未被 songmid/数字 ID 替换前的会话标识）。
     * 小米音乐车载歌词持续污染会话标题/歌手，mediaId 是唯一逐曲稳定身份；
     * 随歌曲元数据透传给 Core 后，SystemUI 侧 TrackIdentity 按 包名+mediaId
     * 严格匹配，不再依赖被污染的标题/歌手。
     */
    val sessionMediaId: String? = null,
    /**
     * id 是否已由应用内数据（HD/MIUI 播放队列快照的数字 ID）验证为在线歌曲 ID。
     * false 时（QQ 手机版的 MEDIA_ID 原值）本地歌曲的 id 是本地库数字 ID，
     * 换算器必须先经标题/歌手搜索校验才能用于取词（issue #62）。
     */
    val idTrusted: Boolean = false,
)

/** Keep songmid/numeric-ID upgrades on one lyric, but distinguish MIUI playback sessions. */
internal fun QQMusicLyricTrack.publishedIdentityKey(playerPackage: String): String = buildString {
    append(QQMusicSongMidResolver.normalizeForMatch(title.orEmpty()))
    append('|')
    append(QQMusicSongMidResolver.normalizeForMatch(artist.orEmpty()))
    if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
        append('|')
        append(sessionMediaId.orEmpty())
    }
}

internal sealed interface QQMusicLyricTrackDecision {
    data class AwaitingVerifiedId(val track: QQMusicLyricTrack) : QQMusicLyricTrackDecision
    data class Load(val track: QQMusicLyricTrack) : QQMusicLyricTrackDecision
    data object Unchanged : QQMusicLyricTrackDecision
}

/**
 * QQ Music HD exposes a queue-local MediaSession ID instead of the real QQ song ID. Keep the
 * MediaSession metadata for display. The current in-app SongInfo can also start a load when
 * MediaSession callbacks lag behind automatic track changes; SystemUI verifies the published
 * title and artist against its current MediaSession before displaying those lyrics.
 * Xiaomi Music (com.miui.player) reports a QQ songmid in its MediaSession while the public
 * lyric endpoint only accepts numeric IDs, and its car-lyrics feature rewrites the session
 * title into the current lyric line (also truncating long titles). The in-app
 * SongInfomation from MusicPlayer.getCurSong() is the player's authoritative current song,
 * so whenever it carries a usable numeric ID it replaces the songmid and provides the
 * published name/artist; a stale snapshot right after a track switch is rejected by the
 * SystemUI-side validation and self-heals on the next poll. An invalid snapshot (id<=0)
 * keeps the existing songmid conversion path as fallback.
 * QQ Music mobile retains its existing MediaSession ID behavior.
 */
internal class QQMusicLyricTrackCoordinator(
    private val playerPackage: String,
) {
    private var mediaTrack: QQMusicLyricTrack? = null
    private var queueSnapshot: QQMusicQueueSnapshot? = null
    private var lastDecisionKey: String? = null

    fun onMetadata(track: QQMusicLyricTrack): QQMusicLyricTrackDecision {
        mediaTrack = track
        return decide(track)
    }

    fun onQueueSnapshot(snapshot: QQMusicQueueSnapshot?): QQMusicLyricTrackDecision {
        if (snapshot == null || playerPackage == QQMusicRuntimePlan.MOBILE_PACKAGE) {
            return QQMusicLyricTrackDecision.Unchanged
        }
        queueSnapshot = snapshot
        if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
            return mediaTrack?.let(::decide) ?: QQMusicLyricTrackDecision.Unchanged
        }
        val current = snapshot.current
        if (current.id.toLongOrNull()?.let { it > 0L } != true ||
            normalize(current.title).isEmpty() || normalize(current.artist).isEmpty()
        ) {
            return mediaTrack?.let(::decide) ?: QQMusicLyricTrackDecision.Unchanged
        }
        val track = mediaTrack?.takeIf { sameIdentity(it, current) }
            ?.copy(id = current.id, idTrusted = true)
            ?: QQMusicLyricTrack(
                id = current.id,
                title = current.title,
                artist = current.artist,
                duration = 0L,
                idTrusted = true,
            )
        return emit(track, track)
    }

    private fun decide(track: QQMusicLyricTrack): QQMusicLyricTrackDecision {
        val resolved = when (playerPackage) {
            QQMusicRuntimePlan.HD_PACKAGE -> queueSnapshot?.current
                ?.takeIf { sameIdentity(track, it) }
                ?.let { current -> track.copy(id = current.id, idTrusted = true) }
            QQMusicRuntimePlan.MIUI_PACKAGE -> queueSnapshot?.current
                ?.takeIf { it.id.toLongOrNull()?.let { id -> id > 0L } == true }
                ?.let { current ->
                    // 快照 getCurSong() 是播放器当前曲：无条件信任其数字 ID 与权威真名/真歌手。
                    // 切歌时快照可能先于 MediaSession 更新；旧曲的 sessionMediaId
                    // 不能随新曲歌词一起发布，否则 SystemUI 会持续拒收。
                    val metadataMatchesCurrent = sameMiuiMediaTrack(track, current) ||
                        current.songMid?.equals(track.id, ignoreCase = true) == true
                    track.copy(
                        id = QQMusicSongMidResolver.sanitizeNumericSongId(current.id),
                        title = current.title.takeIf { normalize(it).isNotEmpty() } ?: track.title,
                        artist = current.artist.takeIf { normalize(it).isNotEmpty() } ?: track.artist,
                        sessionMediaId = track.sessionMediaId.takeIf { metadataMatchesCurrent },
                        idTrusted = true,
                    )
                }
                ?: track
            else -> track
        }
        return emit(resolved, track)
    }

    private fun emit(
        resolved: QQMusicLyricTrack?,
        awaiting: QQMusicLyricTrack,
    ): QQMusicLyricTrackDecision {
        val decisionKey = if (resolved == null) {
            "awaiting:${awaiting.identityKey()}"
        } else {
            "load:${resolved.identityKey()}"
        }
        if (decisionKey == lastDecisionKey) return QQMusicLyricTrackDecision.Unchanged
        lastDecisionKey = decisionKey
        return if (resolved == null) {
            QQMusicLyricTrackDecision.AwaitingVerifiedId(awaiting)
        } else {
            QQMusicLyricTrackDecision.Load(resolved)
        }
    }

    private fun sameIdentity(
        metadata: QQMusicLyricTrack,
        snapshot: QQMusicTrackSnapshot,
    ): Boolean {
        val metadataTitle = normalize(metadata.title)
        val metadataArtist = normalize(metadata.artist)
        return metadataTitle.isNotEmpty() &&
            metadataArtist.isNotEmpty() &&
            metadataTitle == normalize(snapshot.title) &&
            metadataArtist == normalize(snapshot.artist)
    }

    private fun sameMiuiMediaTrack(
        metadata: QQMusicLyricTrack,
        snapshot: QQMusicTrackSnapshot,
    ): Boolean {
        val metadataTitle = normalize(metadata.title)
        val snapshotTitle = normalize(snapshot.title)
        val metadataArtist = normalize(metadata.artist)
        val sameArtist = metadataArtist.isNotEmpty() &&
            metadataArtist == normalize(snapshot.artist)
        if (!sameArtist) return false
        if (metadataTitle.isNotEmpty() && metadataTitle == snapshotTitle) return true
        return metadataTitle.length >= 5 && snapshotTitle.length >= 5 &&
            (snapshotTitle.contains(metadataTitle) || metadataTitle.contains(snapshotTitle))
    }

    private fun QQMusicLyricTrack.identityKey(): String = buildString {
        append(id)
        append('|')
        append(normalize(title))
        append('|')
        append(normalize(artist))
        if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
            append('|')
            append(sessionMediaId.orEmpty())
        }
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
