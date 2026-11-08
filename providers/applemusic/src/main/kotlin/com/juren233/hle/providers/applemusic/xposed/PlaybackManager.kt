/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.util.Log
import com.juren233.hle.providers.applemusic.xposed.diagnostics.AppleMetadataFlowDiagnostics
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import com.juren233.hle.providers.applemusic.xposed.parser.AppleLyricsParserAccess
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import kotlin.system.measureTimeMillis

object PlaybackManager {
    private var player: RemotePlayer? = null
    private var lyricRequester: LyricRequester? = null
    private var lyricsParserAccess: AppleLyricsParserAccess? = null
    private var onMissingLyricsSupplementBuilt: ((Song) -> Unit)? = null
    private var hasKnownNativeLyrics: ((String) -> Boolean)? = null
    private var isSupplementRevoked: ((String) -> Boolean)? = null

    // 状态追踪
    private var currentSongId: String? = null
    private var runtimeUnavailablePromotionSongId: String? = null
    private var lastDisplayDiagnosticSignature: String? = null
    private var lastPublishedSong: Song? = null
    private var lastPublishedDisplayTranslation: Boolean? = null

    internal fun init(
        remotePlayer: RemotePlayer,
        requester: LyricRequester,
        hookResolver: AppleMusicHookResolver,
        onMissingLyricsSupplementBuilt: (Song) -> Unit,
        hasKnownNativeLyrics: (String) -> Boolean,
        isSupplementRevoked: (String) -> Boolean,
    ) {
        this.player = remotePlayer
        this.lyricRequester = requester
        this.lyricsParserAccess = AppleLyricsParserAccess.from(hookResolver)
        this.onMissingLyricsSupplementBuilt = onMissingLyricsSupplementBuilt
        this.hasKnownNativeLyrics = hasKnownNativeLyrics
        this.isSupplementRevoked = isSupplementRevoked
    }

    /**
     * 当系统切歌或 Metadata 变化时调用
     */
    fun onSongChanged(newId: String?) {
        if (newId.isNullOrBlank()) {
            currentSongId = null
            setSong(null)
            ProviderLogger.debug("PlaybackManager: Song changed to null")
            logDisplayDiagnostic(null, "cleared", "song_id_missing")
            return
        }

        // 避免重复处理同一首歌
        if (newId == currentSongId) {
            logDisplayDiagnostic(lastSong, "skipped", "duplicate_song_id")
            return
        }
        runtimeUnavailablePromotionSongId = null
        currentSongId = newId

        ProviderLogger.debug("PlaybackManager: Song changed to $newId")

        // 1. 立即设置歌曲（可能是完整版，也可能是占位版）
        val switchStartNs = if (AppleProviderEnvironment.diagnosticsEnabled) android.os.SystemClock.elapsedRealtimeNanos() else 0L
        val cachedSong = SongRepository.getSong(newId)
        val persistedMissingLyrics = DiskSongManager.loadMissingLyrics(newId)
            ?.lyrics
            .isNullOrEmpty()
            .not()
        val legacyMissingLyricsCandidate =
            !persistedMissingLyrics &&
                shouldUseLegacyCachedLyricsAsMissingSupplement(
                    song = cachedSong,
                    currentSongId = newId,
                )
        val cachedMissingLyricsSupplement =
            shouldUseCachedLyricsAsMissingSupplement(
                song = cachedSong,
                currentSongId = newId,
                hasPersistedMissingLyrics = persistedMissingLyrics,
            )
        val song = if (cachedMissingLyricsSupplement) {
            markAsMissingLyricsSupplement(cachedSong)
        } else {
            cachedSong
        }
        setSong(song)
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.diagnostic(
                "[SongCacheIo] stage=song_changed_sync, id=$newId, " +
                    "totalMs=${(android.os.SystemClock.elapsedRealtimeNanos() - switchStartNs) / 1_000_000.0}, " +
                    "lines=${song.lyrics?.size ?: 0}, thread=${Thread.currentThread().name}"
            )
        }
        if (cachedMissingLyricsSupplement) {
            ProviderLogger.info(
                if (legacyMissingLyricsCandidate) {
                    "Apple Music 无歌词补充冷启动迁移旧缓存候选: id=$newId, "
                } else {
                    "Apple Music 无歌词补充恢复模块缓存: id=$newId, "
                } +
                    "lines=${song.lyrics.orEmpty().size}"
            )
            onMissingLyricsSupplementBuilt?.invoke(song)
        }

        // 2. 缓存缺少歌词或用户需要的翻译时重新下载。
        val lyrics = song.lyrics
        val needsLyrics = lyrics.isNullOrEmpty()
        val needsTranslation =
            !needsLyrics &&
                PreferencesMonitor.isTranslationSelected() &&
                lyrics.any { it.translation.isNullOrBlank() }
        if (needsLyrics || needsTranslation) {
            val queueId = MediaMetadataCache.getMetadataById(newId)?.queueId ?: 0L
            logDisplayDiagnostic(
                song,
                "pending",
                if (needsLyrics) "lyrics_download_requested" else "translation_download_requested",
                "queueId=$queueId",
            )
            lyricRequester?.requestDownload(newId, queueId)
        } else {
            ProviderLogger.debug("PlaybackManager: Song $newId has complete lyrics, skipping download.")
            logDisplayDiagnostic(song, "ready", "cached_lyrics_complete")
        }
    }

    fun onCatalogMetadataResolved(id: String) {
        if (AppleProviderEnvironment.diagnosticsEnabled && (id == currentSongId || id == lastSong?.id)) {
            AppleMetadataFlowDiagnostics.record("catalog_republish_request") {
                "id=$id currentId=$currentSongId accepted=${id == currentSongId} " +
                    "previous=${AppleMetadataFlowDiagnostics.provider(lastSong)}"
            }
        }
        if (id != currentSongId) return
        val republishInputs = CatalogRepublishInputs(
            songId = id,
            metadata = MediaMetadataCache.getMetadataById(id),
            diskGeneration = DiskSongManager.generation,
            displayTranslation = PreferencesMonitor.isTranslationSelected(),
        )
        if (isCatalogRepublishUnchanged(lastCatalogRepublish, republishInputs, lastSong)) {
            if (AppleProviderEnvironment.diagnosticsEnabled) ProviderLogger.debug(
                "PlaybackManager: Catalog republish skipped for $id, inputs unchanged."
            )
            return
        }
        val resolvedSong = SongRepository.getSong(id)
        val mergedSong = mergeCatalogMetadata(lastSong, resolvedSong)
        if (AppleProviderEnvironment.diagnosticsEnabled) AppleMetadataFlowDiagnostics.record("catalog_republish_merge") {
            "resolved=${AppleMetadataFlowDiagnostics.provider(resolvedSong)} " +
                "merged=${AppleMetadataFlowDiagnostics.provider(mergedSong)}"
        }
        ProviderLogger.info(
            "PlaybackManager: Catalog metadata ready for current song $id, " +
                "resolvedLines=${resolvedSong.lyrics?.size ?: 0}, " +
                "publishedLines=${mergedSong.lyrics?.size ?: 0}."
        )
        val published = setSong(mergedSong)
        lastCatalogRepublish = if (published) {
            CatalogRepublish(republishInputs, mergedSong)
        } else {
            null
        }
    }

    /**
     * 一次目录重发布的输入。切歌后 App 内元数据每覆盖一次都会调用 [onCatalogMetadataResolved]
     * （221013 实测 13 首歌 151 次），输入未变时结果必然相同：歌曲缓存只随 [DiskSongManager.generation]
     * 变化，映射与合并是纯函数，合并的另一输入是上次发布后未被替换的 [lastSong]。
     */
    internal data class CatalogRepublishInputs(
        val songId: String,
        val metadata: MediaMetadataCache.Metadata?,
        val diskGeneration: Long,
        val displayTranslation: Boolean,
    )

    internal class CatalogRepublish(
        val inputs: CatalogRepublishInputs,
        val output: Song,
    )

    private var lastCatalogRepublish: CatalogRepublish? = null

    internal fun isCatalogRepublishUnchanged(
        previous: CatalogRepublish?,
        inputs: CatalogRepublishInputs,
        currentSong: Song?,
    ): Boolean = previous != null &&
        previous.output === currentSong &&
        previous.inputs == inputs

    /**
     * 当 Hook 捕获到歌词构建完成时调用
     */
    fun onLyricsBuilt(
        nativeSongObj: Any,
        source: String,
        visibleSongId: String? = null,
        playbackSongId: String? = null,
        trigger: String = "",
    ) {
        val previousSong = lastSong
        val parserAccess = lyricsParserAccess
        if (parserAccess == null) {
            ProviderLogger.error("PlaybackManager: Lyrics parser runtime profile is unavailable")
            logDisplayDiagnostic(null, "skipped", "lyrics_parser_profile_missing", "source=$source")
            return
        }
        val song = SongRepository.saveSong(nativeSongObj, parserAccess, source, trigger)
        if (song == null) {
            ProviderLogger.debug("PlaybackManager: Failed to save song.")
            logDisplayDiagnostic(null, "skipped", "lyrics_parse_failed", "source=$source")
            return
        }
        val id = song.id?.takeIf { it.isNotBlank() } ?: return
        val translatedLines = song.lyrics?.count { !it.translation.isNullOrBlank() } ?: 0
        val lyricLines = song.lyrics?.size ?: 0
        ProviderLogger.debug(
            "PlaybackManager: Lyrics parsed from $source for $id, " +
                "lines=$lyricLines, translatedLines=$translatedLines"
        )

        val isSongSame by lazy {
            var same = false
            val time = measureTimeMillis {
                same = lastSong != song
            }
            Log.d("PlaybackManager", "Same song check took $time ms.")
            return@lazy same
        }

        val shouldPublish = shouldPublishBuiltLyrics(
            songId = id,
            currentSongId = currentSongId,
            visibleSongId = visibleSongId,
            playbackSongId = playbackSongId,
            source = source,
        )
        if (AppleProviderEnvironment.diagnosticsEnabled) AppleMetadataFlowDiagnostics.record("lyrics_publication_decision") {
            "source=$source currentId=$currentSongId visibleId=$visibleSongId playbackId=$playbackSongId " +
                "identityAccepted=$shouldPublish contentChanged=${if (shouldPublish) isSongSame else null} " +
                "incoming=${AppleMetadataFlowDiagnostics.provider(song)} " +
                "previous=${AppleMetadataFlowDiagnostics.provider(lastSong)}"
        }
        if (shouldPublish && isSongSame) {
            if (id != currentSongId) {
                currentSongId = id
                ProviderLogger.debug(
                    "PlaybackManager: Visible lyrics adopted as current song $id before playback."
                )
            }
            ProviderLogger.debug("PlaybackManager: Lyrics ready for current song $id, updating player.")
            val authoritativeNativeLyrics = source == "apple" &&
                hasKnownNativeLyrics?.invoke(id) == true
            val missingLyricsSupplement = !authoritativeNativeLyrics &&
                shouldUseBuiltLyricsAsMissingSupplement(
                    source = source,
                    song = song,
                    currentSongId = currentSongId,
                    previousSong = previousSong,
                )
            val publishedSong = when {
                authoritativeNativeLyrics -> markAsConfirmedAppleNativeLyrics(
                    song,
                    supplementRevoked = isSupplementRevoked?.invoke(id) == true,
                )
                missingLyricsSupplement -> markAsMissingLyricsSupplement(song)
                else -> song
            }
            if (authoritativeNativeLyrics) {
                ProviderLogger.info(
                    "Apple Music 原生歌词权威发布: id=$id, lines=$lyricLines"
                )
            }
            if (missingLyricsSupplement) {
                ProviderLogger.info(
                    "Apple Music 无歌词补充采用模块歌词: id=$id, lines=$lyricLines"
                )
                onMissingLyricsSupplementBuilt?.invoke(publishedSong)
            }
            logDisplayDiagnostic(
                publishedSong,
                "ready",
                if (id == visibleSongId) {
                    "lyrics_parsed_for_visible_song"
                } else {
                    "lyrics_parsed_for_current_song"
                },
                "source=$source, currentSongId=$currentSongId, " +
                    "visibleSongId=$visibleSongId, playbackSongId=$playbackSongId",
            )
            setSong(publishedSong)
        } else {
            ProviderLogger.debug(
                if (shouldPublish) "PlaybackManager: Duplicate lyrics content for current song $id."
                else "PlaybackManager: Lyrics ready for song $id, but not current song."
            )
            logDisplayDiagnostic(
                song,
                "skipped",
                if (shouldPublish) "duplicate_lyrics_content_skipped" else "lyrics_for_non_current_song",
                "source=$source, currentSongId=$currentSongId, " +
                    "visibleSongId=$visibleSongId, playbackSongId=$playbackSongId",
            )
        }
    }

    private var lastSong: Song? = null

    /**
     * 目录源补充被撤销后，把已确认的原生歌词重新发布一份带撤销标记的版本，让核心回到
     * 原生歌词基准。刷新界面绑定不会触发这次发布，所以必须在这里直接发。
     * 原生歌词尚未发布时返回 false；之后的发布会通过 [isSupplementRevoked] 携带标记。
     */
    @Synchronized
    internal fun republishNativeAfterSupplementRevoked(songId: String): Boolean {
        val song = lastSong?.takeIf { it.id == songId } ?: return false
        val confirmed = song.metadata
            ?.getString(LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED)
            .toBoolean()
        if (!confirmed) return false
        ProviderLogger.info("Apple Music 补充已撤销，重新发布原生歌词: id=$songId")
        return setSong(markAsConfirmedAppleNativeLyrics(song, supplementRevoked = true))
    }

    /**
     * Apple 原生歌词请求已完成且结果为空：把当前曲目的占位歌曲重新发布一份带「歌词确认缺失」标记的版本，
     * 让核心知道本 Provider 这一侧已经没有歌词。当前曲目已有歌词（含三方补充）或已带标记时不发。
     */
    @Synchronized
    internal fun republishAsLyricsUnavailable(songId: String): Boolean {
        val song = lastSong?.takeIf { it.id == songId } ?: return false
        if (!song.lyrics.isNullOrEmpty()) return false
        if (song.metadata?.getString(LyricMetadataKeys.LYRICS_UNAVAILABLE).toBoolean()) return false
        ProviderLogger.info("Apple Music 原生歌词为空，上报歌词确认缺失: id=$songId")
        val metadataEntries = buildList<Pair<String, String?>> {
            addAll(song.metadata.orEmpty().entries.map { it.key to it.value })
            add(LyricMetadataKeys.LYRICS_UNAVAILABLE to "true")
        }
        return setSong(song.copy(metadata = lyricMetadataOf(*metadataEntries.toTypedArray())))
    }

    internal fun mergeCatalogMetadata(currentSong: Song?, resolvedSong: Song): Song {
        val currentSameSong = currentSong?.takeIf { it.id == resolvedSong.id }
        val currentLyrics = currentSameSong
            ?.lyrics
            ?.takeIf { it.isNotEmpty() }
        val currentIsMissingLyricsSupplement = currentSameSong
            ?.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean()
        if (currentIsMissingLyricsSupplement && currentLyrics != null) {
            val mergedMetadata = linkedMapOf<String, String?>()
            currentSameSong.metadata?.forEach { (key, value) -> mergedMetadata[key] = value }
            resolvedSong.metadata?.forEach { (key, value) -> mergedMetadata[key] = value }
            listOf(
                LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE,
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT,
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE,
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES,
                LyricMetadataKeys.TTML_SOURCE_RAW,
                LyricMetadataKeys.TTML_SOURCE_LYRIC_ID,
                LyricMetadataKeys.TTML_SOURCE_SHA256,
                LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE,
                LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE,
            ).forEach { key ->
                currentSameSong.metadata?.getString(key)?.let { mergedMetadata[key] = it }
            }
            return resolvedSong.copy(
                name = resolvedSong.name ?: currentSameSong.name,
                artist = resolvedSong.artist ?: currentSameSong.artist,
                duration = resolvedSong.duration.takeIf { it > 0L } ?: currentSameSong.duration,
                metadata = mergedMetadata
                    .takeIf { it.isNotEmpty() }
                    ?.let { lyricMetadataOf(*it.entries.map { entry ->
                        entry.key to entry.value
                    }.toTypedArray()) },
                lyrics = currentLyrics,
            )
        }
        if (!resolvedSong.lyrics.isNullOrEmpty() || currentLyrics == null) return resolvedSong

        return resolvedSong.copy(
            name = resolvedSong.name ?: currentSameSong.name,
            artist = resolvedSong.artist ?: currentSameSong.artist,
            duration = resolvedSong.duration.takeIf { it > 0L } ?: currentSameSong.duration,
            metadata = resolvedSong.metadata ?: currentSameSong.metadata,
            lyrics = currentLyrics
        )
    }

    internal fun shouldPublishBuiltLyrics(
        songId: String,
        currentSongId: String?,
        visibleSongId: String?,
        playbackSongId: String?,
        source: String,
    ): Boolean {
        if (playbackSongId != null && songId != playbackSongId) return false
        if (songId == currentSongId) return true
        return source == "apple" &&
            songId == visibleSongId &&
            songId == playbackSongId
    }

    internal fun shouldUseBuiltLyricsAsMissingSupplement(
        source: String,
        song: Song,
        currentSongId: String?,
        previousSong: Song?,
    ): Boolean {
        if (source != "module") return false
        val songId = song.id?.takeIf(String::isNotBlank) ?: return false
        if (songId != currentSongId || song.lyrics.isNullOrEmpty()) return false
        if (previousSong != null && previousSong.id != songId) return false
        return previousSong?.lyrics.isNullOrEmpty()
    }

    internal fun shouldUseCachedLyricsAsMissingSupplement(
        song: Song,
        currentSongId: String?,
        hasPersistedMissingLyrics: Boolean = false,
    ): Boolean {
        val songId = song.id?.takeIf(String::isNotBlank) ?: return false
        if (songId != currentSongId || song.lyrics.isNullOrEmpty()) return false
        return hasPersistedMissingLyrics || song.metadata
            ?.getString(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE) == "module" ||
            shouldUseLegacyCachedLyricsAsMissingSupplement(song, currentSongId)
    }

    /**
     * 旧缓存没有 lyricsSource。若冷启动已直接命中这类完整缓存，后续不会再发起模块
     * loadLyrics，原来挂在 loadLyrics 后的延迟迁移也就永远无法执行。这里仅把它作为
     * 补充候选写入 Store、暴露歌词入口；真正构建和呈现仍由原生歌词接管门决定。
     */
    internal fun shouldUseLegacyCachedLyricsAsMissingSupplement(
        song: Song,
        currentSongId: String?,
    ): Boolean {
        val songId = song.id?.takeIf(String::isNotBlank) ?: return false
        if (songId != currentSongId) return false
        return shouldPromoteLegacyCachedLyricsAsMissingSupplement(song)
    }

    /**
     * 缓存来源标签不能覆盖 Apple 当前 PlaybackItem 的真实可用性结论。
     *
     * 只有 Apple 当前明确返回无原生歌词、Store 仍为空且当前缓存确有可渲染歌词时，
     * 才允许把缓存降级成补充候选。候选只负责解锁入口；最终呈现仍由原生接管门决定。
     */
    internal fun shouldPromoteCurrentCacheAfterNativeUnavailable(
        song: Song,
        currentSongId: String?,
        nativeLyricsAvailable: Boolean,
        nativeLyricsKnown: Boolean,
        storeHasContent: Boolean,
    ): Boolean {
        if (nativeLyricsAvailable || nativeLyricsKnown || storeHasContent) return false
        val songId = song.id?.takeIf(String::isNotBlank) ?: return false
        return songId == currentSongId && !song.lyrics.isNullOrEmpty()
    }

    @Synchronized
    internal fun promoteCurrentCacheAfterNativeUnavailable(
        songId: String,
        nativeLyricsKnown: Boolean,
        storeHasContent: Boolean,
    ): Boolean {
        if (songId.isBlank() || runtimeUnavailablePromotionSongId == songId) return false
        val song = lastSong?.takeIf { it.id == songId } ?: return false
        if (!shouldPromoteCurrentCacheAfterNativeUnavailable(
                song = song,
                currentSongId = currentSongId,
                nativeLyricsAvailable = false,
                nativeLyricsKnown = nativeLyricsKnown,
                storeHasContent = storeHasContent,
            )
        ) {
            return false
        }
        runtimeUnavailablePromotionSongId = songId
        val previousCacheSource = song.metadata
            ?.getString(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE)
        val supplementSong = markAsMissingLyricsSupplement(
            song = song,
            cacheSource = "module",
        )
        ProviderLogger.info(
            "Apple Music 运行时无原生歌词，降级当前缓存为补充候选: " +
                "id=$songId, lines=${song.lyrics.orEmpty().size}, " +
                "previousCacheSource=$previousCacheSource"
        )
        setSong(supplementSong)
        onMissingLyricsSupplementBuilt?.invoke(supplementSong)
        return true
    }

    /**
     * 将没有来源标记的旧 Apple 歌词缓存迁移成补充载荷，避免冷启动时被误当作原生歌词。
     */
    internal fun promoteLegacyCachedLyricsAsMissingSupplement(songId: String): Boolean {
        if (songId.isBlank() || songId != currentSongId) return false
        val song = lastSong?.takeIf { it.id == songId } ?: SongRepository.getSong(songId)
        if (!shouldPromoteLegacyCachedLyricsAsMissingSupplement(song)) return false
        val supplementSong = markAsMissingLyricsSupplement(
            song = song,
            cacheSource = "module",
        )
        ProviderLogger.info(
            "Apple Music 无歌词补充迁移旧缓存: id=$songId, lines=${song.lyrics.orEmpty().size}"
        )
        setSong(supplementSong)
        onMissingLyricsSupplementBuilt?.invoke(supplementSong)
        return true
    }

    /**
     * 普通播放通道也必须携带补充标记；否则 SystemUI 会把模块缓存误认为 Apple 原生
     * 歌词，既不重新检索来源状态，后续的来源切换请求也会被过期保护拒绝。
     */
    internal fun markAsMissingLyricsSupplement(
        song: Song,
        cacheSource: String? = null,
    ): Song {
        val songId = song.id?.takeIf(String::isNotBlank)
        val persistedMetadata = songId
            ?.let(DiskSongManager::loadMissingLyrics)
            ?.metadata
        val effectiveCacheSource = cacheSource
            ?: song.metadata?.getString(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE)
            ?: "module"
        val metadataEntries = buildList<Pair<String, String?>> {
            addAll(song.metadata.orEmpty().entries.map { it.key to it.value })
            add(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to effectiveCacheSource)
            add(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT to "true")
            listOf(
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE,
                LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES,
                LyricMetadataKeys.TTML_SOURCE_RAW,
                LyricMetadataKeys.TTML_SOURCE_LYRIC_ID,
                LyricMetadataKeys.TTML_SOURCE_SHA256,
            ).forEach { key ->
                val value = song.metadata?.getString(key)
                    ?: persistedMetadata?.getString(key)
                if (!value.isNullOrBlank()) add(key to value)
            }
        }
        return song.copy(metadata = lyricMetadataOf(*metadataEntries.toTypedArray()))
    }

    /**
     * Apple 的非空原生主结果必须显式越过跨进程的 supplement 保留逻辑。
     * 普通无标记回调仍按旧规则继承补充身份，只有已由原生主结果确认的歌曲会携带此标记。
     */
    internal fun markAsConfirmedAppleNativeLyrics(song: Song, supplementRevoked: Boolean = false): Song {
        val replacedKeys = setOf(
            LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE,
            LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED,
            LyricMetadataKeys.APPLE_SUPPLEMENT_REVOKED,
            LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT,
            LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE,
            LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE_STATUSES,
        )
        val metadataEntries = buildList<Pair<String, String?>> {
            addAll(
                song.metadata.orEmpty().entries
                    .filterNot { it.key in replacedKeys }
                    .map { it.key to it.value }
            )
            add(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE to "apple")
            add(LyricMetadataKeys.APPLE_NATIVE_LYRICS_CONFIRMED to "true")
            if (supplementRevoked) add(LyricMetadataKeys.APPLE_SUPPLEMENT_REVOKED to "true")
        }
        return song.copy(metadata = lyricMetadataOf(*metadataEntries.toTypedArray()))
    }

    internal fun shouldPromoteLegacyCachedLyricsAsMissingSupplement(song: Song): Boolean {
        if (song.lyrics.isNullOrEmpty()) return false
        if (!song.metadata?.getString(LyricMetadataKeys.APPLE_LYRICS_CACHE_SOURCE).isNullOrBlank()) {
            return false
        }
        return !song.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean()
    }

    @Synchronized
    /** 返回歌曲与翻译显示状态是否都已送达（内容未变视为已送达）。 */
    private fun setSong(song: Song?): Boolean {
        lastSong = song
        val contentUnchanged = song == lastPublishedSong
        val sent = if (contentUnchanged) {
            true
        } else {
            val publishResult = player?.setSong(song) ?: false
            if (publishResult) {
                lastPublishedSong = song
            }
            publishResult
        }
        val displayTranslation = PreferencesMonitor.isTranslationSelected()
        var translationSent = true
        if (lastPublishedDisplayTranslation != displayTranslation) {
            translationSent = player?.setDisplayTranslation(displayTranslation) ?: false
            if (translationSent) {
                lastPublishedDisplayTranslation = displayTranslation
            }
        }
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            val reason = when {
                !sent -> "bridge_unavailable"
                contentUnchanged -> "duplicate_song_content_skipped"
                else -> "song_sent_to_bridge"
            }
            ProviderLogger.debug(
                "PlaybackManager: Sent song id=${song?.id}, success=$sent, " +
                    "contentUnchanged=$contentUnchanged, " +
                    "secondaryLines=${song?.lyrics?.count { !it.secondary.isNullOrBlank() } ?: 0}, " +
                    "secondaryWordLines=${song?.lyrics?.count { !it.secondaryWords.isNullOrEmpty() } ?: 0}, " +
                    "displayTranslation=$displayTranslation, displayTranslationSuccess=$translationSent"
            )
            AppleMetadataFlowDiagnostics.record("provider_publish") {
                "sent=$sent contentUnchanged=$contentUnchanged currentId=$currentSongId " +
                    "song=${AppleMetadataFlowDiagnostics.provider(song)}"
            }
            logDisplayDiagnostic(
                song = song,
                result = if (sent) "published" else "skipped",
                reason = reason,
                extra = "contentUnchanged=$contentUnchanged, " +
                    "displayTranslation=$displayTranslation, translationSent=$translationSent",
            )
        }
        return sent && translationSent
    }

    private fun logDisplayDiagnostic(
        song: Song?,
        result: String,
        reason: String,
        extra: String = "",
    ) {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        val lyrics = song?.lyrics.orEmpty()
        val missingLyricsSupplement = song?.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SUPPLEMENT)
            .toBoolean()
        val missingLyricsSource = song?.metadata
            ?.getString(LyricMetadataKeys.APPLE_MISSING_LYRICS_SOURCE)
        val signature = listOf(
            song?.id,
            song?.name,
            song?.artist,
            lyrics.size,
            lyrics.count { !it.translation.isNullOrBlank() },
            missingLyricsSupplement,
            missingLyricsSource,
            result,
            reason,
            extra,
        ).joinToString("|")
        if (signature == lastDisplayDiagnosticSignature) return
        lastDisplayDiagnosticSignature = signature
        ProviderLogger.debug(
            "[DISPLAY_DIAG/AM] result=$result, reason=$reason, " +
                "songId=${sanitize(song?.id)}, title=${sanitize(song?.name)}, " +
                "artist=${sanitize(song?.artist)}, package=${Constants.APPLE_MUSIC_PACKAGE_NAME}, " +
                "duration=${song?.duration ?: 0L}, lyricLines=${lyrics.size}, " +
                "translatedLines=${lyrics.count { !it.translation.isNullOrBlank() }}, " +
                "backingLines=${lyrics.count { !it.secondary.isNullOrBlank() }}, " +
                "supplement=$missingLyricsSupplement, source=${sanitize(missingLyricsSource)}, " +
                extra
        )
    }

    private fun sanitize(value: String?): String = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(80)
        .orEmpty()
}
