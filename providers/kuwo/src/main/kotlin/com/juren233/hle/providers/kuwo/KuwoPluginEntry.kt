/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified for the HLE Provider Pack runtime.
 */

package com.juren233.hle.providers.kuwo

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object KuwoPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Kuwo"
    /** 与核心仓库 LyricMetadataKeys.LYRICS_UNAVAILABLE 取值一致。 */
    private const val LYRICS_UNAVAILABLE_METADATA_KEY = "hleLyricsUnavailable"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.kuwo"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: KuwoRuntime? = null

    override fun install(host: OfficialProviderHost) {
        if (OfficialCoreHostGuard.isForeignCoreHost(host)) return
        require(KuwoHostPlan.supports(host.packageName)) {
            "Unexpected target package: ${host.packageName}"
        }
        host.hookApplication { application ->
            val features = KuwoHostPlan.resolve(host.packageName, Application.getProcessName())
            if (features.isEmpty()) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            var lyricRuntime: KuwoRuntime? = null
            if (KuwoFeature.LYRICS in features) {
                lyricRuntime = KuwoRuntime(application, host.packageName, host, features)
                    .also { it.start() }
            }
            if (KuwoFeature.BUFFERING_STATE in features && lyricRuntime != null) {
                BodianBufferRuntime(application, host, lyricRuntime).start()
            }
            if (KuwoFeature.NEXT_TRACK in features && host.packageName == KuwoHostPlan.BODIAN_PACKAGE) {
                BodianNextTrackRuntime(application, host.packageName, host).start()
            }
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                OfficialCoreHostGuard.onPlaybackStateChanged(state)
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderPlaybackStateCallback
                runtime?.onPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderMetadataCallback
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "酷我音乐 Provider Hook 已安装: package=${host.packageName}")
    }

    private class KuwoRuntime(
        private val application: Application,
        private val playerPackage: String,
        private val host: OfficialProviderHost,
        private val features: Set<KuwoFeature>,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-Kuwo-Lyrics").apply { isDaemon = true }
        }
        private val cacheDir = File(application.filesDir, "hle-provider/kuwo")
        private val mainHandler = Handler(Looper.getMainLooper())

        private val requestGuard = KuwoRequestGuard()
        private val firstNextTrackHit = AtomicBoolean(false)
        private val nextTrackSetupStarted = AtomicBoolean(false)
        private val bufferCoordinator = BodianBufferStateCoordinator()

        private var lastSong: Song? = null
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L
        private var nextTrackResolver: KuwoNextTrackResolver? = null
        private var latestPlaybackState: PlaybackState? = null

        @Volatile
        private var nextTrackValidationKeys: List<String> = emptyList()

        @Volatile
        private var currentTrack: KuwoTrackMetadata? = null

        private val carLyricsPolicy = CarLyricsMetadataPolicy()

        private val requestedNextTrackCapture = Runnable(::captureNextTrack)
        private val periodicNextTrackCapture = object : Runnable {
            override fun run() {
                captureNextTrack()
                if (nextTrackResolver != null) {
                    mainHandler.postDelayed(this, NEXT_TRACK_POLL_INTERVAL_MS)
                }
            }
        }
        private val nextTrackValidation = KuwoNextTrackValidationTracker()

        @Volatile
        var provider: LyriconProvider? = null
            private set

        fun start() {
            cacheDir.mkdirs()
            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = playerPackage,
            ).also {
                it.player.setDisplayTranslation(true)
                it.player.setDisplayRoma(false)
                it.register()
            }
            runtime = this
            Log.i(TAG, "酷我音乐 Lyricon Provider 已注册: process=${Application.getProcessName()}")
        }

        @Synchronized
        fun onPlaybackState(state: PlaybackState?) {
            latestPlaybackState = state
            when (val decision = bufferCoordinator.onPlaybackState(state?.toSnapshot())) {
                BodianPlaybackDecision.Forward -> provider?.player?.setPlaybackState(state)
                BodianPlaybackDecision.Ignore -> Unit
                is BodianPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "media_session_while_buffering",
                )
            }
            if (state?.state == PlaybackState.STATE_PLAYING) {
                ensureNextTrackCaptureStarted()
            }
        }

        @Synchronized
        fun onBufferStarted() {
            when (val decision = bufferCoordinator.onBufferStarted(SystemClock.elapsedRealtime())) {
                is BodianPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "buffer_started",
                )
                BodianPlaybackDecision.Forward,
                BodianPlaybackDecision.Ignore,
                -> Unit
            }
        }

        @Synchronized
        fun onBufferEnded() {
            when (val decision = bufferCoordinator.onBufferEnded(SystemClock.elapsedRealtime())) {
                is BodianPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "buffer_ended",
                )
                BodianPlaybackDecision.Forward,
                BodianPlaybackDecision.Ignore,
                -> Unit
            }
        }

        private fun PlaybackState.toSnapshot() = BodianPlaybackSnapshot(
            state = state,
            position = position,
            updatedAtMs = lastPositionUpdateTime,
            speed = playbackSpeed,
        )

        private fun publishSyntheticPlaybackState(
            snapshot: BodianPlaybackSnapshot,
            reason: String,
        ) {
            val builder = latestPlaybackState?.let(PlaybackState::Builder) ?: PlaybackState.Builder()
            val state = builder.setState(
                snapshot.state,
                snapshot.position,
                snapshot.speed,
                snapshot.updatedAtMs,
            ).build()
            val result = provider?.player?.setPlaybackState(state)
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "波点缓冲状态已发布: reason=$reason state=${snapshot.state}, " +
                        "position=${snapshot.position}, updatedAt=${snapshot.updatedAtMs}, result=$result",
                )
            }
        }

        fun onMetadata(value: MediaMetadata?) {
            if (value == null) {
                currentTrack = null
                requestGuard.clear()
                requestNextTrackCapture()
                return
            }
            val mediaId = value.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            val normalized = carLyricsPolicy.normalize(
                id = mediaId.orEmpty(),
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
            )
            val track = KuwoTrackMetadata(
                mediaId = mediaId,
                title = normalized.title,
                artist = normalized.artist,
                album = value.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            if (track.mediaId.isNullOrBlank() && track.title.isNullOrBlank()) return
            currentTrack = track
            ensureNextTrackCaptureStarted()
            requestNextTrackCapture()

            val directRid = KuwoTrackIdResolver.directRid(track.mediaId)
            val requestKey = directRid?.let { "rid:$it" } ?: track.stableSearchKey()
            if (!requestGuard.select(requestKey)) return
            publish(placeholder(track, directRid, requestKey))

            executor.execute {
                loadTrack(requestKey, track, directRid)
            }
        }

        private fun ensureNextTrackCaptureStarted() {
            // 下一首的 playcontrol hook 只在酷我本体存在；波点由 :service 的
            // BodianNextTrackRuntime 负责，主进程不得发起 DexKit 查询。
            if (KuwoFeature.NEXT_TRACK !in features) return
            if (playerPackage != KuwoHostPlan.KUWO_PACKAGE) return
            if (nextTrackSetupStarted.compareAndSet(false, true)) {
                mainHandler.post(::startNextTrackCapture)
            }
        }

        private fun loadTrack(
            requestKey: String,
            track: KuwoTrackMetadata,
            directRid: Long?,
        ) {
            val rid = directRid ?: runCatching { KuwoApiClient.search(track) }
                .onFailure { error -> Log.w(TAG, "酷我歌曲 rid 搜索失败: title=${track.title}", error) }
                .getOrNull()
            if (rid == null) {
                Log.w(TAG, "酷我歌曲 rid 无法解析: title=${track.title}, artist=${track.artist}")
                publishUnavailable(requestKey, track, directRid)
                return
            }
            if (!isCurrent(requestKey)) return

            var cachedHasLyrics = false
            loadCached(rid)?.let { cached ->
                if (isCurrent(requestKey)) {
                    val cachedSong = toSong(track, rid, cached)
                    cachedHasLyrics = !cachedSong.lyrics.isNullOrEmpty()
                    publish(cachedSong)
                }
            }

            runCatching { KuwoApiClient.fetchLyrics(rid) }
                .onSuccess { payload ->
                    writeCache(rid, payload)
                    if (!isCurrent(requestKey)) return@onSuccess
                    val song = toSong(track, rid, payload)
                    publish(song)
                    // 接口已答复但解析不出歌词：本 Provider 没有重试，结果即最终。
                    if (song.lyrics.isNullOrEmpty()) publishUnavailable(requestKey, track, directRid)
                    if (BuildConfig.DEBUG) {
                        val lines = song.lyrics.orEmpty()
                        val wordCount = lines.sumOf { it.words.orEmpty().size }
                        Log.i(
                            TAG,
                            "酷我歌词已发布: rid=$rid, source=${payload.source}, " +
                                "lines=${lines.size}, words=$wordCount",
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "酷我歌词下载失败: rid=$rid", error)
                    // 已用缓存显示出歌词时不能判为缺失。
                    if (!cachedHasLyrics) publishUnavailable(requestKey, track, directRid)
                }
        }

        private fun startNextTrackCapture() {
            require(Looper.myLooper() == Looper.getMainLooper())
            val queries = KuwoNextTrackResolver.queries(application)
            nextTrackValidationKeys = queries.map { it.cacheKey }
            host.resolveDexMethods(
                application = application,
                queries = queries,
                callback = OfficialProviderDexMethodsCallback { targets ->
                    mainHandler.post { finishNextTrackSetup(targets) }
                },
            )
        }

        private fun finishNextTrackSetup(targets: List<OfficialProviderMethodTarget>) {
            mainHandler.removeCallbacks(periodicNextTrackCapture)
            nextTrackResolver = null
            nextTrackValidation.reset()
            val resolver = runCatching {
                KuwoNextTrackResolver.create(application, targets)
            }.onFailure { error ->
                Log.w(TAG, "酷我下一首解析器校验失败", error)
                reportNextTrackValidation(
                    valid = false,
                    detail = "resolver_validation:${error::class.java.simpleName}: ${error.message}",
                )
            }.getOrNull() ?: return
            nextTrackResolver = resolver
            mainHandler.post(periodicNextTrackCapture)
            Log.i(TAG, "酷我下一首 Hook 已安装: process=${Application.getProcessName()}")
        }

        private fun requestNextTrackCapture() {
            if (nextTrackResolver == null) return
            mainHandler.removeCallbacks(requestedNextTrackCapture)
            mainHandler.post(requestedNextTrackCapture)
        }

        private fun captureNextTrack() {
            require(Looper.myLooper() == Looper.getMainLooper())
            val metadata = currentTrack
            val resolver = nextTrackResolver
            if (metadata == null || resolver == null) {
                publishNextTrack(metadata, null)
                return
            }
            runCatching { resolver.resolve(metadata) }
                .onSuccess { rawSnapshot ->
                    val snapshot = KuwoNextTrackBinding.align(metadata, rawSnapshot)
                    when {
                        rawSnapshot == null || snapshot != null -> {
                            nextTrackValidation.record(alignmentFailed = false)
                            reportNextTrackValidation(
                                valid = true,
                                detail = "next=${snapshot?.next?.id ?: "none"}",
                            )
                        }
                        nextTrackValidation.record(alignmentFailed = true) -> {
                            reportNextTrackValidation(
                                valid = false,
                                detail = "queue_current_mismatch:${rawSnapshot.current.id}",
                            )
                            deactivateNextTrackResolver()
                        }
                    }
                    if (
                        BuildConfig.DEBUG &&
                        snapshot != null &&
                        firstNextTrackHit.compareAndSet(false, true)
                    ) {
                        Log.i(
                            TAG,
                            "酷我下一首 Hook 首次命中: current=${snapshot.current.id}, " +
                                "next=${snapshot.next?.id}",
                        )
                    }
                    publishNextTrack(metadata, snapshot)
                }
                .onFailure { error ->
                    reportNextTrackValidation(
                        valid = false,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                    )
                    deactivateNextTrackResolver()
                    if (BuildConfig.DEBUG) Log.w(TAG, "酷我下一首采集失败", error)
                }
        }

        private fun reportNextTrackValidation(valid: Boolean, detail: String) {
            if (valid && !BuildConfig.DEBUG) return
            nextTrackValidationKeys.forEach { key ->
                host.reportDexMethodValidation(key, valid, detail)
            }
        }

        private fun deactivateNextTrackResolver() {
            nextTrackResolver = null
            mainHandler.removeCallbacks(periodicNextTrackCapture)
            mainHandler.removeCallbacks(requestedNextTrackCapture)
        }

        private fun publishNextTrack(
            metadata: KuwoTrackMetadata?,
            snapshot: KuwoQueueSnapshot?,
        ) {
            val current = snapshot?.current
            val currentId = current?.id?.takeIf(String::isNotBlank)
                ?: KuwoTrackIdResolver.directRid(metadata?.mediaId)?.toString()
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
            if (provider?.player?.sendText(frame) == true) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "酷我下一首控制帧已发送: current=$currentId, next=${next?.id}",
                    )
                }
            }
        }

        private fun isCurrent(requestKey: String): Boolean = requestGuard.isCurrent(requestKey)

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider?.player?.setSong(song)
        }

        private fun placeholder(
            track: KuwoTrackMetadata,
            directRid: Long?,
            requestKey: String,
        ): Song = Song().apply {
            id = directRid?.toString() ?: track.mediaId?.takeIf(String::isNotBlank) ?: requestKey
            name = track.title
            artist = track.artist
            duration = track.durationMs.coerceAtLeast(0L)
        }

        /** 在占位歌曲上打「歌词确认缺失」标记；请求已过期时不发。 */
        private fun publishUnavailable(requestKey: String, track: KuwoTrackMetadata, directRid: Long?) {
            if (!isCurrent(requestKey)) return
            publish(placeholder(track, directRid, requestKey).apply {
                metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                    mapOf(LYRICS_UNAVAILABLE_METADATA_KEY to "true"),
                )
            })
        }

        private fun loadCached(rid: Long): KuwoLyricsPayload? {
            val file = File(cacheDir, "$rid.json")
            if (!file.isFile) return null
            return runCatching {
                KuwoLyricsPayload.fromJson(JSONObject(file.readText()))
            }.onFailure { error ->
                if (BuildConfig.DEBUG) Log.w(TAG, "酷我歌词缓存读取失败: rid=$rid", error)
            }.getOrNull()
        }

        private fun writeCache(rid: Long, payload: KuwoLyricsPayload) {
            runCatching {
                File(cacheDir, "$rid.json").writeText(payload.toJson().toString())
            }.onFailure { error ->
                if (BuildConfig.DEBUG) Log.w(TAG, "酷我歌词缓存写入失败: rid=$rid", error)
            }
        }
    }

    /**
     * 波点主进程的缓冲边界 Hook：AIDL 收口 AIDLPlayDelegateImpl 的
     * PlayDelegate_WaitForBuffering/Finish 驱动 KuwoRuntime 里的
     * BodianBufferStateCoordinator，卡顿期间冻结岛内进度，防止歌词漂移。
     * 未验证版本解析不到精确目标时整体跳过，只损失缓冲合成，不影响歌词。
     */
    private class BodianBufferRuntime(
        private val application: Application,
        private val host: OfficialProviderHost,
        private val lyricRuntime: KuwoRuntime,
    ) {
        private val firstStartCallback = AtomicBoolean(false)
        private val firstEndCallback = AtomicBoolean(false)

        fun start() {
            val queries = BodianBufferHookResolver.queries(application) ?: run {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "波点缓冲 Hook 跳过: 当前版本无已验证的精确目标")
                }
                return
            }
            queries.forEach { query ->
                host.hookAfterDexMethod(
                    application = application,
                    query = query,
                    callback = OfficialProviderMethodCallback { _, _ ->
                        when (query.cacheKey) {
                            BodianBufferHookResolver.BUFFER_START_CACHE_KEY -> {
                                if (BuildConfig.DEBUG && firstStartCallback.compareAndSet(false, true)) {
                                    Log.i(TAG, "波点缓冲开始 Hook 首次命中")
                                }
                                lyricRuntime.onBufferStarted()
                            }
                            BodianBufferHookResolver.BUFFER_END_CACHE_KEY -> {
                                if (BuildConfig.DEBUG && firstEndCallback.compareAndSet(false, true)) {
                                    Log.i(TAG, "波点缓冲结束 Hook 首次命中")
                                }
                                lyricRuntime.onBufferEnded()
                            }
                        }
                    },
                )
            }
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "波点缓冲边界 Hook 已请求安装: queries=${queries.size}")
            }
        }
    }

    /**
     * 波点 :service 进程的下一首采集：解码播放与预取都在该进程（真机音频焦点
     * 日志证实），而主进程的播放队列在 Flutter/Dart 侧不可见。经 PlayManager 的
     * play/prefetch 稳定精确目标拿到当前曲与预取曲 bean，独立注册 Provider 实例
     * 发送 NEXT_TRACK 控制帧（QQ 小米音乐 :remote 双注册同款先例）。
     */
    private class BodianNextTrackRuntime(
        private val application: Application,
        private val playerPackage: String,
        private val host: OfficialProviderHost,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val resolver = BodianNextTrackResolver(SystemClock::elapsedRealtime)
        private val firstPlayCallback = AtomicBoolean(false)
        private val firstPrefetchCallback = AtomicBoolean(false)

        @Volatile
        private var provider: LyriconProvider? = null

        private val heartbeat = object : Runnable {
            override fun run() {
                resolver.heartbeat()?.let(::sendFrame)
                mainHandler.postDelayed(this, BodianNextTrackResolver.HEARTBEAT_MS)
            }
        }

        fun start() {
            val queries = BodianNextTrackHookResolver.queries(playerPackage) ?: return
            runCatching {
                host.resolveDexMethods(
                    application = application,
                    queries = queries,
                    callback = OfficialProviderDexMethodsCallback { targets ->
                        mainHandler.post { startResolved(queries, targets) }
                    },
                )
            }.onFailure { error ->
                Log.e(TAG, "波点下一首目标解析注册失败", error)
            }
        }

        @Synchronized
        private fun startResolved(
            queries: List<OfficialProviderDexMethodQuery>,
            targets: List<OfficialProviderMethodTarget>,
        ) {
            if (targets.size != queries.size) {
                Log.w(TAG, "波点下一首解析不完整: expected=${queries.size}, actual=${targets.size}")
                return
            }
            runCatching {
                // resolveDexMethods already owns both cache keys. Install its resolved targets
                // directly; hookAfterDexMethod would register the same keys a second time.
                queries.zip(targets).forEach { (query, target) ->
                    host.hookAfterMethod(
                        target = target,
                        callback = OfficialProviderMethodCallback { _, arguments ->
                            if (BuildConfig.DEBUG) {
                                val firstHit = when (query.cacheKey) {
                                    BodianNextTrackHookResolver.CURRENT_CACHE_KEY -> firstPlayCallback
                                    else -> firstPrefetchCallback
                                }
                                if (firstHit.compareAndSet(false, true)) {
                                    Log.i(TAG, "波点下一首 Hook 首次命中: key=${query.cacheKey}")
                                }
                            }
                            val bean = BodianMusicBeanReader.read(arguments.firstOrNull())
                                ?: return@OfficialProviderMethodCallback
                            mainHandler.post {
                                val frame = when (query.cacheKey) {
                                    BodianNextTrackHookResolver.CURRENT_CACHE_KEY ->
                                        resolver.onCurrentMusic(bean)
                                    else -> resolver.onPrefetchMusic(bean)
                                }
                                if (frame != null) sendFrame(frame)
                            }
                        },
                    )
                }
                provider = LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = PROVIDER_PACKAGE,
                    playerPackageName = playerPackage,
                ).also { it.register() }
                mainHandler.post(heartbeat)
                Log.i(TAG, "波点下一首 Hook 已安装: process=${Application.getProcessName()}")
            }.onFailure { error ->
                Log.e(TAG, "波点下一首 Hook 安装失败", error)
            }
        }

        private fun sendFrame(frame: String) {
            provider?.player?.sendText(frame)
        }
    }

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L

    private fun toSong(
        track: KuwoTrackMetadata,
        rid: Long,
        payload: KuwoLyricsPayload,
    ): Song {
        val timeline = KuwoLyricsParser.parse(payload.raw)
        val lyrics = timeline.map { line ->
            RichLyricLine().apply {
                begin = line.begin
                end = line.end
                duration = (line.end - line.begin).coerceAtLeast(0L)
                text = line.text
                translation = line.translation
                roma = line.roma
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
        return Song().apply {
            id = rid.toString()
            name = track.title
            artist = track.artist
            duration = track.durationMs.takeIf { it > 0L } ?: lyrics.lastOrNull()?.end ?: 0L
            this.lyrics = lyrics.takeIf(List<RichLyricLine>::isNotEmpty)
        }
    }
}

internal class KuwoNextTrackValidationTracker(
    private val invalidThreshold: Int = 3,
) {
    private var consecutiveAlignmentFailures = 0

    init {
        require(invalidThreshold > 0)
    }

    @Synchronized
    fun record(alignmentFailed: Boolean): Boolean {
        if (!alignmentFailed) {
            consecutiveAlignmentFailures = 0
            return false
        }
        consecutiveAlignmentFailures += 1
        if (consecutiveAlignmentFailures < invalidThreshold) return false
        consecutiveAlignmentFailures = 0
        return true
    }

    @Synchronized
    fun reset() {
        consecutiveAlignmentFailures = 0
    }
}
