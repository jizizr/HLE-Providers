/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaPlugin
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs entirely in SystemUI. Playback identity comes from the public MediaSession;
 * lyric content is fetched from Qishui's PC track API.
 */
object QishuiPluginEntry : OfficialProviderSystemMediaPlugin {
    private const val TAG = "HLEProvider/Qishui"
    /** 与核心仓库 LyricMetadataKeys.LYRICS_UNAVAILABLE 取值一致。 */
    private const val LYRICS_UNAVAILABLE_METADATA_KEY = "hleLyricsUnavailable"
    private const val TARGET_PACKAGE = "com.luna.music"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.qishui"

    @Volatile
    private var runtime: QishuiRuntime? = null

    override fun installSystemMedia(host: OfficialProviderSystemMediaHost) {
        if (OfficialCoreHostGuard.isForeignCoreHost(host)) return
        require(host.playerPackageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.playerPackageName}"
        }
        val newRuntime = QishuiRuntime(host)
        newRuntime.start()
        runtime = newRuntime
    }

    override fun releaseSystemMedia() {
        runtime?.stop()
        runtime = null
    }

    private class QishuiRuntime(
        private val host: OfficialProviderSystemMediaHost,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-Qishui-Lyrics").apply { isDaemon = true }
        }
        private val lyricCache = Collections.synchronizedMap(
            object : LinkedHashMap<String, QishuiLyricPayload>(32, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, QishuiLyricPayload>?,
                ): Boolean = size > MAX_LYRIC_CACHE_SIZE
            },
        )

        @Volatile
        private var currentRequestKey: String? = null

        private var retryRunnable: Runnable? = null
        private var retryAttempt = 0

        private var lastPublishedSongKey: String? = null

        private val carLyricsPolicy = CarLyricsMetadataPolicy()

        @Volatile
        private var provider: LyriconProvider? = null

        private var subscription:
            com.juren233.hyperlyricsenhanced.provider.OfficialProviderSystemMediaSubscription? = null

        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "汽水 SystemMedia Provider 必须在主线程启动"
            }
            provider = LyriconFactory.createProvider(
                context = host.application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = host.playerPackageName,
            ).also {
                it.player.setDisplayTranslation(true)
                it.player.setDisplayRoma(false)
                it.register()
            }
            subscription = host.subscribe(
                OfficialProviderSystemMediaCallback { metadata, state ->
                    OfficialCoreHostGuard.onPlaybackStateChanged(state)
                    if (OfficialCoreHostGuard.isDeactivated()) {
                        return@OfficialProviderSystemMediaCallback
                    }
                    onMediaChanged(metadata, state)
                },
            )
            Log.i(
                TAG,
                "汽水音乐 SystemMedia Provider 已注册: host=${host.application.packageName}",
            )
        }

        fun stop() {
            cancelRetry()
            subscription?.release()
            subscription = null
            executor.shutdownNow()
            provider?.unregister()
            provider?.destroy()
            provider = null
            currentRequestKey = null
            lyricCache.clear()
        }

        private fun onMediaChanged(
            metadata: MediaMetadata?,
            state: PlaybackState?,
        ) {
            provider?.player?.setPlaybackState(state)
            if (metadata == null) {
                cancelRetry()
                currentRequestKey = null
                lastPublishedSongKey = null
                return
            }
            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            val normalized = carLyricsPolicy.normalize(
                id = mediaId.orEmpty(),
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            )
            val track = QishuiTrackMetadata(
                mediaId = mediaId,
                title = normalized.title,
                artist = normalized.artist,
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            if (track.mediaId.isNullOrBlank() && track.title.isNullOrBlank()) return
            val trackId = QishuiTrackIdentity.apiTrackId(track.mediaId)
            val requestKey = trackId ?: buildString {
                append("metadata:")
                append(track.title.orEmpty())
                append('|')
                append(track.artist.orEmpty())
            }
            if (currentRequestKey == requestKey) return
            cancelRetry()
            retryAttempt = 0
            currentRequestKey = requestKey
            publishPlaceholder(track, trackId ?: requestKey)

            if (trackId == null) {
                Log.w(TAG, "系统 MediaSession 未提供可用的汽水 track_id: mediaId=${track.mediaId}")
                // 没有 track_id 就永远取不到歌词，结果即最终。
                publishLyricsUnavailable(track, requestKey)
                return
            }
            lyricCache[trackId]?.let { cached ->
                publishLyrics(track, cached)
                return
            }
            requestLyrics(track, trackId)
        }

        private fun requestLyrics(track: QishuiTrackMetadata, trackId: String) {
            executor.execute {
                runCatching { QishuiApiClient.fetch(trackId) }
                    .onSuccess { payload ->
                        lyricCache[trackId] = payload
                        mainHandler.post {
                            if (currentRequestKey == trackId) {
                                cancelRetry()
                                retryAttempt = 0
                                publishLyrics(track, payload)
                            }
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "汽水歌词网络请求失败: trackId=$trackId", error)
                        mainHandler.post {
                            if (currentRequestKey == trackId) {
                                // 服务端已明确答复没有歌词：结果确定，立即上报；重试节奏保持不变。
                                if (error is QishuiNoLyricsException) publishLyricsUnavailable(track, trackId)
                                scheduleRetry(track, trackId)
                            }
                        }
                    }
            }
        }

        private fun scheduleRetry(track: QishuiTrackMetadata, trackId: String) {
            cancelRetry()
            val delayMs = QishuiRetryPolicy.delayMs(retryAttempt++)
            // 连续失败达到上报次数（首次请求加第一次重试，约 15 秒）：上报歌词确认缺失；
            // 之后仍按原节奏继续重试，晚到的歌词会覆盖该状态。
            if (retryAttempt >= QishuiRetryPolicy.REPORT_AFTER_FAILURES) publishLyricsUnavailable(track, trackId)
            retryRunnable = Runnable {
                retryRunnable = null
                if (currentRequestKey == trackId && lyricCache[trackId] == null) {
                    requestLyrics(track, trackId)
                }
            }.also { mainHandler.postDelayed(it, delayMs) }
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "汽水歌词将在 ${delayMs}ms 后自动重试: trackId=$trackId, " +
                        "attempt=$retryAttempt",
                )
            }
        }

        private fun cancelRetry() {
            retryRunnable?.let(mainHandler::removeCallbacks)
            retryRunnable = null
        }

        private fun publishPlaceholder(track: QishuiTrackMetadata, id: String) {
            val key = "placeholder:$id:${track.title}:${track.artist}:${track.durationMs}"
            if (key == lastPublishedSongKey) return
            provider?.player?.setSong(Song().apply {
                this.id = id
                name = track.title
                artist = track.artist
                duration = track.durationMs.coerceAtLeast(0L)
            })
            lastPublishedSongKey = key
        }

        /** 在占位歌曲上打「歌词确认缺失」标记；请求已切到别的曲目时不发。 */
        private fun publishLyricsUnavailable(track: QishuiTrackMetadata, id: String) {
            if (currentRequestKey != id) return
            val key = "unavailable:$id"
            if (key == lastPublishedSongKey) return
            provider?.player?.setSong(Song().apply {
                this.id = id
                name = track.title
                artist = track.artist
                duration = track.durationMs.coerceAtLeast(0L)
                metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                    mapOf(LYRICS_UNAVAILABLE_METADATA_KEY to "true"),
                )
            })
            lastPublishedSongKey = key
        }

        private fun publishLyrics(
            track: QishuiTrackMetadata,
            payload: QishuiLyricPayload,
        ) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val lines = QishuiLyricsParser.parse(payload, track.durationMs)
            if (lines.isEmpty()) {
                // 接口已答复但没有可用歌词，结果即最终。
                publishLyricsUnavailable(track, payload.trackId)
                return
            }
            val key = "lyrics:${payload.trackId}:${payload.content.hashCode()}:" +
                "${payload.translations.hashCode()}:${payload.timeline.hashCode()}"
            if (key == lastPublishedSongKey) return
            provider?.player?.setSong(Song().apply {
                id = payload.trackId
                name = track.title
                artist = track.artist
                duration = track.durationMs.takeIf { it > 0L } ?: lines.last().end
                lyrics = lines.map { line ->
                    RichLyricLine().apply {
                        begin = line.begin
                        end = line.end
                        duration = (line.end - line.begin).coerceAtLeast(0L)
                        text = line.text
                        translation = line.translation
                        words = line.words.map { word ->
                            LyricWord().apply {
                                begin = word.begin
                                end = word.end
                                duration = (word.end - word.begin).coerceAtLeast(0L)
                                text = word.text
                            }
                        }.takeIf(List<LyricWord>::isNotEmpty)
                    }
                }
            })
            lastPublishedSongKey = key
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "汽水网络歌词已发布: trackId=${payload.trackId}, lines=${lines.size}, " +
                        "source=${payload.source}, " +
                        "translations=${payload.translations.map { it.language }}",
                )
            }
        }
    }

    private const val MAX_LYRIC_CACHE_SIZE = 32
}

internal object QishuiRetryPolicy {
    private val delaysMs = longArrayOf(
        15_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L,
    )

    /** 连续失败多少次后上报歌词确认缺失：首次请求失败，加上 [delayMs] 第一档（15 秒）的重试也失败。 */
    const val REPORT_AFTER_FAILURES = 2

    fun delayMs(failureCount: Int): Long = delaysMs[
        failureCount.coerceAtLeast(0).coerceAtMost(delaysMs.lastIndex)
    ]
}

internal data class QishuiTrackMetadata(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)
