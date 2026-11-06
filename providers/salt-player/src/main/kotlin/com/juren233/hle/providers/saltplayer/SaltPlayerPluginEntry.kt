/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import android.app.Application
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.saltplayer.BuildConfig
import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderApplicationCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object SaltPlayerPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/SaltPlayer"
    /** 与核心仓库 LyricMetadataKeys.LYRICS_UNAVAILABLE 取值一致。 */
    private const val LYRICS_UNAVAILABLE_METADATA_KEY = "hleLyricsUnavailable"
    private const val TARGET_PACKAGE = "com.salt.music"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.salt-player"

    /** 主模块远端 Hook 配置键，与 HyperLyrics-Enhanced 的 RootConstants 保持一致。 */
    private const val KEY_ONLINE_TRANSLATION_APP_SALT = "key_hook_online_translation_app_salt"
    private const val KEY_ONLINE_TRANSLATION_SALT_PREFER_ONLINE =
        "key_hook_online_translation_salt_prefer_online"

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var provider: LyriconProvider? = null

    @Volatile
    private var runtime: SaltPlayerRuntime? = null

    override fun install(host: OfficialProviderHost) {
        if (OfficialCoreHostGuard.isForeignCoreHost(host)) return
        require(host.packageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.packageName}"
        }
        host.hookApplication(OfficialProviderApplicationCallback { application ->
            if (Application.getProcessName() != host.packageName) return@OfficialProviderApplicationCallback
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = SaltPlayerHookProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            )
            val usesNativeLyricon = SaltPlayerHookProfiles.usesNativeLyricon(
                packageInfo.versionName.orEmpty(),
            )
            if (usesNativeLyricon) {
                Log.i(
                    TAG,
                    "椒盐音乐 ${packageInfo.versionName} 已原生适配 Lyricon，跳过旧版歌词 Pack",
                )
                return@OfficialProviderApplicationCallback
            }
            if (!initialized.compareAndSet(false, true)) return@OfficialProviderApplicationCallback

            runCatching {
                provider = LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = PROVIDER_PACKAGE,
                    playerPackageName = host.packageName,
                ).apply {
                    register()
                }
                runtime = provider?.let {
                    SaltPlayerRuntime(
                        application = application,
                        provider = it,
                        nextTrackProfile = profile,
                        host = host,
                    )
                }
            }.onSuccess {
                val version = "${packageInfo.versionName}(${packageInfo.longVersionCode})"
                Log.i(TAG, "椒盐音乐旧版歌词 Provider 已注册: version=$version")
            }.onFailure { error ->
                provider = null
                runtime = null
                initialized.set(false)
                Log.e(TAG, "椒盐音乐 Provider 注册失败", error)
            }
        })
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                OfficialCoreHostGuard.onPlaybackStateChanged(state)
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderPlaybackStateCallback
                provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderMetadataCallback
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "椒盐音乐 Provider 生命周期 Hook 已安装")
    }

    private class SaltPlayerRuntime(
        private val application: Application,
        private val provider: LyriconProvider,
        private val nextTrackProfile: SaltPlayerHookProfile?,
        private val host: OfficialProviderHost,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val localLyricsResolver = SaltPlayerMediaStoreResolver(application)
        private val localLyricsExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-SaltPlayer-LocalLyrics").apply { isDaemon = true }
        }
        private val localLyricsGeneration = AtomicLong(0L)
        private var metadata = SaltPlayerTrackMetadata()
        private var document: SaltPlayerLyricsDocument? = null
        private var lastSong: io.github.proify.lyricon.lyric.model.Song? = null
        private var lastLocalLyricsRequestKey: String? = null
        private var pendingLocalLyricsTask: Future<*>? = null
        private var nextTrackResolver: SaltPlayerNextTrackResolver? = null
        private var nextTrackValidationCacheKey: String? = null
        private var consecutiveQueueDecodeFailures = 0
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L
        private val requestedNextTrackCapture = Runnable(::captureNextTrack)
        private val periodicNextTrackCapture = object : Runnable {
            override fun run() {
                if (nextTrackResolver == null) return
                captureNextTrack()
                if (nextTrackResolver != null) {
                    mainHandler.postDelayed(this, NEXT_TRACK_POLL_INTERVAL_MS)
                }
            }
        }

        fun startNextTrackCapture() {
            val profile = nextTrackProfile ?: return
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.e(TAG, "椒盐音乐下一首解析器必须在主线程初始化")
                return
            }
            val query = SaltPlayerNextTrackResolver.controllerFallbackQuery(profile)
            nextTrackValidationCacheKey = query.cacheKey
            host.resolveDexMethods(
                application = application,
                queries = listOf(query),
                callback = OfficialProviderDexMethodsCallback { targets ->
                    val target = targets.singleOrNull()
                    mainHandler.post {
                        if (target == null) {
                            host.reportDexMethodValidation(
                                query.cacheKey,
                                valid = false,
                                detail = "resolved_targets=${targets.size}",
                            )
                            Log.w(TAG, "椒盐音乐 DexKit 控制器目标数量错误: ${targets.size}")
                            return@post
                        }
                        finishNextTrackSetup(profile, target.className, query.cacheKey)
                    }
                },
            )
        }

        private fun finishNextTrackSetup(
            profile: SaltPlayerHookProfile,
            controllerClassName: String,
            validationCacheKey: String?,
        ): Boolean {
            mainHandler.removeCallbacks(periodicNextTrackCapture)
            mainHandler.removeCallbacks(requestedNextTrackCapture)
            nextTrackResolver = null
            consecutiveQueueDecodeFailures = 0
            val resolver = runCatching {
                SaltPlayerNextTrackResolver.create(
                    application = application,
                    profile = profile,
                    controllerClassName = controllerClassName,
                )
            }.onFailure { error ->
                if (validationCacheKey != null) {
                    host.reportDexMethodValidation(
                        validationCacheKey,
                        valid = false,
                        detail = "setup_${error::class.java.simpleName}: ${error.message}",
                    )
                }
                Log.w(TAG, "椒盐音乐下一首解析器校验失败: class=$controllerClassName", error)
            }.getOrNull() ?: return false
            nextTrackResolver = resolver
            nextTrackValidationCacheKey = validationCacheKey
            mainHandler.post(periodicNextTrackCapture)
            Log.i(
                TAG,
                "椒盐音乐下一首采集已启动: class=$controllerClassName, " +
                    "source=dexkit",
            )
            return true
        }

        @Synchronized
        fun onMetadata(value: MediaMetadata?) {
            val next = SaltPlayerTrackMetadata(
                id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                mediaUri = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
                title = value?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
            if (metadata.identity != null && next.identity != metadata.identity) {
                document = null
                lastSong = null
                lastLocalLyricsRequestKey = null
            }
            metadata = next
            publishCurrent()
            requestLocalLyrics(next)
            requestNextTrackCapture()
        }

        @Synchronized
        private fun currentMetadata(): SaltPlayerTrackMetadata = metadata

        /** @param lookupFinished 本地文件歌词已读取完毕；此时仍没有歌词文档，就在占位歌曲上打「歌词确认缺失」标记。 */
        private fun publishCurrent(lookupFinished: Boolean = false) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { publishCurrent(lookupFinished) }
                return
            }
            val song = synchronized(this) {
                val currentDocument = document
                val mapped = if (currentDocument == null) {
                    SaltPlayerLyricsMapper.placeholder(metadata).also { placeholder ->
                        if (lookupFinished) {
                            placeholder.metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                                mapOf(LYRICS_UNAVAILABLE_METADATA_KEY to "true"),
                            )
                        }
                    }
                } else {
                    SaltPlayerLyricsMapper.map(currentDocument, metadata)
                }
                if (mapped == lastSong) return
                lastSong = mapped
                mapped
            }
            val hasTranslation = song.lyrics?.any { !it.translation.isNullOrBlank() } == true
            provider.player.setDisplayTranslation(hasTranslation)
            provider.player.setDisplayRoma(false)
            provider.player.setSong(song)
        }

        @Synchronized
        private fun requestLocalLyrics(current: SaltPlayerTrackMetadata) {
            val requestKey = current.localLyricsRequestKey
            if (requestKey == null) {
                localLyricsGeneration.incrementAndGet()
                pendingLocalLyricsTask?.cancel(true)
                pendingLocalLyricsTask = null
                return
            }
            val preferOnlineSources = host.getBooleanPreference(
                KEY_ONLINE_TRANSLATION_APP_SALT,
                default = false,
            ) && host.getBooleanPreference(
                KEY_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
                default = false,
            )
            if (preferOnlineSources) {
                // “优先使用在线源”：不再读取歌曲文件内置歌词与同目录歌词文件，
                // 交由主模块在线匹配；清掉可能残留的本地歌词文档并发布占位。
                localLyricsGeneration.incrementAndGet()
                pendingLocalLyricsTask?.cancel(true)
                pendingLocalLyricsTask = null
                if (document != null) {
                    document = null
                    publishCurrent()
                }
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "椒盐音乐优先使用在线源，跳过本地文件歌词读取")
                }
                return
            }
            if (requestKey == lastLocalLyricsRequestKey) return
            lastLocalLyricsRequestKey = requestKey
            val generation = localLyricsGeneration.incrementAndGet()
            pendingLocalLyricsTask?.cancel(true)
            pendingLocalLyricsTask = localLyricsExecutor.submit {
                val result = runCatching { localLyricsResolver.load(current) }
                    .onFailure { error ->
                        if (BuildConfig.DEBUG) Log.w(TAG, "椒盐音乐本地文件歌词读取失败", error)
                    }
                    .getOrNull()
                mainHandler.post {
                    synchronized(this) {
                        if (localLyricsGeneration.get() != generation) return@post
                        if (metadata.localLyricsRequestKey != requestKey) return@post
                        document = result?.document?.takeIf { it.lines.isNotEmpty() }
                    }
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            TAG,
                            "椒盐音乐本地文件歌词结果: " +
                                "source=${result?.source}, uri=${result?.mediaUri}, " +
                                "lines=${result?.document?.lines?.size ?: 0}",
                        )
                    }
                    publishCurrent(lookupFinished = true)
                }
            }
        }

        private fun requestNextTrackCapture() {
            if (nextTrackResolver == null) return
            mainHandler.removeCallbacks(requestedNextTrackCapture)
            mainHandler.post(requestedNextTrackCapture)
        }

        private fun captureNextTrack() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(requestedNextTrackCapture)
                return
            }
            val resolver = nextTrackResolver ?: return
            val current = currentMetadata()
            runCatching { resolver.resolveWithValidation(current) }
                .onSuccess { result ->
                    if (result.queueDecoded) {
                        consecutiveQueueDecodeFailures = 0
                        reportNextTrackValidation(valid = true, detail = result.detail)
                    } else if (current.identity != null) {
                        consecutiveQueueDecodeFailures += 1
                        if (consecutiveQueueDecodeFailures >= QUEUE_DECODE_FAILURE_THRESHOLD) {
                            reportNextTrackValidation(valid = false, detail = result.detail)
                            stopNextTrackCapture()
                        }
                    } else {
                        consecutiveQueueDecodeFailures = 0
                    }
                    publishNextTrack(current, result.nextTrack)
                }
                .onFailure { error ->
                    reportNextTrackValidation(
                        valid = false,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                    )
                    stopNextTrackCapture()
                    if (BuildConfig.DEBUG) Log.w(TAG, "椒盐音乐下一首采集失败", error)
                }
        }

        private fun stopNextTrackCapture() {
            nextTrackResolver = null
            mainHandler.removeCallbacks(periodicNextTrackCapture)
            mainHandler.removeCallbacks(requestedNextTrackCapture)
        }

        private fun reportNextTrackValidation(valid: Boolean, detail: String) {
            if (valid && !BuildConfig.DEBUG) return
            nextTrackValidationCacheKey?.let { cacheKey ->
                host.reportDexMethodValidation(cacheKey, valid, detail)
            }
        }

        private fun publishNextTrack(
            current: SaltPlayerTrackMetadata,
            next: SaltPlayerTrackSnapshot?,
        ) {
            val frame = when {
                current.identity == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                next == null || next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = current.id.orEmpty(),
                        currentTitle = current.title.orEmpty(),
                        currentArtist = current.artist.orEmpty(),
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.id.orEmpty(),
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
            if (provider.player.sendText(frame)) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "椒盐下一首控制帧已发送: current=${current.id}, next=${next?.id}",
                    )
                }
            }
        }
    }

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L
    private const val QUEUE_DECODE_FAILURE_THRESHOLD = 3
}
