/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import android.app.Application
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object KuGouPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/KuGou"
    /** 与核心仓库 LyricMetadataKeys.LYRICS_UNAVAILABLE 取值一致。 */
    private const val LYRICS_UNAVAILABLE_METADATA_KEY = "hleLyricsUnavailable"
    private const val FULL_PACKAGE = "com.kugou.android"
    private const val LITE_PACKAGE = "com.kugou.android.lite"
    private const val FULL_SUPPORT_PROCESS = "$FULL_PACKAGE.support"
    private const val LITE_SUPPORT_PROCESS = "$LITE_PACKAGE.support"
    private const val PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider.kugou"
    private const val NEXT_TRACK_CAPTURE_INTERVAL_MS = 1_000L
    private const val NEXT_TRACK_HEARTBEAT_MS = 10_000L

    // 酷狗 LyricManager 加载本地歌词文件的方法没有稳定混淆名，用方法内部
    // 的错误文案作 DexKit 锚点（与上游 Lyricon Provider 的酷狗实现同锚点）。
    private const val LYRIC_MANAGER_CLASS = "com.kugou.framework.lyric.LyricManager"
    private const val LYRIC_MANAGER_FILE_ANCHOR = "file is not krc or lyc or txt file"
    private const val LYRIC_FILE_HOOK_REGISTRATION_TIMEOUT_MS = 30_000L
    private const val MAX_LYRIC_FILE_BYTES = 4L * 1024L * 1024L

    // 单曲最多尝试的搜索候选数：主候选 + 顺位兜底上限，请求封顶。
    private const val MAX_SEARCH_CANDIDATES = 3

    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: KuGouRuntime? = null

    override fun install(host: OfficialProviderHost) {
        if (OfficialCoreHostGuard.isForeignCoreHost(host)) return
        require(host.packageName == FULL_PACKAGE || host.packageName == LITE_PACKAGE) {
            "Unsupported KuGou package: ${host.packageName}"
        }
        val supportProcess = when (host.packageName) {
            FULL_PACKAGE -> FULL_SUPPORT_PROCESS
            LITE_PACKAGE -> LITE_SUPPORT_PROCESS
            else -> error("unreachable")
        }
        if (host.processName != supportProcess) return

        host.hookApplication { application ->
            if (Application.getProcessName() != supportProcess) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication

            val provider = runCatching {
                LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = PROVIDER_PACKAGE,
                    playerPackageName = host.packageName,
                ).also {
                    it.player.setDisplayTranslation(true)
                    it.register()
                }
            }.onFailure { error ->
                installed.set(false)
                Log.e(TAG, "酷狗音乐 Provider 注册失败", error)
            }.getOrNull() ?: return@hookApplication

            val currentRuntime = KuGouRuntime(application, provider, host).also { runtime = it }
            currentRuntime.start()
            currentRuntime.scheduleLyricFileHookRegistration()
            runCatching {
                val versionCode = installedVersionCode(application, host.packageName)
                if (host.packageName == FULL_PACKAGE) {
                    Log.i(
                        TAG,
                        "酷狗下一首档案: versionCode=$versionCode " +
                            "verifiedAgainst=${KuGouNextTrackProfiles.fullProfileFor(versionCode).verifiedAgainst}",
                    )
                }
                host.resolveDexMethods(
                    application = application,
                    queries = nextTrackQueriesFor(host.packageName, versionCode),
                    callback = OfficialProviderDexMethodsCallback { targets ->
                        currentRuntime.installNextTrackResolver(targets)
                    },
                )
            }.onFailure { error ->
                Log.w(TAG, "酷狗下一首 DexKit 注册失败，本地歌词 Hook 仍按超时兜底注册", error)
            }
            Log.i(
                TAG,
                "酷狗音乐 Provider 已注册: package=${host.packageName} " +
                    "process=${host.processName} lyricSource=v2-api+local-file",
            )
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                OfficialCoreHostGuard.onPlaybackStateChanged(state)
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderPlaybackStateCallback
                runtime?.provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderMetadataCallback
                runtime?.onMetadata(metadata)
            },
        )
    }

    internal fun nextTrackQueriesFor(
        packageName: String,
        versionCode: Long?,
    ): List<OfficialProviderDexMethodQuery> {
        val full = packageName == FULL_PACKAGE
        require(full || packageName == LITE_PACKAGE) { "Unsupported KuGou package: $packageName" }
        return KuGouNextTrackProfiles.queriesFor(full = full, versionCode = versionCode)
    }

    private fun installedVersionCode(application: Application, packageName: String): Long? =
        runCatching {
            application.packageManager.getPackageInfo(packageName, 0).longVersionCode
        }.onFailure { error ->
            Log.w(TAG, "读取酷狗版本号失败，下一首查询按最新档案: package=$packageName", error)
        }.getOrNull()

    internal fun lyricFileQuery(): OfficialProviderDexMethodQuery = OfficialProviderDexMethodQuery(
        cacheKey = "kugou-lyric-file-load-v1",
        declaringClassName = LYRIC_MANAGER_CLASS,
        requiredStrings = listOf(LYRIC_MANAGER_FILE_ANCHOR),
        parameterTypeNames = listOf("java.lang.String", "boolean"),
        isStatic = false,
    )

    private class KuGouRuntime(
        private val application: Application,
        val provider: LyriconProvider,
        private val host: OfficialProviderHost,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-KuGou-Lyrics").apply { isDaemon = true }
        }
        private val generation = AtomicLong(0L)
        private val cacheDir = File(application.filesDir, "hle-provider/kugou")
        private val clientMid = KuGouApiProtocol.clientMid(
            buildString {
                append(application.packageName)
                append('\u0000')
                append(
                    Settings.Secure.getString(
                        application.contentResolver,
                        Settings.Secure.ANDROID_ID,
                    ).orEmpty(),
                )
            },
        )
        private val mainHandler = Handler(Looper.getMainLooper())
        private val periodicNextTrackCapture = object : Runnable {
            override fun run() {
                captureNextTrack()
                if (nextTrackResolver != null) {
                    mainHandler.postDelayed(this, NEXT_TRACK_CAPTURE_INTERVAL_MS)
                }
            }
        }
        private val nextTrackValidation = KuGouNextTrackValidationTracker()

        @Volatile
        private var track = KuGouTrackMetadata(null, null, null, null, 0L)

        private val carLyricsPolicy = CarLyricsMetadataPolicy()

        @Volatile
        private var lastSong: Song? = null

        @Volatile
        private var nextTrackResolver: KuGouNextTrackResolver? = null

        @Volatile
        private var nextTrackValidationKeys: List<String> = emptyList()

        private var pendingLyricsTask: Future<*>? = null

        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L

        private val lyricHookRegistered = AtomicBoolean(false)
        private val firstLyricFileHit = AtomicBoolean(false)
        private val lyricHookTimeoutRegistration = Runnable { registerLyricFileHook() }

        // 本地歌词文件源（酷狗自读的精确匹配）最近一次发布结果；本地文件源
        // 优先于 v2 搜索源，命中后同曲不再发起搜索、也不再被搜索结果覆盖。
        @Volatile
        private var fileSourceIdentity: String? = null

        @Volatile
        private var fileSourceSong: Song? = null

        fun start() {
            cacheDir.mkdirs()
            publish(placeholder(track))
        }

        fun scheduleLyricFileHookRegistration() {
            mainHandler.postDelayed(
                lyricHookTimeoutRegistration,
                LYRIC_FILE_HOOK_REGISTRATION_TIMEOUT_MS,
            )
        }

        fun onMetadata(value: MediaMetadata?) {
            val mediaId = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            val normalized = carLyricsPolicy.normalize(
                id = mediaId.orEmpty(),
                title = value?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            )
            val next = KuGouTrackMetadata(
                mediaId = mediaId,
                title = normalized.title,
                artist = normalized.artist,
                album = value?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
            val previous = track
            if (next == previous) return
            track = next
            if (!KuGouTrackUpdatePolicy.shouldReloadLyrics(previous, next)) {
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "酷狗同曲元数据已更新，保留现有歌词: id=${next.identity}, " +
                            "album=${next.album}",
                    )
                }
                mainHandler.post(::captureNextTrack)
                return
            }
            val requestGeneration = generation.incrementAndGet()
            pendingLyricsTask?.cancel(true)
            pendingLyricsTask = null
            val localFileSong = fileSourceSong
                ?.takeIf { KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity, next.identity) }
            publish(localFileSong ?: placeholder(next))
            if (localFileSong == null && next.isSearchable) {
                pendingLyricsTask = executor.submit {
                    loadLyrics(requestGeneration, next)
                }
            }
            mainHandler.post(::captureNextTrack)
        }

        private fun loadLyrics(
            requestGeneration: Long,
            requestTrack: KuGouTrackMetadata,
        ) {
            val candidates = runCatching { KuGouApiClient.searchCandidates(requestTrack, clientMid) }
                .onFailure { error ->
                    if (!Thread.currentThread().isInterrupted) {
                        Log.w(
                            TAG,
                            "酷狗歌词搜索失败: title=${requestTrack.title}",
                            error,
                        )
                    }
                }
                .getOrNull()
            if (candidates.isNullOrEmpty()) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.w(
                        TAG,
                        "酷狗歌词未匹配: title=${requestTrack.title}, " +
                            "artist=${requestTrack.artist}, " +
                            "mediaId=${requestTrack.mediaId != null}",
                    )
                }
                publishUnavailable(requestGeneration, requestTrack)
                return
            }
            if (!isCurrent(requestGeneration, requestTrack)) return
            if (KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity, requestTrack.identity)) return

            // 主候选优先发布；只有主候选内容不带翻译段时，才在同样通过严格
            // 评分门槛的顺位候选里找带翻译的版本，打分与匹配标准保持不变。
            var primaryCandidate: KuGouSearchCandidate? = null
            var primaryParsed: List<ParsedLine>? = null
            for (candidate in candidates.take(MAX_SEARCH_CANDIDATES)) {
                if (!isCurrent(requestGeneration, requestTrack)) return
                val parsed = resolveLyrics(requestGeneration, candidate, requestTrack) ?: run {
                    if (primaryParsed == null) publishUnavailable(requestGeneration, requestTrack)
                    return
                }
                if (parsed.isEmpty()) continue
                if (hasTranslation(parsed)) {
                    publishSearchLyrics(candidate, parsed, requestTrack, requestGeneration)
                    return
                }
                if (primaryParsed == null) {
                    primaryCandidate = candidate
                    primaryParsed = parsed
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            TAG,
                            "酷狗主候选无翻译段，尝试顺位候选: id=${candidate.downloadId}, " +
                                "lines=${parsed.size}",
                        )
                    }
                }
            }
            val fallbackCandidate = primaryCandidate
            val fallbackParsed = primaryParsed
            if (fallbackCandidate == null || fallbackParsed == null) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.w(
                        TAG,
                        "酷狗歌词无可解析时间轴: ids=" +
                            candidates.take(MAX_SEARCH_CANDIDATES)
                                .joinToString { it.downloadId },
                    )
                }
                publishUnavailable(requestGeneration, requestTrack)
                return
            }
            publishSearchLyrics(fallbackCandidate, fallbackParsed, requestTrack, requestGeneration)
        }

        private fun hasTranslation(parsed: List<ParsedLine>): Boolean =
            // 个别元数据行（词/曲署名）缺翻译是正常形态；全都没有才视为无翻译
            parsed.any { !it.translation.isNullOrBlank() }

        /** 返回 null 表示任务已取消或被新曲取代；空列表表示该候选不可解析。 */
        private fun resolveLyrics(
            requestGeneration: Long,
            candidate: KuGouSearchCandidate,
            requestTrack: KuGouTrackMetadata,
        ): List<ParsedLine>? {
            loadCached(candidate.downloadId)?.let { cached ->
                val parsed = decodeLyrics(cached, requestTrack.durationMs)
                if (parsed.isNotEmpty()) return parsed
            }
            val raw = runCatching { KuGouApiClient.download(candidate, clientMid) }
                .onFailure { error ->
                    if (!Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "酷狗歌词下载失败: id=${candidate.downloadId}", error)
                    }
                }
                .getOrNull() ?: return emptyList()
            if (!isCurrent(requestGeneration, requestTrack)) return null
            val parsed = decodeLyrics(raw, requestTrack.durationMs)
            if (parsed.isEmpty()) return emptyList()
            writeCache(candidate.downloadId, raw)
            return parsed
        }

        private fun publishSearchLyrics(
            candidate: KuGouSearchCandidate,
            parsed: List<ParsedLine>,
            requestTrack: KuGouTrackMetadata,
            requestGeneration: Long,
        ) {
            if (!isCurrent(requestGeneration, requestTrack)) return
            if (KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity, requestTrack.identity)) return
            publish(toSong(requestTrack, parsed))
            if (BuildConfig.DEBUG) {
                val translationCount = parsed.count { !it.translation.isNullOrBlank() }
                Log.i(
                    TAG,
                    "酷狗 v2 歌词已发布: id=${candidate.downloadId}, " +
                        "contentType=${candidate.contentType}, lines=${parsed.size}, " +
                        "words=${parsed.sumOf { it.words.size }}, translations=$translationCount",
                )
            }
        }

        fun installNextTrackResolver(targets: List<OfficialProviderMethodTarget>) {
            mainHandler.post {
                onDexBatchResolved()
                mainHandler.removeCallbacks(periodicNextTrackCapture)
                nextTrackResolver = null
                nextTrackValidation.reset()
                nextTrackValidationKeys = nextTrackQueriesFor(
                    host.packageName,
                    installedVersionCode(application, host.packageName),
                ).map { it.cacheKey }
                val resolver = runCatching {
                    KuGouNextTrackResolver.create(application, targets)
                }.onFailure { error ->
                    Log.w(TAG, "酷狗下一首解析器校验失败", error)
                    reportNextTrackValidation(
                        valid = false,
                        detail = "resolver_validation:${error::class.java.simpleName}: ${error.message}",
                    )
                }.getOrNull() ?: return@post
                nextTrackResolver = resolver
                mainHandler.post(periodicNextTrackCapture)
                Log.i(TAG, "酷狗下一首解析器已启用")
            }
        }

        /**
         * 歌词文件拦截与下一首批量解析共享本进程唯一的 DexKit 吞吐；批量完成
         * 后再注册，避免酷狗刚更新后的首次冷启动出现两份全量 DEX 扫描并发
         * （参见 KUWO-STARTUP-001）。批量始终不完成时由超时兜底注册。
         */
        private fun onDexBatchResolved() {
            registerLyricFileHook()
        }

        private fun registerLyricFileHook() {
            if (!lyricHookRegistered.compareAndSet(false, true)) return
            runCatching {
                host.hookAfterDexMethod(
                    application = application,
                    query = KuGouPluginEntry.lyricFileQuery(),
                    callback = OfficialProviderMethodCallback { _, arguments ->
                        onLyricFileLoaded(arguments)
                    },
                )
            }.onSuccess {
                mainHandler.removeCallbacks(lyricHookTimeoutRegistration)
                Log.i(TAG, "酷狗歌词文件拦截已提交解析注册: class=$LYRIC_MANAGER_CLASS")
            }.onFailure { error ->
                lyricHookRegistered.set(false)
                Log.w(TAG, "酷狗歌词文件 Hook 注册失败，后续回调仍可重试", error)
            }
        }

        private fun onLyricFileLoaded(arguments: Array<Any?>?) {
            val path = arguments?.firstOrNull() as? String
            if (path.isNullOrBlank()) return
            val boundTrack = track
            if (!KuGouLyricFilePolicy.isBindableTrack(boundTrack)) {
                // 酷狗可能在 MediaSession 元数据广播前就加载歌词文件；此时无法
                // 可靠归属，放弃文件源（网络搜索兜底）也不冒错绑风险。
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "酷狗歌词文件命中但当前轨道身份为空，放弃绑定: $path")
                }
                return
            }
            if (firstLyricFileHit.compareAndSet(false, true)) {
                Log.i(TAG, "酷狗歌词文件拦截首次命中: $path")
            } else if (BuildConfig.DEBUG) {
                Log.d(TAG, "酷狗歌词文件命中: $path")
            }
            executor.submit { loadLyricsFromFile(boundTrack, path) }
        }

        private fun loadLyricsFromFile(boundTrack: KuGouTrackMetadata, path: String) {
            val raw = try {
                val file = File(path)
                if (!file.isFile) return
                val length = file.length()
                if (length <= 0L || length > MAX_LYRIC_FILE_BYTES) return
                file.readBytes()
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "酷狗歌词文件读取失败: $path", error)
                }
                return
            }

            val parsed = decodeLyrics(raw, boundTrack.durationMs)
            if (parsed.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "酷狗歌词文件无可解析时间轴: $path")
                }
                return
            }
            val song = toSong(boundTrack, parsed)
            if (!KuGouLyricFilePolicy.isStillCurrent(boundTrack.identity, track.identity)) return
            fileSourceSong = song
            fileSourceIdentity = boundTrack.identity
            publish(song)
            if (BuildConfig.DEBUG) {
                val wordCount = parsed.sumOf { it.words.size }
                val translationCount = parsed.count { !it.translation.isNullOrBlank() }
                Log.i(
                    TAG,
                    "酷狗本地歌词文件已发布: lines=${parsed.size}, words=$wordCount, " +
                        "translations=$translationCount",
                )
            }
        }

        private fun captureNextTrack() {
            val current = track
            val resolver = nextTrackResolver ?: return
            var resolverFailed = false
            val resolvedNext = runCatching { resolver.resolve() }
                .onFailure { error ->
                    resolverFailed = true
                    Log.w(TAG, "酷狗下一首读取失败", error)
                    reportNextTrackValidation(
                        valid = false,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                    )
                    deactivateNextTrackResolver()
                }
                .getOrNull()
            val candidateMatchesCurrent = resolvedNext?.let { candidate ->
                KuGouNextTrackCandidatePolicy.isCurrent(
                    currentTitle = current.title,
                    currentArtist = current.artist,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                )
            } == true
            val next = resolvedNext?.takeUnless { candidateMatchesCurrent }
            if (!resolverFailed) {
                if (candidateMatchesCurrent) {
                    if (nextTrackValidation.record(candidateMatchesCurrent = true)) {
                        reportNextTrackValidation(
                            valid = false,
                            detail = "candidate_matches_current:${resolvedNext?.id}",
                        )
                        deactivateNextTrackResolver()
                    }
                } else {
                    nextTrackValidation.record(candidateMatchesCurrent = false)
                    reportNextTrackValidation(
                        valid = true,
                        detail = "next=${next?.id ?: "none"}",
                    )
                }
            }
            if (candidateMatchesCurrent && BuildConfig.DEBUG) {
                Log.w(
                    TAG,
                    "酷狗下一首候选与当前曲一致，已清空: " +
                        "current=${current.title}/${current.artist}, " +
                        "candidate=${resolvedNext.title}/${resolvedNext.artist}",
                )
            }
            val frame = if (next == null || next.title.isBlank()) {
                OfficialProviderControlProtocol.encodeNextTrackClear(
                    currentId = current.identity,
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                )
            } else {
                OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.identity,
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                    nextId = next.id,
                    nextTitle = next.title,
                    nextArtist = next.artist,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (frame == lastNextTrackFrame &&
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
                        "酷狗下一首控制帧已发送: " +
                            "current=${current.title}/${current.artist}, " +
                            "next=${next?.title}/${next?.artist}, id=${next?.id}",
                    )
                }
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
        }

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider.player.setSong(song)
        }

        private fun placeholder(track: KuGouTrackMetadata): Song = Song().apply {
            id = track.identity
            name = track.title
            artist = track.artist
            duration = track.durationMs
        }

        /** 搜索失败、未匹配、无可解析时间轴都是本 Provider 的最终结果：在占位歌曲上打「歌词确认缺失」标记。 */
        private fun publishUnavailable(requestGeneration: Long, requestTrack: KuGouTrackMetadata) {
            if (Thread.currentThread().isInterrupted) return
            if (!isCurrent(requestGeneration, requestTrack)) return
            // 本地歌词文件源已拥有这首歌时，它的结果优先，不能判为缺失。
            if (KuGouLyricFilePolicy.ownsTrack(fileSourceIdentity, requestTrack.identity)) return
            publish(placeholder(requestTrack).apply {
                metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                    mapOf(LYRICS_UNAVAILABLE_METADATA_KEY to "true"),
                )
            })
        }

        private fun toSong(track: KuGouTrackMetadata, lines: List<ParsedLine>): Song = Song().apply {
            id = track.identity
            name = track.title
            artist = track.artist
            duration = track.durationMs.takeIf { it > 0 } ?: lines.lastOrNull()?.end ?: 0L
            lyrics = lines.map { line ->
                RichLyricLine().apply {
                    begin = line.begin
                    end = line.end
                    duration = line.end - line.begin
                    text = line.text
                    translation = line.translation
                    words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                }
            }
        }

        private fun decodeLyrics(raw: ByteArray, durationMs: Long): List<ParsedLine> {
            val text = if (raw.hasKrcHeader()) {
                KrcDecryptor.decrypt(raw) ?: return emptyList()
            } else {
                // 酷狗本地歌词缓存存在没有 "krc1" 魔数的形态，先按 KRC 解密，
                // 失败再按纯文本处理
                KrcDecryptor.decrypt(raw)
                    ?: raw.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
            val lines = KrcLyricsParser.parse(text).takeIf(List<ParsedLine>::isNotEmpty)
                ?: LrcLyricsParser.parse(text, durationMs)
            val translations = KuGouLanguageParser.translations(text, lines.size)
            return if (translations == null) {
                lines
            } else {
                lines.mapIndexed { index, line ->
                    line.copy(translation = translations[index])
                }
            }
        }

        private fun isCurrent(
            requestGeneration: Long,
            requestTrack: KuGouTrackMetadata,
        ): Boolean = !Thread.currentThread().isInterrupted &&
            requestGeneration == generation.get() &&
            requestTrack.identity == track.identity

        private fun loadCached(downloadId: String): ByteArray? {
            val file = cacheFile(downloadId)
            if (!file.isFile) return null
            return runCatching { file.readBytes() }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "酷狗歌词缓存读取失败: id=$downloadId", error)
                    }
                }
                .getOrNull()
        }

        private fun writeCache(downloadId: String, raw: ByteArray) {
            runCatching { cacheFile(downloadId).writeBytes(raw) }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "酷狗歌词缓存写入失败: id=$downloadId", error)
                    }
                }
        }

        private fun cacheFile(downloadId: String): File =
            File(cacheDir, "${sha256Hex(downloadId)}.lyrics")

        private fun ByteArray.hasKrcHeader(): Boolean = size >= 4 &&
            this[0] == 'k'.code.toByte() &&
            this[1] == 'r'.code.toByte() &&
            this[2] == 'c'.code.toByte() &&
            this[3] == '1'.code.toByte()
    }

    private data class NextTrack(
        val id: String,
        val title: String,
        val artist: String,
    )

    private class KuGouNextTrackResolver private constructor(
        private val singletonMethod: Method,
        private val nextMediaMethod: Method,
    ) {
        @Volatile
        private var mediaAccessors: MediaAccessors? = null

        fun resolve(): NextTrack? {
            val manager = singletonMethod.invoke(null) ?: return null
            val media = nextMediaMethod.invoke(manager) ?: return null
            val accessors = mediaAccessors
                ?.takeIf { it.ownerClass.isInstance(media) }
                ?: MediaAccessors.create(media.javaClass).also { mediaAccessors = it }
            return NextTrack(
                id = accessors.hashMethod.invoke(media) as? String ?: "",
                title = accessors.titleMethod.invoke(media) as? String ?: "",
                artist = accessors.artistMethod.invoke(media) as? String ?: "",
            )
        }

        private data class MediaAccessors(
            val ownerClass: Class<*>,
            val hashMethod: Method,
            val titleMethod: Method,
            val artistMethod: Method,
        ) {
            companion object {
                fun create(ownerClass: Class<*>): MediaAccessors = MediaAccessors(
                    ownerClass = ownerClass,
                    hashMethod = ownerClass.getMethod("getHashValue").apply { isAccessible = true },
                    titleMethod = ownerClass.getMethod("getTrackName").apply { isAccessible = true },
                    artistMethod = ownerClass.getMethod("getArtistName").apply { isAccessible = true },
                )
            }
        }

        companion object {
            fun create(
                application: Application,
                targets: List<OfficialProviderMethodTarget>,
            ): KuGouNextTrackResolver {
                require(targets.size == 2) { "酷狗下一首目标数量错误" }
                val loader = application.classLoader
                val singletonMethod = targets[0].toMethod(loader)
                val nextMediaMethod = targets[1].toMethod(loader)
                require(Modifier.isStatic(singletonMethod.modifiers))
                require(!Modifier.isStatic(nextMediaMethod.modifiers))
                return KuGouNextTrackResolver(
                    singletonMethod = singletonMethod,
                    nextMediaMethod = nextMediaMethod,
                )
            }

            private fun OfficialProviderMethodTarget.toMethod(loader: ClassLoader): Method {
                val clazz = loader.loadClass(className)
                val parameters = parameterTypeNames.map { name ->
                    when (name) {
                        "boolean" -> Boolean::class.javaPrimitiveType!!
                        "int" -> Int::class.javaPrimitiveType!!
                        "long" -> Long::class.javaPrimitiveType!!
                        else -> loader.loadClass(name)
                    }
                }.toTypedArray()
                return clazz.getDeclaredMethod(methodName, *parameters).apply {
                    isAccessible = true
                }
            }
        }
    }

    private data class ParsedLine(
        val begin: Long,
        val end: Long,
        val text: String,
        val words: List<LyricWord> = emptyList(),
        val translation: String? = null,
    )

    internal object KrcDecryptor {
        private val key = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
            206.toByte(), 210.toByte(), 110, 105,
        )
        private val magic = "krc1".toByteArray()

        fun decrypt(input: ByteArray): String? = runCatching {
            // 网络下载的 KRC 带 "krc1" 魔数；本地缓存文件两种形态都存在，
            // 按魔数自动选择解密偏移
            val offset = if (input.hasKrcMagic()) magic.size else 0
            require(input.size > offset)
            val decoded = ByteArray(input.size - offset) { index ->
                (input[index + offset].toInt() xor key[index % key.size].toInt()).toByte()
            }
            java.util.zip.InflaterInputStream(decoded.inputStream()).bufferedReader().use { it.readText() }
        }.getOrNull()

        private fun ByteArray.hasKrcMagic(): Boolean = size >= magic.size &&
            magic.indices.all { this[it] == magic[it] }
    }

    private object KrcLyricsParser {
        private val linePattern = Regex("^\\[(\\d+)\\s*,\\s*(\\d+)](.*)$")
        private val wordPattern = Regex("<(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)>")

        fun parse(content: String): List<ParsedLine> = content.lineSequence().mapNotNull { raw ->
            val match = linePattern.matchEntire(raw.trim()) ?: return@mapNotNull null
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val body = match.groupValues[3]
            val tags = wordPattern.findAll(body).toList()
            if (tags.isEmpty()) {
                return@mapNotNull ParsedLine(begin, begin + duration, body.trim())
            }
            val words = buildList {
                tags.forEachIndexed { index, tag ->
                    val start = tag.range.last + 1
                    val end = tags.getOrNull(index + 1)?.range?.first ?: body.length
                    val text = body.substring(start, end)
                    val offset = tag.groupValues[1].toLongOrNull() ?: 0L
                    val wordDuration = tag.groupValues[2].toLongOrNull() ?: 0L
                    add(
                        LyricWord(
                            begin = begin + offset,
                            end = begin + offset + wordDuration,
                            duration = wordDuration,
                            text = text,
                        )
                    )
                }
            }
            ParsedLine(begin, begin + duration, words.joinToString("") { it.text.orEmpty() }, words)
        }.toList().sortedBy(ParsedLine::begin)
    }

    private object LrcLyricsParser {
        private val timestamp = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")

        fun parse(content: String, duration: Long): List<ParsedLine> {
            val rows = content.lineSequence().flatMap { raw ->
                val matches = timestamp.findAll(raw).toList()
                if (matches.isEmpty() || matches.first().range.first != 0) {
                    emptySequence()
                } else {
                    val text = raw.substring(matches.last().range.last + 1).trim()
                    matches.asSequence().map { match ->
                        val minute = match.groupValues[1].toLongOrNull() ?: 0L
                        val second = match.groupValues[2].toLongOrNull() ?: 0L
                        val fraction = match.groupValues.getOrNull(3).orEmpty()
                        val millis = when (fraction.length) {
                            1 -> fraction.toLong() * 100
                            2 -> fraction.toLong() * 10
                            3 -> fraction.toLong()
                            else -> 0L
                        }
                        minute * 60_000 + second * 1_000 + millis to text
                    }
                }
            }.toList().sortedBy { it.first }
            return rows.mapIndexed { index, (begin, text) ->
                val end = rows.getOrNull(index + 1)?.first
                    ?: duration.takeIf { it > begin } ?: begin + 5_000L
                ParsedLine(begin, end, text)
            }
        }
    }

}

/**
 * 本地歌词文件源（酷狗自读的精确匹配）与 v2 搜索源（模糊匹配）之间的
 * 优先级与归属规则。文件源命中同曲后拥有更高优先级，但不回收已发布的
 * 搜索结果以外的任何内容；归属以钩子命中时刻的轨道身份为准。
 */
internal object KuGouLyricFilePolicy {
    /** 元数据尚未到达（mediaId 与标题都为空）时文件命中无法可靠归属。 */
    fun isBindableTrack(track: KuGouTrackMetadata): Boolean =
        !track.mediaId.isNullOrBlank() || !track.title.isNullOrBlank()

    /** 归属身份与当前轨道不一致（命中后已切歌）时丢弃解析结果，防止错绑。 */
    fun isStillCurrent(boundIdentity: String, currentIdentity: String): Boolean =
        boundIdentity == currentIdentity

    /** 本地文件源已发布该轨道时，搜索不再启动、已完成的搜索结果也不覆盖。 */
    fun ownsTrack(fileSourceIdentity: String?, identity: String): Boolean =
        fileSourceIdentity == identity
}

internal object KuGouNextTrackCandidatePolicy {
    private val whitespace = Regex("\\s+")

    fun isCurrent(
        currentTitle: String?,
        currentArtist: String?,
        candidateTitle: String,
        candidateArtist: String,
    ): Boolean {
        val normalizedCurrentTitle = normalize(currentTitle)
        if (normalizedCurrentTitle.isEmpty()) return false
        return normalizedCurrentTitle == normalize(candidateTitle) &&
            normalize(currentArtist) == normalize(candidateArtist)
    }

    private fun normalize(value: String?): String = value
        .orEmpty()
        .trim()
        .replace(whitespace, " ")
        .lowercase(Locale.ROOT)
}

internal class KuGouNextTrackValidationTracker(
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
