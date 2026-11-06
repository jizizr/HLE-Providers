/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hyperlyricsenhanced.contract.AppleSourceSwitchPerformanceDiagnostics

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleLyricsBlurPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.ApplePronunciationVisibilityPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.AppleSystemFontWeightPolicy
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCall
import com.juren233.hle.providers.applemusic.xposed.util.extensions.ScreenStateMonitor
import com.juren233.hle.providers.applemusic.xposed.util.extensions.inflate
import com.juren233.hle.providers.applemusic.xposed.util.extensions.json
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleContentLocalizationHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleDebugNetworkHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleFrameworkMetadataHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.ApplePlaybackHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.FunctionalAppleMusicHookModule
import com.juren233.hle.providers.applemusic.xposed.lyrics.AppleOnlineSourceMenuHooks
import com.juren233.hle.providers.applemusic.xposed.internal.ThreadLocalReentryGuard
import com.juren233.hle.providers.applemusic.xposed.internal.ThreadLocalStack
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

import android.content.SharedPreferences

internal class AppleLyricsSupplementHooks(
    internal val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
    internal val playbackHooks: () -> ApplePlaybackHooks,
    internal val onlineSourceMenuHooks: () -> AppleOnlineSourceMenuHooks,
    internal val lyricRequester: () -> LyricRequester,
    internal val catalogResolver: () -> AppleInternalCatalogResolver?,
    internal val applyConfiguredContentUiLanguageCallback: () -> Unit,
    internal val recordLyricsRequestSource: (String, String) -> Unit,
    internal val currentPlaybackQueueMediaId: () -> String?,
    internal val registeredPlaybackItems: (String) -> List<Any>,
    internal val registeredPlaybackItemId: (Any) -> String?,
    private val epoxyDataBindingFromHolderCallback: (Any?) -> Any?,
    internal val missingLyricsSupplement: () -> AppleMissingLyricsHooks,
) {
    internal companion object {
        const val MAX_APPLE_SYSTEM_FONT_VARIATION_CACHE_ENTRIES = 64
        const val LEGACY_MODULE_PROMOTION_DELAY_MS = 1_000L
        const val SUPPLEMENT_ACTIVE_LINE_INTERVAL_MS = 300L
        const val BLANK_NATIVE_LYRICS_PAGE_RECOVERY_DELAY_MS = 1_500L
    }

    internal val application: Application
        get() = runtime.application
    internal val classLoader: ClassLoader
        get() = runtime.classLoader
    internal val hookResolver: AppleMusicHookResolver
        get() = runtime.hookResolver
    internal val hookRegistrar
        get() = runtime.hookRegistrar
    internal val mainHandler: Handler
        get() = runtime.mainHandler
    internal val contentUiLanguagePrefs: SharedPreferences?
        get() = preferences()
    internal val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }

    /** 方法发现与安装去重；各 Hook 家族独立，互不共享第一次安装判定。 */
    internal val lyricTextInstallDedup = AppleLyricsMethodInstallDedup()
    internal val nativeTextInstallDedup = AppleLyricsMethodInstallDedup()
    internal val pronunciationRenderInstallDedup = AppleLyricsMethodInstallDedup()
    internal val appleOfficialTranslationProbeGuard = ThreadLocalReentryGuard()
    internal val pronunciationDiagnostics = AppleLyricsPronunciationDiagnostics()
    internal val pronunciationState = AppleLyricsPronunciationState()
    val nativeOnlineTranslationStore = AppleNativeOnlineTranslationStore()
    internal val deferredTranslationPresentation = AppleLyricsDeferredPresentation()
    @Volatile
    internal var appleLyricsLoadMethod: Method? = null
    @Volatile
    private var activeLyricsResultObserver: Pair<WeakReference<Any>, Any>? = null
    @Volatile
    internal var appleLyricsPresentationMethod: Method? = null
    @Volatile
    internal var appleLyricsResultPresentationMethod: Method? = null
    internal val presentationBinding = AppleLyricsPresentationBinding()
    internal val playbackBinding = AppleLyricsPlaybackBinding()
    internal val currentAppleLyricsSongId: String?
        get() = presentationBinding.songId()
    private data class AppleLyricsPresentationPerformanceContext(
        val stagePrefix: String,
        val songId: String?,
        val methodName: String,
        val startedAtNanos: Long,
    )
    private val appleLyricsPresentationPerformanceContexts =
        ThreadLocalStack<AppleLyricsPresentationPerformanceContext>()
    private val appleLyricsPresentationPerformanceMethods =
        ConcurrentHashMap.newKeySet<Executable>()
    /** 已绑定「可见即隐藏」监听的 loading_progress View 与已强制的翻译按钮，按实例去重。 */
    internal val viewTracking = AppleLyricsViewTracking()
    internal val scrollPresentationState = AppleLyricsScrollPresentationState()
    internal val activeLineUpdateController by lazy {
        AppleLyricsActiveLineUpdateController(
            post = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
            intervalMs = SUPPLEMENT_ACTIVE_LINE_INTERVAL_MS,
            update = ::updateSupplementActiveLine,
        )
    }
    internal lateinit var lyricsRuntimeTarget: AppleMusicHookTarget
    private val lyricsUiTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).target
    }
    private val lyricsSongTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.APPLE_SONG_MODEL_CLASS).target
    }
    private val systemFontHooks by lazy {
        AppleSystemFontHooks(
            runtime = runtime,
            preferences = preferences,
            currentSongId = ::currentSongId,
            nativeRawWordVectorText = { nativeRawWordVectorText(it) },
            currentPlaybackPositionMs = { appleLyricsCurrentPlaybackPositionMs() },
        )
    }
    internal val blurHooks by lazy {
        AppleLyricsBlurHooks(
            runtime = runtime,
            preferences = preferences,
            playbackHooks = playbackHooks,
            currentFragment = { presentationBinding.fragment() },
        )
    }
    internal val diagnostics by lazy {
        AppleLyricsDiagnostics(
            runtime = runtime,
            currentSongId = ::currentSongId,
            epoxyDataBindingFromHolderCallback = epoxyDataBindingFromHolderCallback,
            bindingDiagnostic = { stage, context, details, dedupeKey -> logApplePronunciationBindingDiagnostic(stage, context, details, dedupeKey) },
            uiStateDiagnostic = { fragment, stage ->
                logAppleLyricsUiState(fragment, stage)
            },
            recyclerLifecycleDiagnostic = { recyclerView, stage -> logAppleLyricsRecyclerLifecycle(recyclerView, stage) },
            appleRecyclerViewPredicate = { isAppleRecyclerViewInstance(it) },
        )
    }

    fun currentSongId(): String? = currentAppleLyricsSongId

    internal fun installSourceSwitchPresentationPerformanceHook(
        method: Method,
        stagePrefix: String,
    ) {
        if (!AppleProviderEnvironment.diagnosticsEnabled || !appleLyricsPresentationPerformanceMethods.add(method)) return
        hookRegistrar.installScopedHook(
            executable = method,
            enter = { chain ->
                val pointer = chain.args.firstOrNull()
                val songId = pointer?.let { candidate ->
                    runCatching {
                        lyricsNativeCall(
                            candidate,
                            AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD,
                        )
                    }.getOrNull()?.let { this.nativeSongId(it) }
                } ?: currentAppleLyricsSongId
                if (!AppleSourceSwitchPerformanceDiagnostics.isTracing(songId)) {
                    return@installScopedHook false
                }
                val context = AppleLyricsPresentationPerformanceContext(
                    stagePrefix = stagePrefix,
                    songId = songId,
                    methodName = "${method.declaringClass.name}.${method.name}/${method.parameterCount}",
                    startedAtNanos = SystemClock.elapsedRealtimeNanos(),
                )
                appleLyricsPresentationPerformanceContexts.push(context)
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = songId,
                    stage = "${stagePrefix}_started",
                    details = "method=${context.methodName},pointer=${pointer?.javaClass?.name ?: "none"}",
                )
                true
            },
            after = { _, _ ->
                val context = appleLyricsPresentationPerformanceContexts.current
                    ?: return@installScopedHook
                val elapsedNanos =
                    SystemClock.elapsedRealtimeNanos() - context.startedAtNanos
                AppleSourceSwitchPerformanceDiagnostics.record(
                    songId = context.songId,
                    event = context.stagePrefix,
                    durationNanos = elapsedNanos,
                    details = "method=${context.methodName}",
                )
                AppleSourceSwitchPerformanceDiagnostics.stageForSong(
                    songId = context.songId,
                    stage = "${context.stagePrefix}_finished",
                    details = "method=${context.methodName},elapsedMs=${elapsedNanos / 1_000_000.0}",
                )
            },
            exit = { appleLyricsPresentationPerformanceContexts.pop() },
        )
    }

    internal fun lyricsRuntimeMember(member: AppleMusicRuntimeMember): String =
        lyricsRuntimeTarget.runtimeMemberName(member)

    internal fun lyricsUiMember(member: AppleMusicRuntimeMember): String =
        lyricsUiTarget.runtimeMemberName(member)

    internal fun lyricsSongMember(member: AppleMusicRuntimeMember): String =
        lyricsSongTarget.runtimeMemberName(member)

    internal fun lyricsNativeCall(
        instance: Any?,
        member: AppleMusicRuntimeMember,
        vararg args: Any?,
    ): Any? = instance?.let {
        AppleReflection.call(it, lyricsRuntimeMember(member), *args)
    }

    internal fun lyricsUiField(instance: Any?, member: AppleMusicRuntimeMember): Any? =
        instance?.let { AppleReflection.field(it, lyricsUiMember(member)) }

    fun isSimplifyTraditionalLyricsEnabled(): Boolean =
        contentUiLanguagePrefs?.getBoolean(
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT,
            AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT,
        ) == true

    private val translationPreferenceHitLogged = AtomicBoolean(false)
    private val pronunciationPreferenceHitLogged = AtomicBoolean(false)

    fun hookTranslationPreference() {
        val exact = hookResolver.exactLyricsPreferences()
        if (exact != null) {
            installPreferenceBindings(exact)
            return
        }
        // Base Application.onCreate is still inside the host subclass onCreate. Only
        // initialize discovered preference keys after that call returns, off the UI thread.
        mainHandler.post {
            coroutineScope.launch(Dispatchers.IO) {
                runCatching { hookResolver.discoverLyricsPreferences() }
                    .onSuccess { bindings ->
                        if (bindings != null) mainHandler.post {
                            hookRegistrar.withModule("hookTranslationPreference") {
                                installPreferenceBindings(bindings)
                            }
                        }
                    }
                    .onFailure { ProviderLogger.error("Apple Music 歌词偏好自动定位失败，其他歌词功能继续运行", it) }
            }
        }
    }

    private fun installPreferenceBindings(bindings: AppleLyricsPreferenceBindings) {
        // Keep distinct literal diagnostics for source-level regression checks and log filtering.
        // 歌词翻译偏好 Hook 已安装 / 歌词发音偏好 Hook 已安装
        // 歌词翻译偏好 Hook 首次命中 / 歌词发音偏好 Hook 首次命中
        installAppleLyricsPreferenceHooks(bindings, install = { pronunciation, binding ->
            val label = if (pronunciation) "发音" else "翻译"
            val logged = if (pronunciation) pronunciationPreferenceHitLogged else translationPreferenceHitLogged
            hookRegistrar.installHook(binding.setter, after = { chain, _ ->
                (chain.args.firstOrNull() as? Boolean)?.let { selected ->
                    if (AppleProviderEnvironment.diagnosticsEnabled && logged.compareAndSet(false, true)) {
                        ProviderLogger.diagnostic("Apple Music 歌词${label}偏好 Hook 首次命中: selected=$selected")
                    }
                    if (pronunciation) PreferencesMonitor.notifyPronunciationSelectedChanged(selected)
                    else PreferencesMonitor.notifyTranslationSelectedChanged(selected)
                }
            })
            ProviderLogger.debug("Apple Music 歌词${label}偏好 Hook 已安装")
        }, onFailure = { pronunciation, error ->
            ProviderLogger.error("Apple Music 歌词偏好 Hook 降级: pronunciation=$pronunciation", error)
        })
    }


    private val consumptionDiagnostics = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun hasCurrentOnlineContentConsumption(
        songId: String?,
        contentType: String,
    ): Boolean {
        val consumed = computeOnlineContentConsumption(songId, contentType)
        if (AppleProviderEnvironment.diagnosticsEnabled && contentType == "translation" && !songId.isNullOrBlank()) {
            val details = describeTranslationConsumption(songId)
            val line = "结果=$consumed, $details"
            // 状态不变就不重复记录，菜单每次重绘都会调用本函数。
            if (consumptionDiagnostics.put(songId, line) != line) {
                ProviderLogger.diagnostic("在线翻译消费判定: id=$songId, $line")
            }
        }
        return consumed
    }

    private fun describeTranslationConsumption(songId: String): String {
        if (songId != currentAppleLyricsSongId) return "当前歌词页歌曲=${currentAppleLyricsSongId}与请求不符"
        val supplementTranslation = missingLyricsSupplement().hasTranslation(songId)
        val nativeEnabled = isNativeOnlineTranslationEnabled()
        val songNative = currentAppleLyricsNativeSong(songId)
        val lines = songNative?.let { currentAppleLyricsNativeLines(it) }.orEmpty()
        var official = 0
        var online = 0
        lines.forEach { line ->
            val hasOfficial = AppleNativeOnlineTranslationStore.sanitizeContent(
                nativeRawLineText(
                    line,
                    lyricsRuntimeMember(AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD),
                )
            ) != null
            if (hasOfficial) official++
            if (onlineTranslationForNativeLine(line) != null) online++
        }
        val first = lines.firstOrNull()
        val firstBegin = first?.let { (lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number)?.toLong() }
        val firstEnd = first?.let { (lyricsNativeCall(it, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Number)?.toLong() }
        val overlay = nativeOnlineTranslationStore.diagnostic(songId, firstBegin ?: -1L, firstEnd ?: -1L)
        return "补充含翻译=$supplementTranslation, 原生在线翻译开关=$nativeEnabled, " +
            "原生歌曲=${songNative != null}, 原生行=${lines.size}, 行自带官方翻译=$official, 行可匹配在线翻译=$online, " +
            "首行=$firstBegin-$firstEnd, $overlay"
    }

    private fun computeOnlineContentConsumption(
        songId: String?,
        contentType: String,
    ): Boolean {
        if (songId.isNullOrBlank() || songId != currentAppleLyricsSongId) return false
        if (contentType == "lyrics") {
            return missingLyricsSupplement().sourceInfo(songId)?.selectedSource != null
        }
        if (contentType == "translation" && missingLyricsSupplement().hasTranslation(songId)) {
            return true
        }
        if (!isNativeOnlineTranslationEnabled()) return false
        val songNative = currentAppleLyricsNativeSong(songId) ?: return false
        val lines = currentAppleLyricsNativeLines(songNative)
        if (lines.isEmpty()) return false

        return when (contentType) {
            "translation" -> lines.any { line ->
                AppleNativeOnlineTranslationStore.sanitizeContent(
                    nativeRawLineText(
                        line,
                        lyricsRuntimeMember(
                            AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD
                        ),
                    )
                ) == null && onlineTranslationForNativeLine(line) != null
            }
            "pronunciation" -> {
                if (shouldHideMandarinPronunciation(songId = songId)) return false
                lines.any { line ->
                    val originalText = nativeOriginalLineText(line)
                    val officialPronunciation = RomanizationPolicy.sanitize(
                        originalText = originalText,
                        pronunciation = nativeRawLineText(
                            line,
                            lyricsRuntimeMember(
                                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD
                            ),
                        ),
                    )
                    officialPronunciation == null &&
                        onlinePronunciationForNativeLine(line) != null
                }
            }
            else -> false
        }
    }

    internal fun observePlayerLyricsViewModelResult(viewModel: Any, songId: String) {
        val resultLiveData = runCatching {
            AppleReflection.call(
                viewModel,
                lyricsRuntimeMember(
                    AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_RESULT_GETTER
                ),
            )
        }.getOrNull()
        if (resultLiveData == null) {
            diagnoseNativeResult("结果 LiveData 获取失败: id=$songId, vm=${System.identityHashCode(viewModel)}")
            return
        }

        val initialValue = runCatching {
            AppleReflection.call(resultLiveData, "getValue")
        }.getOrNull()
        diagnoseNativeResult(
            "观察器排程: id=$songId, vm=${System.identityHashCode(viewModel)}, " +
                "liveData=${System.identityHashCode(resultLiveData)}, " +
                "initial=${initialValue?.javaClass?.name}@${initialValue?.let(System::identityHashCode)}"
        )

        mainHandler.post {
            // 清理上一个 Observer，避免泄露或重复监听
            activeLyricsResultObserver?.let { (liveDataRef, oldObserver) ->
                val oldLiveData = liveDataRef.get()
                if (oldLiveData != null) {
                    runCatching {
                        AppleReflection.call(oldLiveData, "removeObserver", oldObserver)
                    }
                }
            }

            val observerClass = runCatching {
                classLoader.loadClass("androidx.lifecycle.Observer")
            }.getOrNull()
            if (observerClass == null) {
                diagnoseNativeResult("Observer 类加载失败: id=$songId")
                return@post
            }

            val observer = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(observerClass),
            ) { _, method, args ->
                if (method.name == "onChanged") {
                    val result = args?.firstOrNull()
                    diagnoseNativeResult(
                        "onChanged: id=$songId, liveData=${System.identityHashCode(resultLiveData)}, " +
                            "result=${result?.javaClass?.name}@${result?.let(System::identityHashCode)}, " +
                            "sameAsInitial=${result === initialValue}, " +
                            "first=${runCatching { AppleReflection.call(result!!, "getFirst") }.getOrNull()?.javaClass?.name}, " +
                            "second=${runCatching { AppleReflection.call(result!!, "getSecond") }.getOrNull()?.javaClass?.name}"
                    )
                    if (result !== initialValue) {
                        handleNativeLyricsResultEmitted(songId, result)
                    }
                }
                null
            }

            activeLyricsResultObserver = Pair(WeakReference(resultLiveData), observer)
            runCatching {
                AppleReflection.call(resultLiveData, "observeForever", observer)
            }.onSuccess {
                diagnoseNativeResult(
                    "观察器已安装: id=$songId, liveData=${System.identityHashCode(resultLiveData)}"
                )
            }.onFailure {
                ProviderLogger.debug("Apple Music 原生歌词 LiveData 观察者安装失败: ${it.message}")
            }
        }
    }

    private fun diagnoseNativeResult(message: String) {
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.info("[NativeResultDiag] $message")
        }
    }

    private fun handleNativeLyricsResultEmitted(songId: String, result: Any?) {
        if (result == null) {
            diagnoseNativeResult("结果为空被忽略: id=$songId")
            return
        }
        val pointer = runCatching {
            AppleReflection.call(result, "getFirst")
        }.getOrNull()

        // 如果是本模块注入的三方补充模型，绝不能当作 Apple 原生歌词结果处理
        if (pointer != null && missingLyricsSupplement().isSupplementPointer(pointer)) {
            diagnoseNativeResult("补充模型指针被忽略: id=$songId")
            return
        }

        if (pointer != null) {
            val songNative = runCatching {
                lyricsNativeCall(pointer, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD)
            }.getOrNull()
            val nativeSongId = songNative?.let { this.nativeSongId(it) }
            val hasLines = appleNativeSongHasLines(songNative)
            ProviderLogger.info(
                "Apple Music 原生歌词请求结果已发射: id=${nativeSongId ?: songId}, " +
                    "hasLines=$hasLines"
            )
            missingLyricsSupplement().onNativeLyricsState(
                songId = nativeSongId ?: songId,
                hasLines = hasLines,
            )
        } else {
            // pointer 为 null 表示 Apple 原生歌词请求已结束且无歌词（404 / 异常 / 空结果）
            val exception = runCatching {
                AppleReflection.call(result, "getSecond")
            }.getOrNull()
            ProviderLogger.info(
                "Apple Music 原生歌词请求结果已发射: id=$songId, " +
                    "result=empty, error=${exception?.javaClass?.name}"
            )
            missingLyricsSupplement().onNativeLyricsState(
                songId = songId,
                hasLines = false,
            )
        }
    }

    internal fun cleanupLyricsResultObserver() {
        diagnoseNativeResult("观察器被清理")
        mainHandler.post {
            activeLyricsResultObserver?.let { (liveDataRef, oldObserver) ->
                val oldLiveData = liveDataRef.get()
                if (oldLiveData != null) {
                    runCatching {
                        AppleReflection.call(oldLiveData, "removeObserver", oldObserver)
                    }
                }
            }
            activeLyricsResultObserver = null
        }
    }



    fun refreshAppleLyricsDisplay() {
        mainHandler.post {
            val method = appleLyricsLoadMethod ?: return@post
            val binding = playbackBinding.snapshot()
            val viewModel = binding.viewModel ?: return@post
            val item = binding.item ?: return@post
            runCatching {
                method.invoke(viewModel, item)
            }.onFailure {
                ProviderLogger.error("Apple Music 当前歌词页刷新失败", it)
            }
        }
    }

    fun refreshAppleLyricsBlurEffect() = blurHooks.refreshAppleLyricsBlurEffect()

    fun refreshAppleSystemFont() = systemFontHooks.refreshAppleSystemFont()

    fun refreshAppleSystemFontWeight() = refreshAppleSystemFont()

    internal fun scheduleAppleLyricsBlur(
        recyclerView: Any?,
        delayMs: Long = 0L,
    ) = blurHooks.scheduleAppleLyricsBlur(recyclerView, delayMs)

    internal fun refreshAppleLyricsRecyclerView(
        fragment: Any,
        expectedSongId: String?,
        expectedRevision: Long?,
        syncDisplayFlags: Boolean = false,
    ) {
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] refreshAppleLyricsRecyclerView: expectedSongId=$expectedSongId, expectedRevision=$expectedRevision"
            )
        }
        val recyclerView = resolveAppleLyricsRecyclerView(fragment) ?: run {
            logAppleLyricsUiState(
                fragment = fragment,
                stage = "refresh_resolve_failed",
                expectedSongId = expectedSongId,
                expectedRevision = expectedRevision,
            )
            ProviderLogger.debug(
                "Apple Music 歌词 RecyclerView 解析失败: fragment=${fragment.javaClass.name}"
            )
            return
        }
        logAppleLyricsUiState(
            fragment = fragment,
            stage = "refresh_resolved",
            expectedSongId = expectedSongId,
            expectedRevision = expectedRevision,
        )

        val bindingTicket = presentationBinding.ticket()
        fun isRefreshCurrent(): Boolean =
            presentationBinding.isCurrent(bindingTicket) &&
                (expectedSongId == null || bindingTicket.songId == expectedSongId) &&
                (expectedRevision == null ||
                    nativeOnlineTranslationStore.isCurrentRevision(
                        songId = expectedSongId,
                        revision = expectedRevision,
                    ))

        val recyclerViewAsView = recyclerView as? View ?: return

        fun isComputingLayout(): Boolean =
            runCatching {
                AppleReflection.call(recyclerView, "isComputingLayout") as? Boolean
            }.getOrNull() == true

        fun rebindAllRows(stage: String) {
            if (!isRefreshCurrent()) return
            if (isComputingLayout()) {
                recyclerViewAsView.postOnAnimation { rebindAllRows(stage) }
                return
            }
            val adapter = appleRecyclerAdapter(recyclerView) ?: return
            val itemCount = appleRecyclerAdapterItemCount(adapter)
            if (itemCount <= 0) return
            if (syncDisplayFlags) {
                syncAppleLyricsAdapterDisplayFlags(adapter)
                invalidateAppleLyricsVisibleHolderTextCaches(recyclerView)
            }
            if (AppleProviderEnvironment.diagnosticsEnabled) {
                ProviderLogger.debug(
                    "[LyricsScrollDiag] rebindAllRows: stage=$stage, expectedSongId=$expectedSongId, itemCount=$itemCount"
                )
            }
            runCatching {
                appleRecyclerNotifyDataSetChanged(adapter)
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 歌词列表完整重绑失败: stage=$stage",
                    it,
                )
            }
            if (AppleProviderEnvironment.diagnosticsEnabled) {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词列表已请求完整重绑: " +
                        "id=$expectedSongId, revision=${expectedRevision ?: "none"}, " +
                        "stage=$stage, adapter=${adapter.javaClass.name}, " +
                        "itemCount=$itemCount"
                )
            }
        }

        // Apple Music's karaoke adapter appends translation child views when it handles
        // PAYLOAD_TOGGLE_TRANSLATION. A scroll fixes duplicate rows because the normal full bind
        // clears and rebuilds the holder. Request the same full-bind path for all rows instead of
        // sending the partial translation payload or directly binding foreign-ClassLoader holders.
        recyclerViewAsView.postOnAnimation {
            rebindAllRows("next_frame")
        }
    }

    /**
     * Apple 的适配器显示开关由 ViewModel LiveData 驱动，偏好刚切换时会滞后数秒。
     * 本轮重绑若继续按旧开关执行，adapter 会整段跳过翻译/发音子行的渲染。
     */
    internal fun syncAppleLyricsAdapterDisplayFlags(adapter: Any) {
        syncAppleLyricsAdapterBooleanFlag(
            adapter = adapter,
            member = AppleMusicRuntimeMember.LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD,
            selected = PreferencesMonitor.isTranslationSelected(),
            label = "translation",
        )
        syncAppleLyricsAdapterBooleanFlag(
            adapter = adapter,
            member = AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD,
            selected = PreferencesMonitor.isPronunciationSelected(),
            label = "pronunciation",
        )
    }

    private fun syncAppleLyricsAdapterBooleanFlag(
        adapter: Any,
        member: AppleMusicRuntimeMember,
        selected: Boolean,
        label: String,
    ) {
        runCatching {
            val name = blurHooks.lyricsAdapterMember(adapter, member)
            val previous = AppleReflection.field(adapter, name) as? Boolean
            if (previous != selected) {
                AppleReflection.setField(adapter, name, selected)
                if (AppleProviderEnvironment.diagnosticsEnabled) {
                    ProviderLogger.diagnostic(
                        "[LyricsToggleDiag] adapter_$label synced: $previous->$selected, " +
                            "adapter=${adapter.javaClass.name}"
                    )
                }
            }
        }.onFailure {
            ProviderLogger.debug("Apple Music 歌词适配器 $label 显示开关同步失败: $it")
        }
    }

    private fun invalidateAppleLyricsVisibleHolderTextCaches(recyclerView: Any) {
        val group = recyclerView as? ViewGroup ?: return
        var holders = 0
        var fields = 0
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            val holder = blurHooks.appleLyricsChildViewHolder(recyclerView, child) ?: continue
            holders++
            fields += clearAppleLyricsHolderTextCaches(holder)
        }
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            val candidates = if (holders == 0) {
                runCatching {
                    blurHooks.findAppleLyricsChildViewHolderMethods(recyclerView.javaClass)
                        .joinToString(",") { "${it.name}(${it.parameterTypes.singleOrNull()?.name})" }
                }.getOrNull().orEmpty().ifBlank { "none" }
            } else {
                "-"
            }
            ProviderLogger.diagnostic(
                "[LyricsToggleDiag] holder_text_cache_cleared: " +
                    "holders=$holders, fields=$fields, candidates=$candidates"
            )
        }
    }

    internal fun refreshVisibleAppleLyricsRows(
        recyclerView: Any,
        songId: String,
        retryAfterLayout: Boolean = true,
        isCurrent: () -> Boolean = { true },
    ) {
        if (!isCurrent()) return
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.debug(
                "[LyricsScrollDiag] refreshVisibleAppleLyricsRows: songId=$songId, retryAfterLayout=$retryAfterLayout"
            )
        }
        val recyclerViewAsView = recyclerView as? View ?: return
        val computingLayout = runCatching {
            AppleReflection.call(recyclerView, "isComputingLayout") as? Boolean
        }.getOrNull() == true
        if (computingLayout) {
            if (retryAfterLayout) {
                recyclerViewAsView.postOnAnimation {
                    refreshVisibleAppleLyricsRows(
                        recyclerView = recyclerView,
                        songId = songId,
                        retryAfterLayout = false,
                        isCurrent = isCurrent,
                    )
                }
            }
            return
        }
        val adapter = appleRecyclerAdapter(recyclerView) ?: return
        val positions = (recyclerView as? ViewGroup)
            ?.let { group ->
                (0 until group.childCount).mapNotNull { index ->
                    val child = group.getChildAt(index)
                    blurHooks.appleLyricsChildAdapterPosition(recyclerView, child)
                        .takeIf { it >= 0 }
                }
            }
            .orEmpty()
        val range = visibleAdapterRange(positions)
            ?: runCatching {
                val layoutManager = AppleReflection.call(recyclerView, "getLayoutManager")
                val first = (layoutManager?.let {
                    AppleReflection.call(it, "findFirstVisibleItemPosition")
                } as? Number)?.toInt() ?: return@runCatching null
                val childCount = (recyclerView as? ViewGroup)?.childCount ?: return@runCatching null
                visibleAdapterRange(first until (first + childCount))
            }.getOrNull()
            ?: return
        val count = range.last - range.first + 1
        runCatching {
            AppleReflection.call(adapter, "notifyItemRangeChanged", range.first, count)
        }.recoverCatching {
            range.forEach { position ->
                AppleReflection.call(adapter, "notifyItemChanged", position)
            }
        }.onFailure {
            ProviderLogger.error(
                "Apple Music 补充歌词翻译可见行重绑失败: id=$songId, range=$range",
                it,
            )
        }
        AppleSourceSwitchPerformanceDiagnostics.record(
            songId = songId,
            event = "supplement_translation_visible_rebind",
            units = count.toLong(),
            details = "range=$range,adapter=${adapter.javaClass.name}",
        )
    }

    fun hookAppleSystemFontWeight() = systemFontHooks.hookAppleSystemFontWeight()

    fun hookAppleLyricsBlurEffect() = blurHooks.hookAppleLyricsBlurEffect()

    fun hookAppleLyricsBindingDiagnostics() = diagnostics.hookAppleLyricsBindingDiagnostics()

    fun hookAppleLyricsUiDiagnostics() = diagnostics.hookAppleLyricsUiDiagnostics()

    fun isAppleLyricsRecyclerAdapter(adapter: Any?): Boolean =
        blurHooks.isAppleLyricsRecyclerAdapter(adapter)
}
