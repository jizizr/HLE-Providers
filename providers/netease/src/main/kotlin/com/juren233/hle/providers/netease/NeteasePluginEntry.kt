/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * The MediaSession boundary follows the original LyricProvider implementation,
 * while the host-side Xposed calls remain in HyperLyrics Enhanced.
 */

package com.juren233.hle.providers.netease

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.netease.BuildConfig
import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodResultCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NeteasePluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Netease"
    private const val PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider.netease"
    /** 与核心仓库 LyricMetadataKeys.LYRICS_UNAVAILABLE 取值一致。 */
    private const val LYRICS_UNAVAILABLE_METADATA_KEY = "hleLyricsUnavailable"

    @Volatile
    private var runtime: NeteaseRuntime? = null
    private val appLyricsHookInstalled = AtomicBoolean(false)
    private val appLyricsCompatibilityLookupStarted = AtomicBoolean(false)
    private val appLyricsReadFailureLogged = AtomicBoolean(false)

    private fun ensureRuntime(
        host: OfficialProviderHost,
        application: Application? = null,
    ): NeteaseRuntime? {
        val existing = runtime
        if (existing != null) return existing

        val app = application ?: runCatching {
            val activityThreadClass = Class.forName(
                "android.app.ActivityThread",
                false,
                javaClass.classLoader,
            )
            activityThreadClass.getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull() ?: return null

        val process = Application.getProcessName()
        if (process != host.packageName && process != "${host.packageName}:play") return null

        return synchronized(this) {
            val doubleCheck = runtime
            if (doubleCheck != null) {
                doubleCheck
            } else {
                val packageInfo = runCatching {
                    app.packageManager.getPackageInfo(host.packageName, 0)
                }.getOrNull()
                val nextTrackProcessName = NeteaseNextTrackProfiles.nextTrackProcessName(
                    host.packageName,
                    packageInfo?.versionName.orEmpty(),
                    packageInfo?.longVersionCode ?: 0L,
                )
                val newRuntime = NeteaseRuntime(
                    application = app,
                    playerPackage = host.packageName,
                    enableNextTrack = process == nextTrackProcessName,
                    host = host,
                )
                runtime = newRuntime
                newRuntime.start()
                newRuntime
            }
        }
    }

    override fun install(host: OfficialProviderHost) {
        if (OfficialCoreHostGuard.isForeignCoreHost(host)) return
        require(host.packageName == "com.netease.cloudmusic" ||
            host.packageName == "com.hihonor.cloudmusic") {
            "Unsupported Netease package: ${host.packageName}"
        }

        host.hookApplication { application ->
            ensureRuntime(host, application)
            installAppLyricsHook(host, application)
        }

        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                OfficialCoreHostGuard.onPlaybackStateChanged(state)
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderPlaybackStateCallback
                ensureRuntime(host)?.onPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderMetadataCallback
                ensureRuntime(host)?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "网易云音乐 Provider Hook 已安装: package=${host.packageName}")
    }

    private fun installAppLyricsHook(host: OfficialProviderHost, application: Application) {
        val versionCode = runCatching {
            application.packageManager.getPackageInfo(host.packageName, 0).longVersionCode
        }.getOrNull() ?: return
        NeteaseAppLyricsProfile.targetFor(host.packageName, versionCode)?.let { target ->
            installAppLyricsTarget(host, versionCode, target, "verified_profile")
            return
        }
        val query = NeteaseAppLyricsProfile.compatibilityQueryFor(host.packageName, versionCode)
            ?: return
        if (!appLyricsCompatibilityLookupStarted.compareAndSet(false, true)) return
        runCatching {
            host.resolveDexMethods(
                application = application,
                queries = listOf(query),
                callback = OfficialProviderDexMethodsCallback { targets ->
                    if (targets.size == 1) {
                        installAppLyricsTarget(host, versionCode, targets.single(), "verified_dex")
                    } else {
                        Log.w(TAG, "网易云 App 歌词 DEX 目标数量错误: versionCode=$versionCode count=${targets.size}")
                    }
                },
            )
            Log.i(TAG, "网易云 App 歌词开始 DEX 兼容解析: versionCode=$versionCode")
        }.onFailure { error ->
            appLyricsCompatibilityLookupStarted.set(false)
            Log.w(TAG, "网易云 App 歌词 DEX 兼容解析未启动: versionCode=$versionCode", error)
        }
    }

    private fun installAppLyricsTarget(
        host: OfficialProviderHost,
        versionCode: Long,
        target: OfficialProviderMethodTarget,
        source: String,
    ) {
        if (!appLyricsHookInstalled.compareAndSet(false, true)) return
        runCatching {
            host.hookMethodResult(
                target,
                OfficialProviderMethodResultCallback { _, arguments, result ->
                    if (OfficialCoreHostGuard.isDeactivated()) {
                        return@OfficialProviderMethodResultCallback result
                    }
                    runCatching {
                        NeteaseAppLyricsReader.read(arguments.firstOrNull())
                    }.onSuccess { snapshot ->
                        if (snapshot != null) ensureRuntime(host)?.onAppLyrics(snapshot)
                    }.onFailure { error ->
                        if (appLyricsReadFailureLogged.compareAndSet(false, true)) {
                            Log.w(TAG, "读取网易云 App 歌词对象失败", error)
                        }
                    }
                    result
                },
            )
            Log.i(TAG, "网易云 App 歌词结果 Hook 已安装: versionCode=$versionCode source=$source")
        }.onFailure { error ->
            appLyricsHookInstalled.set(false)
            Log.w(TAG, "网易云 App 歌词结果 Hook 未安装: versionCode=$versionCode", error)
        }
    }

    private class NeteaseRuntime(
        private val application: Application,
        private val playerPackage: String,
        private val enableNextTrack: Boolean,
        private val host: OfficialProviderHost,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-Netease-Lyrics").apply { isDaemon = true }
        }
        private val appLyricsExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-Netease-AppLyrics").apply { isDaemon = true }
        }
        private val metadata = ConcurrentHashMap<Long, TrackMetadata>()
        private val cacheDir = File(application.filesDir, "hle-provider/netease")
        private val nextTrackScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "HLE-Netease-NextTrack").apply { isDaemon = true }
            }
        @Volatile
        private var currentId: Long? = null
        private var lastSong: Song? = null
        private val lyricSelection = NeteaseLyricSelection<NeteasePayload>()
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L
        private val diagnosticsEnabled = NeteaseDiagnosticCapability.resolve { host.isDiagnosticEnabled() }
        private val firstAppLyricsHit = if (diagnosticsEnabled) AtomicBoolean(false) else null
        private val playbackDiagnostics = if (diagnosticsEnabled) {
            NeteasePlaybackDiagnosticSampler()
        } else null
        private var lastMediaCardDiagnosticAtMs = 0L
        private val mainHandler = Handler(Looper.getMainLooper())
        private val nextTrackGeneration = AtomicLong(0L)
        private val nextTrackValidation = NeteaseNextTrackValidationTracker()

        @Volatile
        private var nextTrackTask: ScheduledFuture<*>? = null

        @Volatile
        private var latestPlaybackState: PlaybackState? = null

        @Volatile
        private var manualPositionSyncEnabled = false

        private val positionWriter = object : Runnable {
            override fun run() {
                if (!manualPositionSyncEnabled) {
                    reportWriterExit("manual_mode_disabled")
                    return
                }
                val state = latestPlaybackState ?: run {
                    reportWriterExit("no_state")
                    return
                }
                if (state.state != PlaybackState.STATE_PLAYING) {
                    reportWriterExit("not_playing")
                    return
                }
                val player = provider?.player ?: run {
                    reportWriterExit("player_unavailable")
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val position = extrapolatePlaybackPosition(
                    basePosition = state.position,
                    lastUpdateTime = state.lastPositionUpdateTime,
                    now = now,
                    playbackSpeed = state.playbackSpeed,
                    playing = true,
                )
                val result = runCatching { player.setPosition(position) }.getOrDefault(false)
                if (diagnosticsEnabled) {
                    val callback = playbackDiagnostics?.currentSequence ?: 0L
                    playbackDiagnostics?.sampleWrite(now, callback, result)?.let { sampleReason ->
                        emitPlaybackDiagnostic(
                            "[LyricPositionDiag] stage=provider_manual_position_write, " +
                                "callback=$callback, providerBuild=${BuildConfig.VERSION_CODE}, sampleReason=$sampleReason, " +
                                "result=$result, position=$position, state=${state.state}, " +
                                "anchor=${state.position}, updatedAt=${state.lastPositionUpdateTime}, " +
                                "anchorAgeMs=${now - state.lastPositionUpdateTime}, speed=${state.playbackSpeed}, " +
                                "currentId=$currentId, currentState=${latestPlaybackState === state}, " +
                                "writerEnabled=$manualPositionSyncEnabled, playerActive=${runCatching { player.isActive }.getOrNull()}",
                        )
                    }
                }
                if (manualPositionSyncEnabled && latestPlaybackState === state) {
                    mainHandler.postDelayed(this, NETEASE_POSITION_UPDATE_INTERVAL_MS)
                } else {
                    reportWriterExit("mode_or_state_changed_during_write")
                }
            }
        }

        @Volatile
        private var currentTrack: TrackMetadata? = null

        private val carLyricsPolicy = CarLyricsMetadataPolicy()

        private var nextTrackResolver: NeteaseNextTrackResolver? = null

        @Volatile
        private var nextTrackValidationKeys: List<String> = emptyList()

        @Volatile
        var provider: LyriconProvider? = null
            private set

        fun start() {
            if (diagnosticsEnabled) reportMediaCardDiagnostic(
                stage = "provider",
                event = "runtime_start",
                details = "process=${Application.getProcessName()},playerPackage=$playerPackage,enableNextTrack=$enableNextTrack",
            )
            cacheDir.mkdirs()
            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = playerPackage,
            ).also {
                applyDisplayPreference(it, application)
                it.register()
            }
            NeteasePluginEntry.runtime = this
            if (diagnosticsEnabled) {
                val message = "[LyricPositionDiag] stage=provider_runtime_ready, " +
                        "providerBuild=${BuildConfig.VERSION_CODE}, providerVersion=${BuildConfig.VERSION_NAME}, diagnosticsEnabled=true, " +
                        "process=${Application.getProcessName()}, " +
                        "providerAvailable=${provider != null}, " +
                        "playerActive=${runCatching { provider?.player?.isActive }.getOrNull()}"
                Log.i(TAG, message)
                host.reportDiagnostic(TAG, message)
            }
            if (enableNextTrack) {
                runCatching { startNextTrackCapture() }
                    .onFailure { error -> Log.w(TAG, "网易云下一首查询未启动，歌词服务继续运行", error) }
            }
            Log.i(TAG, "网易云音乐 Lyricon Provider 已注册: process=${Application.getProcessName()}")
        }

        fun onMetadata(value: MediaMetadata?) {
            val id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.toLongOrNull()
            if (diagnosticsEnabled) reportMediaCardDiagnostic(
                stage = "provider",
                event = "metadata_callback",
                details = "incomingId=$id,currentId=$currentId,title=${sanitize(value?.getString(MediaMetadata.METADATA_KEY_TITLE))},artist=${sanitize(value?.getString(MediaMetadata.METADATA_KEY_ARTIST))},duration=${value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L},playerActive=${runCatching { provider?.player?.isActive }.getOrNull()}",
            )
            if (id == null) {
                currentId = null
                currentTrack = null
                if (diagnosticsEnabled) reportMediaCardDiagnostic(
                    stage = "provider",
                    event = "metadata_cleared",
                    reason = "missing_media_id",
                )
                requestNextTrackCapture()
                return
            }
            val normalized = carLyricsPolicy.normalize(
                id = id.toString(),
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
            )
            val track = TrackMetadata(
                id = id,
                title = normalized.title,
                artist = normalized.artist,
                duration = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            currentTrack = track
            requestNextTrackCapture()
            metadata[id] = track
            if (currentId == id) {
                if (diagnosticsEnabled) reportMediaCardDiagnostic(
                    stage = "provider",
                    event = "metadata_ignored",
                    reason = "same_track_id",
                    details = "id=$id",
                )
                return
            }
            val previousId = currentId
            currentId = id
            if (diagnosticsEnabled) reportMediaCardDiagnostic(
                stage = "provider",
                event = "track_changed",
                details = "previousId=$previousId,currentId=$id",
            )
            loadCachedPayload(track)
                ?.takeIf(::hasUsableLyrics)
                ?.let { lyricSelection.setApi(id, it) }
            publishSelected(track)
            executor.execute {
                runCatching { NeteaseClient.fetch(id) }
                    .onSuccess { payload ->
                        val usable = hasUsableLyrics(payload)
                        if (usable) writeCache(id, payload)
                        lyricSelection.setApi(id, payload.takeIf { usable })
                        val isCurrent = currentId == id
                        if (diagnosticsEnabled) reportMediaCardDiagnostic(
                            stage = "provider",
                            event = "lyrics_fetch_complete",
                            details = "id=$id,current=$isCurrent,usable=$usable," +
                                "lines=${payload.lrc?.lines()?.size ?: 0}," +
                                "translated=${payload.translated?.lines()?.size ?: 0}",
                        )
                        if (isCurrent) publishSelected(track)
                        // 接口已答复且没有可用歌词（含纯音乐）：本 Provider 没有重试，视为确认缺失。
                        if (isCurrent && !usable) publishUnavailable(track)
                    }
                    .onFailure { error ->
                        Log.w(TAG, "网易云歌词下载失败: id=$id", error)
                        // 网络失败同样没有重试，结果即最终。
                        if (currentId == id) publishUnavailable(track)
                    }
            }
        }

        fun onAppLyrics(snapshot: NeteaseAppLyricsSnapshot) {
            if (firstAppLyricsHit?.compareAndSet(false, true) == true) {
                reportMediaCardDiagnostic(
                    stage = "provider",
                    event = "app_lyrics_first_hit",
                    details = "id=${snapshot.musicId},currentId=$currentId," +
                        "lrcLength=${snapshot.lrc?.length ?: 0},yrcLength=${snapshot.yrc?.length ?: 0}",
                )
            }
            val payload = NeteasePayload(
                lrc = snapshot.lrc,
                translated = snapshot.lrcTranslation,
                yrc = snapshot.yrc,
                yrcTranslated = snapshot.yrcTranslation,
                roma = snapshot.yrcRomanization ?: snapshot.lrcRomanization,
                pureMusic = false,
            )
            appLyricsExecutor.execute {
                if (!hasUsableLyrics(payload)) return@execute
                if (!lyricSelection.setApp(snapshot.musicId, payload)) return@execute
                val track = metadata[snapshot.musicId] ?: return@execute
                publishSelected(track)
            }
        }

        @Synchronized
        private fun publishSelected(track: TrackMetadata) {
            if (currentId != track.id) return
            val selected = lyricSelection.select(track.id)
            val song = selected?.value?.let { toSong(track, it) } ?: placeholder(track)
            if (currentId != track.id) return
            if (diagnosticsEnabled) reportMediaCardDiagnostic(
                stage = "provider",
                event = "lyric_source_selected",
                details = "id=${track.id},source=${selected?.source ?: "none"}," +
                    "lines=${song.lyrics?.size ?: 0}",
            )
            publish(song)
        }

        fun onPlaybackState(state: PlaybackState?) {
            val diagnosticSequence = if (diagnosticsEnabled) playbackDiagnostics?.nextSequence() ?: 0L else 0L
            reportPlaybackInput(state, diagnosticSequence)
            if (diagnosticsEnabled) reportMediaCardDiagnostic(
                stage = "provider",
                event = "playback_callback",
                details = "state=${state?.state},position=${state?.position},updatedAt=${state?.lastPositionUpdateTime},speed=${state?.playbackSpeed},currentId=$currentId,playerActive=${runCatching { provider?.player?.isActive }.getOrNull()}",
            )
            manualPositionSyncEnabled = false
            latestPlaybackState = state
            mainHandler.removeCallbacks(positionWriter)

            val player = provider?.player
            val playerActive = if (diagnosticsEnabled) runCatching { player?.isActive }.getOrNull() else null
            var autoFailure: Throwable? = null
            val autoResult = runCatching { player?.setPlaybackState(state) }
                .onFailure { if (diagnosticsEnabled) autoFailure = it }
                .getOrNull()

            val forwardingMode = neteasePlaybackForwardingMode(
                hasState = state != null,
                buffering = state?.state == PlaybackState.STATE_BUFFERING,
                automaticAccepted = autoResult,
            )
            if (forwardingMode != NeteasePlaybackForwardingMode.MANUAL_FALLBACK) {
                // The legacy boolean overload disables Central's timestamped PlaybackState.
                // Keep it only as a compatibility fallback when automatic forwarding fails;
                // otherwise an off-screen track change can reuse the previous shared position.
                reportPlaybackForward(
                    diagnosticSequence = diagnosticSequence,
                    state = state,
                    playerAvailable = player != null,
                    playerActive = playerActive,
                    autoResult = autoResult,
                    manualResult = null,
                    intervalResult = null,
                    positionResult = null,
                    autoFailure = autoFailure,
                    decision = if (forwardingMode == NeteasePlaybackForwardingMode.AUTOMATIC) {
                        "automatic_anchor_preserved"
                    } else {
                        "preserve_buffering_anchor"
                    },
                )
                return
            }

            val playing = state?.state == PlaybackState.STATE_PLAYING
            var manualFailure: Throwable? = null
            val manualResult = runCatching { player?.setPlaybackState(playing) }
                .onFailure { if (diagnosticsEnabled) manualFailure = it }
                .getOrNull()
            val intervalResult = runCatching {
                player?.setPositionUpdateInterval(NETEASE_POSITION_UPDATE_INTERVAL_MS.toInt())
            }.getOrNull()
            val position = state?.let {
                extrapolatePlaybackPosition(
                    basePosition = it.position,
                    lastUpdateTime = it.lastPositionUpdateTime,
                    now = SystemClock.elapsedRealtime(),
                    playbackSpeed = it.playbackSpeed,
                    playing = playing,
                )
            } ?: 0L
            val positionResult = runCatching { player?.setPosition(position) }.getOrNull()

            if (playing) {
                manualPositionSyncEnabled = true
                mainHandler.post(positionWriter)
            }
            reportPlaybackForward(
                diagnosticSequence = diagnosticSequence,
                state = state,
                playerAvailable = player != null,
                playerActive = playerActive,
                autoResult = autoResult,
                manualResult = manualResult,
                intervalResult = intervalResult,
                positionResult = positionResult,
                autoFailure = autoFailure ?: manualFailure,
                decision = if (playing) "manual_position_sync_started" else "manual_position_sync_stopped",
            )
        }

        private fun reportPlaybackForward(
            diagnosticSequence: Long,
            state: PlaybackState?,
            playerAvailable: Boolean,
            playerActive: Boolean?,
            autoResult: Boolean?,
            manualResult: Boolean?,
            intervalResult: Boolean?,
            positionResult: Boolean?,
            autoFailure: Throwable?,
            decision: String,
        ) {
            if (!diagnosticsEnabled) return
            val now = SystemClock.elapsedRealtime()
            val details = "callback=$diagnosticSequence, providerBuild=${BuildConfig.VERSION_CODE}, " +
                "currentId=$currentId, currentState=${latestPlaybackState === state}, " +
                "runtimeAvailable=true, providerAvailable=${provider != null}, " +
                "playerAvailable=$playerAvailable, playerActive=$playerActive, " +
                "autoResult=$autoResult, manualResult=$manualResult, " +
                "intervalResult=$intervalResult, positionResult=$positionResult, " +
                "intervalMs=$NETEASE_POSITION_UPDATE_INTERVAL_MS, " +
                "state=${state?.state}, position=${state?.position}, speed=${state?.playbackSpeed}, " +
                "updatedAt=${state?.lastPositionUpdateTime}, anchorAgeMs=${state?.let { now - it.lastPositionUpdateTime }}, " +
                "writerEnabled=$manualPositionSyncEnabled, writerQueued=${mainHandler.hasCallbacks(positionWriter)}, " +
                "decision=$decision, failure=${autoFailure?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"}"
            emitPlaybackDiagnostic("[LyricPositionDiag] stage=provider_state_forward, $details")
            // Both automatic and fallback paths must expose a completion event in exported logs.
            if (diagnosticsEnabled) reportMediaCardDiagnostic("provider", "playback_forward_complete", details = details)
        }

        private fun reportPlaybackInput(state: PlaybackState?, sequence: Long) {
            if (!diagnosticsEnabled) return
            val now = SystemClock.elapsedRealtime()
            emitPlaybackDiagnostic(
                "[LyricPositionDiag] stage=provider_state_input, callback=$sequence, " +
                    "providerBuild=${BuildConfig.VERSION_CODE}, process=${Application.getProcessName()}, currentId=$currentId, " +
                    "state=${state?.state}, position=${state?.position}, updatedAt=${state?.lastPositionUpdateTime}, " +
                    "speed=${state?.playbackSpeed}, anchorAgeMs=${state?.let { now - it.lastPositionUpdateTime }}, " +
                    "previousState=${latestPlaybackState?.state}, previousPosition=${latestPlaybackState?.position}, " +
                    "writerWasEnabled=$manualPositionSyncEnabled, writerWasQueued=${mainHandler.hasCallbacks(positionWriter)}",
            )
        }

        private fun reportWriterExit(reason: String) {
            if (!diagnosticsEnabled) return
            val sampler = playbackDiagnostics ?: return
            val callback = sampler.currentSequence
            if (!sampler.sampleExit(callback, reason)) return
            emitPlaybackDiagnostic(
                "[LyricPositionDiag] stage=provider_manual_writer_exit, callback=$callback, " +
                    "providerBuild=${BuildConfig.VERSION_CODE}, reason=$reason, currentId=$currentId, " +
                    "writerEnabled=$manualPositionSyncEnabled, state=${latestPlaybackState?.state}",
            )
        }

        private fun emitPlaybackDiagnostic(message: String) {
            if (!diagnosticsEnabled) return
            runCatching {
                Log.i(TAG, message)
                host.reportDiagnostic(TAG, message)
            }
        }

        private fun applyDisplayPreference(provider: LyriconProvider, context: Context) {
            val prefs = context.getSharedPreferences("com.netease.cloudmusic.preferences", Context.MODE_PRIVATE)
            applyDisplayPreference(provider, prefs)
            prefs.registerOnSharedPreferenceChangeListener { changed, key ->
                if (key == "showLyricSetting") applyDisplayPreference(provider, changed)
            }
        }

        private fun applyDisplayPreference(provider: LyriconProvider, prefs: SharedPreferences) {
            when (prefs.getInt("showLyricSetting", -1)) {
                0 -> {
                    provider.player.setDisplayTranslation(true)
                    provider.player.setDisplayRoma(false)
                }
                1 -> {
                    provider.player.setDisplayTranslation(false)
                    provider.player.setDisplayRoma(true)
                }
                else -> {
                    provider.player.setDisplayTranslation(false)
                    provider.player.setDisplayRoma(false)
                }
            }
        }

        private fun publish(song: Song) {
            if (lastSong == song) {
                if (diagnosticsEnabled) reportMediaCardDiagnostic(
                    stage = "provider",
                    event = "song_publish_skipped",
                    reason = "same_song_object",
                    details = "songId=${song.id},lines=${song.lyrics?.size ?: 0}",
                )
                return
            }
            lastSong = song
            val player = provider?.player
            val playerActive = if (diagnosticsEnabled) runCatching { player?.isActive }.getOrNull() else null
            var failure: Throwable? = null
            val forwarded = runCatching { player?.setSong(song) }
                .onFailure { if (diagnosticsEnabled) failure = it }
                .getOrNull()
            if (diagnosticsEnabled) {
                val message = "[LyricPositionDiag] stage=provider_song_forward, " +
                        "playerAvailable=${player != null}, playerActive=$playerActive, " +
                        "result=$forwarded, songId=${song.id}, lyrics=${song.lyrics?.size ?: 0}, " +
                        "failure=${failure?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"}"
                Log.i(TAG, message)
                host.reportDiagnostic(TAG, message)
            }
            if (diagnosticsEnabled) reportMediaCardDiagnostic(
                stage = "provider",
                event = "song_publish_complete",
                details = "songId=${song.id},lines=${song.lyrics?.size ?: 0},playerActive=$playerActive,result=$forwarded",
            )
        }

        private fun reportMediaCardDiagnostic(
            stage: String,
            event: String,
            reason: String? = null,
            details: String = "",
        ) {
            if (!diagnosticsEnabled) return
            val now = runCatching { SystemClock.elapsedRealtime() }
                .getOrElse { System.nanoTime() / 1_000_000L }
            if (event == "playback_callback" &&
                now - lastMediaCardDiagnosticAtMs < POSITION_DIAGNOSTIC_INTERVAL_MS
            ) return
            if (event == "playback_callback") lastMediaCardDiagnosticAtMs = now
            val message = buildString {
                append("[MEDIA_CARD_DIAG] stage=").append(stage)
                append(" event=").append(event)
                reason?.takeIf { it.isNotBlank() }?.let { append(" reason=").append(it) }
                if (details.isNotBlank()) append(" ").append(details)
            }
            Log.i(TAG, message)
            host.reportDiagnostic(TAG, message)
        }

        private fun sanitize(value: String?): String = value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            ?.replace(',', ';')
            .orEmpty()

        private fun startNextTrackCapture() {
            val queries = NeteaseNextTrackResolver.queries(application)
            nextTrackValidationKeys = queries.map { it.cacheKey }
            host.resolveDexMethods(
                application = application,
                queries = queries,
                callback = OfficialProviderDexMethodsCallback { targets ->
                    mainHandler.post { finishNextTrackSetup(targets) }
                },
            )
        }

        @Synchronized
        private fun finishNextTrackSetup(targets: List<OfficialProviderMethodTarget>) {
            nextTrackTask?.cancel(false)
            nextTrackTask = null
            nextTrackResolver = null
            nextTrackValidation.reset()
            val resolver = runCatching {
                NeteaseNextTrackResolver.create(application, targets)
            }.onFailure { error ->
                Log.w(TAG, "网易云下一首解析器校验失败", error)
                reportNextTrackValidation(
                    valid = false,
                    detail = "resolver_validation:${error::class.java.simpleName}: ${error.message}",
                )
            }.getOrNull() ?: return
            nextTrackResolver = resolver
            val generation = nextTrackGeneration.incrementAndGet()
            nextTrackTask = nextTrackScheduler.scheduleWithFixedDelay(
                { captureNextTrack(generation) },
                0L,
                NEXT_TRACK_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun requestNextTrackCapture() {
            val generation = nextTrackGeneration.get()
            if (nextTrackResolver != null && nextTrackTask != null) {
                nextTrackScheduler.execute { captureNextTrack(generation) }
            }
        }

        private fun captureNextTrack(generation: Long) {
            if (nextTrackGeneration.get() != generation) return
            val resolver = nextTrackResolver ?: return
            val current = currentTrack
            runCatching {
                val resolvedNext = resolver.resolve()
                if (nextTrackGeneration.get() != generation) return
                val candidateMatchesCurrent = resolvedNext?.let { candidate ->
                    NeteaseNextTrackCandidatePolicy.isCurrent(
                        currentId = current?.id,
                        currentTitle = current?.title,
                        currentArtist = current?.artist,
                        candidate = candidate,
                    )
                } == true
                val next = resolvedNext?.takeUnless { candidateMatchesCurrent }
                if (candidateMatchesCurrent) {
                    if (nextTrackValidation.record(candidateMatchesCurrent = true)) {
                        reportNextTrackValidation(
                            valid = false,
                            detail = "candidate_matches_current:${resolvedNext?.id}",
                        )
                        stopNextTrackCapture(generation)
                    }
                } else {
                    nextTrackValidation.record(candidateMatchesCurrent = false)
                    reportNextTrackValidation(
                        valid = true,
                        detail = "next=${next?.id ?: "none"}",
                    )
                }
                publishNextTrack(current, next)
                next
            }
                .onFailure { error ->
                    if (nextTrackGeneration.get() != generation) return@onFailure
                    reportNextTrackValidation(
                        valid = false,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                    )
                    stopNextTrackCapture(generation)
                    if (diagnosticsEnabled) Log.w(TAG, "网易云下一首采集失败", error)
                }
        }

        private fun reportNextTrackValidation(valid: Boolean, detail: String) {
            if (valid && !diagnosticsEnabled) return
            nextTrackValidationKeys.forEach { key ->
                host.reportDexMethodValidation(key, valid, detail)
            }
        }

        @Synchronized
        private fun stopNextTrackCapture(generation: Long) {
            if (nextTrackGeneration.get() != generation) return
            nextTrackGeneration.incrementAndGet()
            nextTrackTask?.cancel(false)
            nextTrackTask = null
            nextTrackResolver = null
        }

        private fun publishNextTrack(current: TrackMetadata?, next: NeteaseNextTrackSnapshot?) {
            val frame = when {
                current == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                next == null || next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = current.id.toString(),
                        currentTitle = current.title.orEmpty(),
                        currentArtist = current.artist.orEmpty(),
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.id.toString(),
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                    nextId = next.id,
                    nextTitle = next.title,
                    nextArtist = next.artist,
                    nextAlbum = next.album,
                    nextDurationMs = next.durationMs,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (
                frame == lastNextTrackFrame &&
                now - lastNextTrackFrameSentAtMs < NEXT_TRACK_HEARTBEAT_MS
            ) {
                return
            }
            if (provider?.player?.sendText(frame) == true) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
                if (diagnosticsEnabled) {
                    Log.i(
                        TAG,
                        "网易云下一首控制帧已发送: current=${current?.id}, next=${next?.id}",
                    )
                }
            }
        }

        private fun placeholder(track: TrackMetadata): Song = Song().apply {
            id = track.id.toString()
            name = track.title
            artist = track.artist
            duration = track.duration
        }

        /** 取词彻底结束且没有任何歌词时，在占位歌曲上打「歌词确认缺失」标记；已有歌词（含 App 自带）则不发。 */
        @Synchronized
        private fun publishUnavailable(track: TrackMetadata) {
            if (currentId != track.id) return
            if (lyricSelection.select(track.id) != null) return
            publish(placeholder(track).apply {
                metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                    mapOf(LYRICS_UNAVAILABLE_METADATA_KEY to "true"),
                )
            })
        }

        private fun loadCachedPayload(track: TrackMetadata): NeteasePayload? {
            val file = File(cacheDir, "${track.id}.json")
            if (!file.isFile) return null
            return runCatching { NeteasePayload.fromJson(JSONObject(file.readText())) }.getOrNull()
        }

        private fun writeCache(id: Long, payload: NeteasePayload) {
            runCatching {
                File(cacheDir, "$id.json").writeText(payload.toJson().toString())
            }.onFailure { Log.w(TAG, "网易云歌词缓存写入失败: id=$id", it) }
        }
    }

    private data class TrackMetadata(
        val id: Long,
        val title: String?,
        val artist: String?,
        val duration: Long,
    )

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L
    private const val POSITION_DIAGNOSTIC_INTERVAL_MS = 5_000L

    private data class NeteasePayload(
        val lrc: String?,
        val translated: String?,
        val yrc: String?,
        val yrcTranslated: String?,
        val roma: String?,
        val pureMusic: Boolean,
    ) {
        fun toJson() = JSONObject().apply {
            putOpt("lrc", lrc)
            putOpt("translated", translated)
            putOpt("yrc", yrc)
            putOpt("yrcTranslated", yrcTranslated)
            putOpt("roma", roma)
            put("pureMusic", pureMusic)
        }

        companion object {
            fun fromJson(json: JSONObject) = NeteasePayload(
                lrc = json.optString("lrc").takeIf(String::isNotBlank),
                translated = json.optString("translated").takeIf(String::isNotBlank),
                yrc = json.optString("yrc").takeIf(String::isNotBlank),
                yrcTranslated = json.optString("yrcTranslated").takeIf(String::isNotBlank),
                roma = json.optString("roma").takeIf(String::isNotBlank),
                pureMusic = json.optBoolean("pureMusic", false),
            )
        }
    }

    private object NeteaseClient {
        private const val URL = "https://interface.music.163.com/eapi/song/lyric/v1"
        private const val KEY = "e82ckenh8dichen8"
        private const val SALT = "nobody%suse%smd5forencrypt"

        fun fetch(id: Long): NeteasePayload {
            val params = JSONObject().apply {
                put("id", id.toString())
                put("cp", false)
                put("lv", 0)
                put("tv", 0)
                put("rv", 0)
                put("yv", 0)
                put("ytv", 0)
                put("yrv", 0)
            }.toString()
            val path = "/eapi/song/lyric/v1"
            val apiPath = path.replace("eapi", "api")
            val digest = md5(String.format(SALT, apiPath, params))
            val text = "$apiPath-36cd479b6b5-$params-36cd479b6b5-$digest"
            val encrypted = aes(text, KEY).uppercase()
            val connection = (java.net.URI(URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://music.163.com/")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            return try {
                val body = "params=${URLEncoder.encode(encrypted, "UTF-8")}"
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "网易云 HTTP ${connection.responseCode}"
                }
                fromJson(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
            } finally {
                connection.disconnect()
            }
        }

        private fun fromJson(json: JSONObject): NeteasePayload {
            fun lyric(name: String): String? = json.optJSONObject(name)?.optString("lyric")
                ?.takeIf(String::isNotBlank)
            return NeteasePayload(
                lrc = lyric("lrc"),
                translated = lyric("tlyric"),
                yrc = lyric("yrc"),
                yrcTranslated = lyric("ytlrc"),
                roma = lyric("romalrc"),
                pureMusic = json.optBoolean("pureMusic", false),
            )
        }

        private fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun aes(value: String, key: String): String {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"))
            return cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private data class TimelineLine(
        val begin: Long,
        val end: Long,
        val text: String,
        val words: List<LyricWord> = emptyList(),
    )

    private object TimelineParser {
        private val yrcHeader = Regex("\\[(\\d+),(\\d+)]")
        private val lrcTime = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")

        fun parseYrc(raw: String?): List<TimelineLine> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.lineSequence().mapNotNull { line ->
                val header = yrcHeader.find(line) ?: return@mapNotNull null
                val start = header.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val duration = header.groupValues[2].toLongOrNull() ?: 0L
                val words = NeteaseYrcWordParser.parse(
                    line.substring(header.range.last + 1),
                ).map { segment ->
                    LyricWord().apply {
                        this.begin = segment.begin
                        this.end = segment.begin + segment.duration
                        this.duration = segment.duration
                        this.text = NeteaseInlineTimestampSanitizer.strip(segment.text)
                    }
                }
                TimelineLine(start, start + duration, words.joinToString("") { it.text.orEmpty() }, words)
            }.sortedBy(TimelineLine::begin).toList()
        }

        fun parseLrc(raw: String?): List<TimelineLine> {
            if (raw.isNullOrBlank()) return emptyList()
            val result = mutableListOf<TimelineLine>()
            raw.lineSequence().forEach { line ->
                val matches = lrcTime.findAll(line).toList()
                if (matches.isEmpty() || matches.first().range.first != 0) return@forEach
                val content = NeteaseInlineTimestampSanitizer.strip(
                    line.substring(matches.last().range.last + 1),
                ).trim()
                if (isNeteaseSectionMarker(content)) return@forEach
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = match.groupValues.getOrNull(3).orEmpty()
                    val millis = when (fraction.length) {
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        3 -> fraction.toLong()
                        else -> 0L
                    }
                    result += TimelineLine(minutes * 60_000 + seconds * 1_000 + millis, 0L, content)
                }
            }
            return result.sortedBy(TimelineLine::begin).mapIndexed { index, line ->
                val next = result.sortedBy(TimelineLine::begin).getOrNull(index + 1)?.begin
                line.copy(end = next ?: line.begin + 5_000L)
            }
        }
    }

    private fun toSong(track: TrackMetadata, payload: NeteasePayload): Song {
        val source = TimelineParser.parseYrc(payload.yrc).ifEmpty { TimelineParser.parseLrc(payload.lrc) }
        val translations = TimelineParser.parseLrc(payload.yrcTranslated).ifEmpty {
            TimelineParser.parseLrc(payload.translated)
        }
        val romas = TimelineParser.parseLrc(payload.roma)
        val rich = source.map { line ->
            RichLyricLine().apply {
                begin = line.begin
                end = line.end
                duration = (line.end - line.begin).coerceAtLeast(0L)
                text = line.text
                words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                translation = closest(translations, line.begin)?.text
                roma = closest(romas, line.begin)?.text
            }
        }
        return Song().apply {
            id = track.id.toString()
            name = track.title
            artist = track.artist
            duration = track.duration.takeIf { it > 0 } ?: rich.lastOrNull()?.end ?: 0L
            lyrics = rich.takeIf { it.isNotEmpty() && !payload.pureMusic }
        }
    }

    private fun hasUsableLyrics(payload: NeteasePayload): Boolean {
        if (payload.pureMusic) return false
        val source = TimelineParser.parseYrc(payload.yrc)
            .ifEmpty { TimelineParser.parseLrc(payload.lrc) }
        return NeteaseLyricContentPolicy.hasMeaningfulText(source.map(TimelineLine::text))
    }

    private fun closest(lines: List<TimelineLine>, position: Long): TimelineLine? = lines
        .minByOrNull { kotlin.math.abs(it.begin - position) }
        ?.takeIf { kotlin.math.abs(it.begin - position) <= 1_000L }
}

internal object NeteaseNextTrackCandidatePolicy {
    fun isCurrent(
        currentId: Long?,
        currentTitle: String?,
        currentArtist: String?,
        candidate: NeteaseNextTrackSnapshot,
    ): Boolean {
        val candidateId = candidate.id.toLongOrNull()
        if (currentId != null && currentId > 0L && candidateId != null && candidateId > 0L) {
            return currentId == candidateId
        }
        val title = normalize(currentTitle)
        if (title.isEmpty()) return false
        return title == normalize(candidate.title) &&
            normalize(currentArtist) == normalize(candidate.artist)
    }

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
}

internal class NeteaseNextTrackValidationTracker(
    private val invalidThreshold: Int = 3,
) {
    private var consecutiveCurrentCandidates = 0

    init {
        require(invalidThreshold > 0)
    }

    @Synchronized
    fun record(candidateMatchesCurrent: Boolean): Boolean {
        if (!candidateMatchesCurrent) {
            consecutiveCurrentCandidates = 0
            return false
        }
        consecutiveCurrentCandidates += 1
        if (consecutiveCurrentCandidates < invalidThreshold) return false
        consecutiveCurrentCandidates = 0
        return true
    }

    @Synchronized
    fun reset() {
        consecutiveCurrentCandidates = 0
    }
}

/** 网易云 LRC 中的纯段落标记（如 [Intro]、[Chorus]）不是可唱歌词行。 */
internal fun isNeteaseSectionMarker(content: String): Boolean =
    content.matches(Regex("\\[[^\\[\\]\\d][^\\[\\]]*]"))
