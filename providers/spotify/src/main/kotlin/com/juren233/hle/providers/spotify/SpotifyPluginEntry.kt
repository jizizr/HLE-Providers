/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodResultCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

object SpotifyPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Spotify"
    /** 与核心仓库 LyricMetadataKeys.LYRICS_UNAVAILABLE 取值一致。 */
    private const val LYRICS_UNAVAILABLE_METADATA_KEY = "hleLyricsUnavailable"
    private const val TARGET_PACKAGE = "com.spotify.music"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.spotify"

    private val installed = AtomicBoolean(false)

    override fun install(host: OfficialProviderHost) {
        if (OfficialCoreHostGuard.isForeignCoreHost(host)) return
        require(host.packageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.packageName}"
        }
        if (host.processName != host.packageName) {
            Log.i(TAG, "跳过 Spotify 非主进程: process=${host.processName}")
            return
        }
        if (!installed.compareAndSet(false, true)) return

        val startup = SpotifyStartupCoordinator(host)
        val runtimeStarted = AtomicBoolean(false)
        startup.installLyricsHooks()
        startup.installLyricsClientHooks()
        host.hookApplication { application ->
            if (Application.getProcessName() != host.packageName) return@hookApplication
            if (!runtimeStarted.compareAndSet(false, true)) return@hookApplication
            val versionCode = runCatching {
                application.packageManager.getPackageInfo(host.packageName, 0).longVersionCode
            }.getOrDefault(0L)
            val exactProfile = SpotifyHookProfiles.exactProfileFor(versionCode)
            if (exactProfile != null) {
                Log.i(
                    TAG,
                    "Spotify 歌词档案: ${exactProfile.versionName} (${exactProfile.versionCode})",
                )
                startup.selectProfile(exactProfile)
                startup.installLyricsHooks()
                startup.installLyricsClientHooks()
            } else {
                // 未知版本：install 阶段预装的兜底档案 Hook 若类名对不上已失败，
                // 这里改用注解锚链式解析（端点值跨版本稳定且全 App 唯一），
                // 成功后以链式档案安装，与兜底重合的目标按键自动去重。
                Log.i(
                    TAG,
                    "Spotify 版本 $versionCode 无已验证歌词档案，进入注解锚链式解析",
                )
                startup.installDynamicLyricsChain(application, versionCode)
            }
            val createdRuntime = SpotifyRuntime(application, host).also(SpotifyRuntime::start)
            startup.attach(createdRuntime)
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                OfficialCoreHostGuard.onPlaybackStateChanged(state)
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderPlaybackStateCallback
                startup.onPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderMetadataCallback
                startup.onMetadata(metadata)
            },
        )
        Log.i(TAG, "Spotify Provider Hook 已安装")
    }

    private class SpotifyStartupCoordinator(
        private val host: OfficialProviderHost,
    ) {
        private val stateLock = Any()
        private val lyricsHookInstallLock = Any()
        private val lyricsClientHookInstallLock = Any()
        private val hookInstallationState = SpotifyHookInstallationState()
        private val firstLyricsClientHits = mutableMapOf<SpotifyLyricsEndpoint, AtomicBoolean>()
        private val firstLyricsEndpointSelectionHit = AtomicBoolean(false)
        private val firstLyricsEndpointTrafficHit = AtomicBoolean(false)
        private val firstLyricsRequestHit = AtomicBoolean(false)
        private val firstLyricsHit = AtomicBoolean(false)
        private val dynamicChainStarted = AtomicBoolean(false)
        private val lyricsClientSelector = SpotifyLyricsClientSelector<Any>()
        private val pending = SpotifyStartupBuffer<
            MediaMetadata,
            SpotifyLyricsPayload,
            PlaybackState,
        >(MAX_STARTUP_LYRICS_CACHE_SIZE, SpotifyLyricsPayload::trackUri)

        private var activeRuntime: SpotifyRuntime? = null
        private var pendingLyricsClient: SpotifySelectedLyricsClient<Any>? = null

        @Volatile
        private var profile: SpotifyVersionProfile = SpotifyHookProfiles.profileFor(0L)

        fun selectProfile(profile: SpotifyVersionProfile) {
            this.profile = profile
        }

        private fun currentProfile(): SpotifyVersionProfile = profile

        fun installLyricsHooks() {
            synchronized(lyricsHookInstallLock) {
                currentProfile().lyricsRequests.forEach { request ->
                    val target = request.target
                    val targetKey = "${target.className}#${target.methodName}"
                    if (!hookInstallationState.needsRequestTarget(target)) return@forEach
                    runCatching {
                        host.hookMethodResult(
                            target = target,
                            callback = OfficialProviderMethodResultCallback { receiver, arguments, result ->
                                if (firstLyricsRequestHit.compareAndSet(false, true)) {
                                    Log.i(
                                        TAG,
                                        "Spotify color-lyrics 方法结果 Hook 首次命中: " +
                                            "target=${receiver?.javaClass?.name ?: target.className}",
                                    )
                                }
                                // 请求命中即证明 Spotify 当前实际使用的 endpoint，
                                // 供开关 Hook 不可用档案（如 9.1.80）选择客户端。
                                onLyricsEndpointTrafficObserved(request.endpoint)
                                SpotifySingleSuccessObserver.wrap(
                                    result = result,
                                    trackUri = arguments.getOrNull(0) as? String,
                                    onSuccess = { trackUri, lyricsResult ->
                                        val payload = SpotifyLyricsPayloadExtractor.extract(
                                            trackUri,
                                            lyricsResult,
                                        )
                                        if (payload == null) {
                                            if (BuildConfig.DEBUG) {
                                                Log.w(TAG, "Spotify 异步歌词成功值解析失败")
                                            }
                                            return@wrap
                                        }
                                        if (firstLyricsHit.compareAndSet(false, true)) {
                                            Log.i(
                                                TAG,
                                                "Spotify color-lyrics 异步成功首次命中: " +
                                                    "lines=${payload.lines.size}, " +
                                                    "translations=${payload.translations.size}, " +
                                                    "syncType=${payload.syncType}",
                                            )
                                        }
                                        onLyricsPayload(payload)
                                    },
                                    onObserverFailure = { error ->
                                        if (BuildConfig.DEBUG) {
                                            Log.w(TAG, "Spotify 异步歌词观察回调失败", error)
                                        }
                                    },
                                )
                            },
                        )
                    }.onSuccess {
                        hookInstallationState.recordRequestTarget(target)
                        Log.i(
                            TAG,
                            "Spotify color-lyrics 请求结果 Hook 已安装: $targetKey",
                        )
                    }.onFailure { error ->
                        Log.e(
                            TAG,
                            "Spotify color-lyrics 请求结果 Hook 安装失败，等待生命周期阶段重试: " +
                                "$targetKey",
                            error,
                        )
                    }
                }
            }
        }

        fun installLyricsClientHooks() {
            synchronized(lyricsClientHookInstallLock) {
                currentProfile().lyricsClientConstructors.forEach { profile ->
                    if (!hookInstallationState.needsClientConstructor(profile)) return@forEach
                    runCatching {
                        host.hookAfterConstructor(
                            target = profile.target,
                            callback = OfficialProviderConstructorCallback { instance, _ ->
                                instance?.let { onLyricsClient(profile.endpoint, it) }
                            },
                        )
                    }.onSuccess {
                        hookInstallationState.recordClientConstructor(profile)
                        Log.i(
                            TAG,
                            "Spotify ${profile.endpoint} 歌词客户端构造 Hook 已安装: " +
                                profile.target.className,
                        )
                    }.onFailure { error ->
                        Log.e(
                            TAG,
                            "Spotify ${profile.endpoint} 歌词客户端构造 Hook 安装失败，" +
                                "等待生命周期阶段重试",
                            error,
                        )
                    }
                }

                val selectionTarget = currentProfile().lyricsEndpointSelection
                if (selectionTarget == null) {
                    if (hookInstallationState.shouldLogMissingEndpointSelector()) {
                        Log.i(
                            TAG,
                            "当前歌词档案无 endpoint 开关 Hook，" +
                                "活动 endpoint 由请求结果 Hook 命中观测",
                        )
                    }
                    return
                }
                if (hookInstallationState.needsEndpointSelector(selectionTarget)) {
                    runCatching {
                        host.hookMethodResult(
                            target = selectionTarget,
                            callback = OfficialProviderMethodResultCallback { _, _, result ->
                                (result as? Boolean)?.let { enableV3 ->
                                    onLyricsEndpointSelected(
                                        SpotifyLyricsEndpoint.fromEnableV3(enableV3),
                                    )
                                }
                                result
                            },
                        )
                    }.onSuccess {
                        hookInstallationState.recordEndpointSelector(selectionTarget)
                        Log.i(
                            TAG,
                            "Spotify 歌词 endpoint 选择 Hook 已安装: " +
                                "${selectionTarget.className}#${selectionTarget.methodName}",
                        )
                    }.onFailure { error ->
                        Log.e(
                            TAG,
                            "Spotify 歌词 endpoint 选择 Hook 安装失败，等待生命周期阶段重试",
                            error,
                        )
                    }
                }
            }
        }

        /**
         * 未知宿主版本的注解锚链式解析：端点注解值 -> 服务接口 -> 持有该接口
         * 字段的包装类请求方法。解析与 Hook 安装都在核心的 Provider 线程完成，
         * 一次注册，核心自带缓存、自修复与看门狗。
         */
        fun installDynamicLyricsChain(application: Application, versionCode: Long) {
            if (!dynamicChainStarted.compareAndSet(false, true)) return
            runCatching {
                host.resolveDexMethods(
                    application = application,
                    queries = SpotifyHookProfiles.lyricsChainQueries,
                ) { targets ->
                    val profile = SpotifyHookProfiles.chainProfile(versionCode, targets)
                    if (profile == null) {
                        Log.e(
                            TAG,
                            "Spotify 链式解析结果数量异常: " +
                                "expected=${SpotifyHookProfiles.lyricsChainQueries.size}, " +
                                "actual=${targets.size}",
                        )
                        return@resolveDexMethods
                    }
                    val v3 = profile.lyricsRequests
                        .first { it.endpoint == SpotifyLyricsEndpoint.V3 }.target.className
                    val v2 = profile.lyricsRequests
                        .first { it.endpoint == SpotifyLyricsEndpoint.V2 }.target.className
                    Log.i(TAG, "Spotify 链式解析成功: v3=$v3, v2=$v2")
                    selectProfile(profile)
                    installLyricsHooks()
                    installLyricsClientHooks()
                }
            }.onFailure { error ->
                Log.e(TAG, "Spotify 链式解析注册失败，保持兜底档案 Hook", error)
            }
        }

        fun attach(runtime: SpotifyRuntime) {
            val startupState = synchronized(stateLock) {
                if (activeRuntime != null) return
                activeRuntime = runtime
                pending.drain() to pendingLyricsClient.also { pendingLyricsClient = null }
            }
            runtime.postStartupSnapshot(startupState.first, startupState.second)
        }

        fun onMetadata(metadata: MediaMetadata?) {
            val runtime = synchronized(stateLock) {
                activeRuntime ?: run {
                    pending.onMetadata(metadata)
                    return
                }
            }
            runtime.postMetadata(metadata)
        }

        fun onPlaybackState(state: PlaybackState?) {
            val runtime = synchronized(stateLock) {
                activeRuntime ?: run {
                    pending.onPlaybackState(state)
                    return
                }
            }
            runtime.postPlaybackState(state)
        }

        private fun onLyricsPayload(payload: SpotifyLyricsPayload) {
            val runtime = synchronized(stateLock) {
                activeRuntime ?: run {
                    pending.onLyrics(payload)
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            TAG,
                            "Spotify 启动阶段歌词已暂存: track=${payload.trackUri}, " +
                                "lines=${payload.lines.size}",
                        )
                    }
                    return
                }
            }
            runtime.postLyricsPayload(payload)
        }

        private fun onLyricsClient(
            endpoint: SpotifyLyricsEndpoint,
            client: Any,
        ) {
            val firstHit = synchronized(firstLyricsClientHits) {
                firstLyricsClientHits.getOrPut(endpoint, ::AtomicBoolean)
            }
            if (firstHit.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "Spotify $endpoint 歌词客户端构造 Hook 首次命中: ${client.javaClass.name}",
                )
            }
            lyricsClientSelector.onClientAvailable(endpoint, client)?.let(::onSelectedLyricsClient)
        }

        private fun onLyricsEndpointSelected(endpoint: SpotifyLyricsEndpoint) {
            if (firstLyricsEndpointSelectionHit.compareAndSet(false, true)) {
                Log.i(TAG, "Spotify 歌词 endpoint 首次选择: $endpoint")
            } else if (BuildConfig.DEBUG) {
                Log.i(TAG, "Spotify 歌词 endpoint 选择: $endpoint")
            }
            lyricsClientSelector.onEndpointSelected(endpoint)?.let(::onSelectedLyricsClient)
        }

        private fun onLyricsEndpointTrafficObserved(endpoint: SpotifyLyricsEndpoint) {
            if (firstLyricsEndpointTrafficHit.compareAndSet(false, true)) {
                Log.i(TAG, "Spotify 歌词 endpoint 请求命中观测: $endpoint")
            }
            lyricsClientSelector.onEndpointTrafficObserved(endpoint)
                ?.let(::onSelectedLyricsClient)
        }

        private fun onSelectedLyricsClient(selection: SpotifySelectedLyricsClient<Any>) {
            Log.i(
                TAG,
                "Spotify 歌词客户端已就绪: endpoint=${selection.endpoint}, " +
                    "class=${selection.client.javaClass.name}",
            )
            val runtime = synchronized(stateLock) {
                activeRuntime ?: run {
                    pendingLyricsClient = selection
                    return
                }
            }
            runtime.postLyricsClient(selection)
        }
    }

    private class SpotifyRuntime(
        private val application: Application,
        private val host: OfficialProviderHost,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val firstQueueDexCallbackHit = AtomicBoolean(false)
        private val firstQueueAccessorCallbackHit = AtomicBoolean(false)
        private val queueValidation = SpotifyHookValidationTracker()
        private val queueExtractionInProgress = ThreadLocal<Boolean>()
        private val lyricsCache = object : LinkedHashMap<String, SpotifyLyricsPayload>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, SpotifyLyricsPayload>?,
            ): Boolean = size > MAX_LYRICS_CACHE_SIZE
        }

        private var currentTrack: SpotifyTrackMetadata? = null
        private var latestQueueSnapshot: SpotifyQueueSnapshot? = null
        private var lastPublishedSongKey: String? = null
        private val providerPublisher = SpotifyProviderPublisher<Song>(
            songSender = { song -> provider?.player?.setSong(song) == true },
            controlSender = { frame -> provider?.player?.sendText(frame) == true },
        )
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L
        private val connectionListener = object : ConnectionListener {
            override fun onConnected(provider: LyriconProvider) {
                postCurrentSongReplay(provider, source = "connected")
            }

            override fun onReconnected(provider: LyriconProvider) {
                postCurrentSongReplay(provider, source = "reconnected")
            }

            override fun onDisconnected(provider: LyriconProvider) = Unit

            override fun onConnectTimeout(provider: LyriconProvider) = Unit
        }
        private val lyricsFallback = SpotifyLyricsFallbackCoordinator(
            scheduler = SpotifyHandlerLyricsFallbackScheduler(mainHandler),
            requestDelayMs = LYRICS_FALLBACK_DELAY_MS,
            requestStarter = SpotifyLyricsFallbackRequestStarter<Any> { client, trackUri, success, error ->
                SpotifyLyricsClientRequester.start(
                    client = client,
                    trackUri = trackUri,
                    onSuccess = { value -> mainHandler.post { success(value) } },
                    onError = { failure -> mainHandler.post { error(failure) } },
                )
            },
            onSuccess = { trackUri, result ->
                val payload = SpotifyLyricsPayloadExtractor.extract(trackUri, result)
                if (payload == null) {
                    Log.w(TAG, "Spotify 主动歌词成功值解析失败: track=$trackUri")
                    // 主动请求只有一次机会，解析不出歌词即最终结果。
                    publishLyricsUnavailable(trackUri)
                } else {
                    Log.i(
                        TAG,
                        "Spotify 主动歌词请求成功: track=$trackUri, lines=${payload.lines.size}",
                    )
                    onLyricsPayload(payload)
                }
            },
            onFailure = { trackUri, error ->
                Log.w(TAG, "Spotify 主动歌词请求失败: track=$trackUri", error)
                publishLyricsUnavailable(trackUri)
            },
            onScheduled = { trackUri, delayMs ->
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "Spotify 主动歌词兜底已安排: track=$trackUri, delayMs=$delayMs")
                }
            },
            onRequestStarted = { trackUri ->
                Log.i(TAG, "Spotify 内部接口歌词请求开始: track=$trackUri")
            },
        )

        private val nextTrackHeartbeat = object : Runnable {
            override fun run() {
                publishAlignedNextTrack()
                mainHandler.postDelayed(this, NEXT_TRACK_HEARTBEAT_MS)
            }
        }

        @Volatile
        var provider: LyriconProvider? = null
            private set

        fun start() {
            val createdProvider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = host.packageName,
            )
            provider = createdProvider
            // Provider SDK auto-syncs through a single SONG/TEXT cache slot. The callback posts
            // our independent Song replay after the whole listener dispatch, so listener order
            // cannot let a next-track control frame remain as the final restored lyric value.
            createdProvider.service.addConnectionListener(connectionListener)
            createdProvider.player.setDisplayTranslation(true)
            createdProvider.player.setDisplayRoma(false)
            createdProvider.register()
            installQueueHook()
            mainHandler.removeCallbacks(nextTrackHeartbeat)
            mainHandler.post(nextTrackHeartbeat)
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            Log.i(
                TAG,
                "Spotify Lyricon Provider 已注册: version=${packageInfo.versionName}/" +
                    "${packageInfo.longVersionCode}, process=${Application.getProcessName()}",
            )
        }

        fun postMetadata(metadata: MediaMetadata?) {
            mainHandler.post { onMetadata(metadata) }
        }

        fun postPlaybackState(state: PlaybackState?) {
            mainHandler.post { provider?.player?.setPlaybackState(state) }
        }

        fun postLyricsPayload(payload: SpotifyLyricsPayload) {
            mainHandler.post { onLyricsPayload(payload) }
        }

        fun postLyricsClient(selection: SpotifySelectedLyricsClient<Any>) {
            mainHandler.post {
                Log.i(TAG, "Spotify 内部歌词接口启用: endpoint=${selection.endpoint}")
                lyricsFallback.onClientAvailable(selection.client)
            }
        }

        fun postStartupSnapshot(
            snapshot: SpotifyStartupSnapshot<
                MediaMetadata,
                SpotifyLyricsPayload,
                PlaybackState,
            >,
            lyricsClient: SpotifySelectedLyricsClient<Any>?,
        ) {
            mainHandler.post {
                lyricsClient?.let { selection ->
                    Log.i(TAG, "Spotify 启动阶段内部歌词接口启用: endpoint=${selection.endpoint}")
                    lyricsFallback.onClientAvailable(selection.client)
                }
                if (snapshot.metadataReceived) onMetadata(snapshot.metadata)
                snapshot.lyrics.forEach(::onLyricsPayload)
                if (snapshot.playbackStateReceived) {
                    provider?.player?.setPlaybackState(snapshot.playbackState)
                }
            }
        }

        private fun installQueueHook() {
            val dexCallback = queueCallback(
                source = "dexkit_state_mapper",
                firstCallbackHit = firstQueueDexCallbackHit,
                reportDexValidation = true,
            )
            val accessorCallback = queueCallback(
                source = "auto_value_next_tracks",
                firstCallbackHit = firstQueueAccessorCallbackHit,
                reportDexValidation = false,
            )
            host.hookAfterDexMethod(
                application = application,
                query = SpotifyHookProfiles.queueStateQuery,
                callback = dexCallback,
            )
            runCatching {
                host.hookAfterMethod(
                    target = SpotifyHookProfiles.nextTracksAccessorTarget,
                    callback = accessorCallback,
                )
            }.onSuccess {
                Log.i(TAG, "Spotify nextTracks 精确辅助 Hook 已安装")
            }.onFailure { error ->
                Log.e(TAG, "Spotify nextTracks 精确辅助 Hook 安装失败", error)
            }
            Log.i(TAG, "Spotify nextTracks DexKit 主 Hook 与精确辅助 Hook 安装流程已启动")
        }

        private fun queueCallback(
            source: String,
            firstCallbackHit: AtomicBoolean,
            reportDexValidation: Boolean,
        ) = OfficialProviderMethodCallback { receiver, arguments ->
            if (BuildConfig.DEBUG && firstCallbackHit.compareAndSet(false, true)) {
                Log.i(TAG, "Spotify nextTracks Hook 首次真实回调: source=$source")
            }
                if (queueExtractionInProgress.get() == true) return@OfficialProviderMethodCallback
                queueExtractionInProgress.set(true)
                val snapshot = try {
                    SpotifyQueueExtractor.extract(arguments.getOrNull(0) ?: receiver)
                } finally {
                    queueExtractionInProgress.remove()
                }
                if (snapshot == null) {
                    if (reportDexValidation) {
                        reportHookValidation(
                            cacheKey = SpotifyHookProfiles.queueStateQuery.cacheKey,
                            tracker = queueValidation,
                            valid = false,
                            detail = "snapshot=null",
                        )
                    }
                    return@OfficialProviderMethodCallback
                }
                val valid = snapshot.current.id.isNotBlank() && snapshot.current.title.isNotBlank()
                if (reportDexValidation) {
                    reportHookValidation(
                        cacheKey = SpotifyHookProfiles.queueStateQuery.cacheKey,
                        tracker = queueValidation,
                        valid = valid,
                        detail = "current=${snapshot.current.id}, next=${snapshot.next?.id}",
                    )
                }
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "Spotify nextTracks 快照: source=$source, current=${snapshot.current.id}, " +
                            "next=${snapshot.next?.id}",
                    )
                }
                mainHandler.post { onQueueSnapshot(snapshot) }
        }

        private fun reportHookValidation(
            cacheKey: String,
            tracker: SpotifyHookValidationTracker,
            valid: Boolean,
            detail: String,
        ) {
            val reportInvalid = tracker.record(valid)
            if ((valid && BuildConfig.DEBUG) || reportInvalid) {
                host.reportDexMethodValidation(cacheKey, valid, detail)
            }
        }

        private fun onMetadata(value: MediaMetadata?) {
            if (value == null) {
                lyricsFallback.clearTrack()
                currentTrack = null
                lastPublishedSongKey = null
                providerPublisher.clearSong()
                publishNextTrack(null, null)
                return
            }
            val track = SpotifyTrackMetadata(
                mediaId = value.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            if (track.mediaId.isNullOrBlank() && track.title.isNullOrBlank()) return
            val sameTrack = SpotifyTrackIdentity.sameTrack(currentTrack, track)
            currentTrack = track
            if (!sameTrack) {
                lyricsFallback.onTrackChanged(track.mediaId)
                publishPlaceholder(track)
            } else {
                lyricsFallback.onTrackMetadataUpdated(track.mediaId)
                if (lastPublishedSongKey == null) publishPlaceholder(track)
            }
            publishCachedLyrics()
            publishAlignedNextTrack()
        }

        private fun onLyricsPayload(payload: SpotifyLyricsPayload) {
            SpotifyTrackIdentity.candidates(payload.trackUri).forEach { id -> lyricsCache[id] = payload }
            publishCachedLyrics()
        }

        private fun onQueueSnapshot(snapshot: SpotifyQueueSnapshot) {
            latestQueueSnapshot = snapshot
            val aligned = SpotifyQueueBinding.align(currentTrack, snapshot)
            publishNextTrack(currentTrack, aligned)
        }

        private fun publishCachedLyrics() {
            val metadata = currentTrack ?: return
            val ids = SpotifyTrackIdentity.candidates(metadata.mediaId)
            val payload = ids.asSequence().mapNotNull(lyricsCache::get).firstOrNull() ?: return
            if (payload.lines.size > 1) {
                lyricsFallback.onTrackMetadataUpdated(payload.trackUri)
                lyricsFallback.onLyricsAvailable(payload.trackUri)
            }
            val lines = SpotifyLyricsTimelineMapper.map(payload, metadata.durationMs)
            if (lines.isEmpty()) return
            val songKey = "lyrics:${payload.trackUri}:${payload.lines.hashCode()}"
            if (songKey == lastPublishedSongKey) return
            publishSong(Song().apply {
                id = payload.trackUri
                name = metadata.title
                artist = metadata.artist
                duration = metadata.durationMs.takeIf { it > 0L } ?: lines.last().end
                lyrics = lines.map { line ->
                    RichLyricLine().apply {
                        begin = line.begin
                        end = line.end
                        duration = (line.end - line.begin).coerceAtLeast(0L)
                        text = line.text
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
            lastPublishedSongKey = songKey
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "Spotify 歌词已发布: track=${payload.trackUri}, lines=${lines.size}, " +
                        "syllables=${lines.sumOf { it.words.size }}",
                )
            }
        }

        /** 主动取词已结束且当前曲目没有歌词时，在占位歌曲上打「歌词确认缺失」标记。 */
        private fun publishLyricsUnavailable(trackUri: String) {
            val track = currentTrack ?: return
            if (SpotifyTrackIdentity.requestUri(track.mediaId) != trackUri) return
            if (lastPublishedSongKey?.startsWith("lyrics:") == true) return
            val id = SpotifyTrackIdentity.candidates(track.mediaId).firstOrNull()
                ?: "spotify:${track.title.orEmpty()}:${track.artist.orEmpty()}"
            val songKey = "unavailable:$id"
            if (songKey == lastPublishedSongKey) return
            publishSong(Song().apply {
                this.id = id
                name = track.title
                artist = track.artist
                duration = track.durationMs.coerceAtLeast(0L)
                metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                    mapOf(LYRICS_UNAVAILABLE_METADATA_KEY to "true"),
                )
            })
            lastPublishedSongKey = songKey
        }

        private fun publishPlaceholder(track: SpotifyTrackMetadata) {
            val id = SpotifyTrackIdentity.candidates(track.mediaId).firstOrNull()
                ?: "spotify:${track.title.orEmpty()}:${track.artist.orEmpty()}"
            val songKey = "placeholder:$id:${track.title}:${track.artist}:${track.durationMs}"
            if (songKey == lastPublishedSongKey) return
            publishSong(Song().apply {
                this.id = id
                name = track.title
                artist = track.artist
                duration = track.durationMs.coerceAtLeast(0L)
            })
            lastPublishedSongKey = songKey
        }

        private fun publishSong(song: Song) {
            providerPublisher.publishSong(song)
        }

        private fun postCurrentSongReplay(
            connectedProvider: LyriconProvider,
            source: String,
        ) {
            mainHandler.post {
                val replay = providerPublisher.replaySong(
                    connectedProvider.player::setSong,
                ) ?: return@post
                Log.i(
                    TAG,
                    "Spotify 当前 Song 连接重放: source=$source, " +
                        "id=${replay.first.id}, success=${replay.second}",
                )
            }
        }

        private fun publishAlignedNextTrack() {
            val metadata = currentTrack
            val snapshot = SpotifyQueueBinding.align(metadata, latestQueueSnapshot)
            publishNextTrack(metadata, snapshot)
        }

        private fun publishNextTrack(
            metadata: SpotifyTrackMetadata?,
            snapshot: SpotifyQueueSnapshot?,
        ) {
            val current = snapshot?.current
            val currentId = current?.id?.takeIf(String::isNotBlank)
                ?: metadata?.mediaId.orEmpty()
            val currentTitle = current?.title?.takeIf(String::isNotBlank)
                ?: metadata?.title.orEmpty()
            val currentArtist = current?.artist?.takeIf(String::isNotBlank)
                ?: metadata?.artist.orEmpty()
            val next = snapshot?.next
            val frame = when {
                metadata == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                next == null || next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = currentId,
                        currentTitle = currentTitle,
                        currentArtist = currentArtist,
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = currentId,
                    currentTitle = currentTitle,
                    currentArtist = currentArtist,
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
            if (providerPublisher.publishControlFrame(frame)) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
            }
        }
    }

    internal class SpotifyHookValidationTracker(
        private val invalidThreshold: Int = 3,
    ) {
        private var consecutiveInvalid = 0

        init {
            require(invalidThreshold > 0)
        }

        @Synchronized
        fun record(valid: Boolean): Boolean {
            if (valid) {
                consecutiveInvalid = 0
                return false
            }
            consecutiveInvalid += 1
            if (consecutiveInvalid < invalidThreshold) return false
            consecutiveInvalid = 0
            return true
        }
    }

    internal class SpotifyStartupBuffer<Metadata, Lyrics, Playback>(
        private val maxLyrics: Int,
        private val lyricsKey: (Lyrics) -> String,
    ) {
        private val pendingLyrics = object : LinkedHashMap<String, Lyrics>(
            maxLyrics,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Lyrics>?,
            ): Boolean = size > maxLyrics
        }

        private var metadataReceived = false
        private var metadata: Metadata? = null
        private var playbackStateReceived = false
        private var playbackState: Playback? = null

        init {
            require(maxLyrics > 0)
        }

        fun onMetadata(value: Metadata?) {
            metadataReceived = true
            metadata = value
        }

        fun onLyrics(value: Lyrics) {
            pendingLyrics[lyricsKey(value)] = value
        }

        fun onPlaybackState(value: Playback?) {
            playbackStateReceived = true
            playbackState = value
        }

        fun drain(): SpotifyStartupSnapshot<Metadata, Lyrics, Playback> {
            val snapshot = SpotifyStartupSnapshot(
                metadataReceived = metadataReceived,
                metadata = metadata,
                lyrics = pendingLyrics.values.toList(),
                playbackStateReceived = playbackStateReceived,
                playbackState = playbackState,
            )
            metadataReceived = false
            metadata = null
            pendingLyrics.clear()
            playbackStateReceived = false
            playbackState = null
            return snapshot
        }
    }

    internal class SpotifyProviderPublisher<Song>(
        private val songSender: (Song) -> Boolean,
        private val controlSender: (String) -> Boolean,
    ) {
        private var song: Song? = null

        fun publishSong(value: Song): Boolean {
            synchronized(this) {
                song = value
            }
            return songSender(value)
        }

        fun publishControlFrame(frame: String): Boolean = controlSender(frame)

        @Synchronized
        fun clearSong() {
            song = null
        }

        @Synchronized
        fun currentSong(): Song? = song

        fun replaySong(sender: (Song) -> Boolean): Pair<Song, Boolean>? {
            val current = currentSong() ?: return null
            return current to sender(current)
        }
    }

    internal data class SpotifyStartupSnapshot<Metadata, Lyrics, Playback>(
        val metadataReceived: Boolean,
        val metadata: Metadata?,
        val lyrics: List<Lyrics>,
        val playbackStateReceived: Boolean,
        val playbackState: Playback?,
    )

    private const val MAX_LYRICS_CACHE_SIZE = 64
    private const val MAX_STARTUP_LYRICS_CACHE_SIZE = 8
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L
    // 交付门禁已改为构造捕获即默认交付（v2），主动兜底不再依赖歌词页流量；
    // 该延迟因此就是后台拿词的最终延迟，取 200ms 跟进切歌。被动结果仍会
    // 照常接管，发布按 songKey 去重，抢先请求只是多一次拉取。
    private const val LYRICS_FALLBACK_DELAY_MS = 200L
}
