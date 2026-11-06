/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

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
import com.juren233.hyperlyricsenhanced.common.lyric.RomanizationPolicy
import com.juren233.hyperlyricsenhanced.lyric.model.Song as LocalSong
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCall
import com.juren233.hle.providers.applemusic.xposed.util.extensions.ScreenStateMonitor
import com.juren233.hle.providers.applemusic.xposed.util.extensions.inflate
import com.juren233.hle.providers.applemusic.xposed.util.extensions.json
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleContentLocalizationHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleDebugNetworkHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleAtmosVolumeDiagnostics
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleFrameworkMetadataHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.ApplePlaybackHooks
import com.juren233.hle.providers.applemusic.xposed.hooks.FunctionalAppleMusicHookModule
import com.juren233.hle.providers.applemusic.xposed.lyrics.AppleOnlineSourceMenuHooks
import com.juren233.hle.providers.applemusic.xposed.lyrics.AppleLyricsShareExportHooks
import com.juren233.hle.providers.applemusic.xposed.internal.ThreadLocalReentryGuard
import com.juren233.hle.providers.applemusic.xposed.internal.ThreadLocalStack
import com.juren233.hle.providers.applemusic.xposed.internal.WeakIdentityMap
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import kotlinx.serialization.decodeFromString
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun AppleMusicProviderOrchestrator.startHooks() {
    val costs = if (AppleProviderEnvironment.diagnosticsEnabled) mutableListOf<StartupHookModuleCost>() else null
    hookModules().asSequence()
        .filter { hookModule -> !hookModule.debugOnly || AppleProviderEnvironment.diagnosticsEnabled }
        .forEach { hookModule ->
            val startNs = if (costs != null) System.nanoTime() else 0L
            hookRegistrar.withModule(hookModule.id, hookModule::installHooks)
            costs?.add(
                StartupHookModuleCost(
                    id = hookModule.id,
                    debugOnly = hookModule.debugOnly,
                    wallNs = System.nanoTime() - startNs,
                    installed = hookRegistrar.installCost.snapshot(hookModule.id),
                )
            )
        }
    costs?.let(::logStartupHookCosts)
}

private class StartupHookModuleCost(
    val id: String,
    val debugOnly: Boolean,
    val wallNs: Long,
    val installed: com.juren233.hle.providers.applemusic.xposed.hooks.AppleHookInstallCost.Entry,
) {
    /** 去掉安装日志后的耗时，近似 release 包的开销。 */
    val workNs: Long get() = wallNs - installed.logNs
}

/**
 * debug 包专用：冷启动 Hook 安装耗时拆分。`work` 已扣除每个 Hook 的安装日志；
 * `hook` 为 `module.hook()` 本身，其余（`work - hook`）是目标解析与反射查找。
 */
private fun logStartupHookCosts(costs: List<StartupHookModuleCost>) {
    fun ms(ns: Long) = "%.1f".format(ns / 1_000_000.0)
    val release = costs.filterNot { it.debugOnly }
    val top = release.sortedByDescending { it.workNs }.take(12).joinToString { cost ->
        "${cost.id}=${ms(cost.workNs)}/${ms(cost.installed.hookNs)}/n${cost.installed.hooks}"
    }
    ProviderLogger.info(
        "[StartupCost] stage=hooks, wallMs=${ms(costs.sumOf { it.wallNs })}, " +
            "logMs=${ms(costs.sumOf { it.installed.logNs })}, " +
            "releaseWorkMs=${ms(release.sumOf { it.workNs })}, " +
            "releaseHookMs=${ms(release.sumOf { it.installed.hookNs })}, " +
            "releaseHooks=${release.sumOf { it.installed.hooks }}, " +
            "debugOnlyWorkMs=${ms(costs.filter { it.debugOnly }.sumOf { it.workNs })}, " +
            "top(work/hook/n)=[$top]"
    )
}

internal fun AppleMusicProviderOrchestrator.hookModuleIdsForBuild(debug: Boolean): List<String> =
    hookModules()
        .filter { hookModule -> !hookModule.debugOnly || debug }
        .map { hookModule -> hookModule.id }

internal fun AppleMusicProviderOrchestrator.hookModules() = listOf(
    FunctionalAppleMusicHookModule(
        "hookActivityThemeInitialization",
        installer = { AppleActivityThemeInitializationHooks(runtime).install() },
    ),
    FunctionalAppleMusicHookModule(
        "hookMetadataSurfaceLifecycle",
        installer = { inAppMetadata.metadataSurfaceRuntime.installLifecycleHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookTranslationPreference",
        installer = { lyricsPlayback.lyricsHooks.hookTranslationPreference() },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaApiLocalization",
        installer = { catalogLanguage.contentLocalizationHooks.installMediaApiLocalization() },
    ),
    FunctionalAppleMusicHookModule(
        "hookContentHttpLocalization",
        installer = { catalogLanguage.contentLocalizationHooks.installContentHttpLocalization() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAmpApiHttpLocalization",
        installer = { catalogLanguage.contentLocalizationHooks.installAmpApiHttpLocalization() },
    ),
    FunctionalAppleMusicHookModule(
        "hookCatalogRequestLocalization",
        installer = { catalogLanguage.contentLocalizationHooks.installCatalogRequestLocalization() },
    ),
    FunctionalAppleMusicHookModule(
        "hookExoMediaPlayer",
        installer = { lyricsPlayback.playbackHooks.installExoMediaPlayer() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAtmosVolumeDiagnostics",
        debugOnly = true,
        installer = { lyricsPlayback.atmosphereVolumeDiagnostics.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaMetadataChange",
        installer = { lyricsPlayback.playbackMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookContentItemMetadata",
        installer = { inAppMetadata.contentItemMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppLibraryEntities",
        installer = { inAppMetadata.librarySurfaceHooks.installEntityHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookCollectionPageMetadataRefresh",
        installer = { inAppMetadata.collectionSurfaceHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookArtistProfileTopSongs",
        installer = { inAppMetadata.artistSurfaceHooks.installTopSongHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookArtistProfileMetadata",
        installer = { inAppMetadata.artistSurfaceHooks.installProfileHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookRecentlySearchedMetadata",
        installer = { inAppMetadata.mediaApiMetadataCoordinator.installRecentlySearchedHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppArtworkContinuity",
        installer = { inAppMetadata.inAppArtworkContinuityHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppListenNowArtworkContinuity",
        installer = { inAppMetadata.listenNowHooks.installArtworkContinuityHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppLibraryEpoxyRefresh",
        installer = { inAppMetadata.librarySurfaceHooks.installEpoxyHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppLibraryComposeRefresh",
        installer = { inAppMetadata.librarySurfaceHooks.installComposeHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookDebugListenNowArtworkLifecycle",
        debugOnly = true,
        installer = { inAppMetadata.listenNowHooks.installDebugArtworkLifecycleHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookVisibleMetadataDiagnostics",
        debugOnly = true,
        installer = { inAppMetadata.visibleMetadataDiagnostics.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppDataBindingRefresh",
        installer = { inAppMetadata.dataBindingHooks.installDataBindingHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppListenNowMetadataBinding",
        installer = { inAppMetadata.listenNowHooks.installMetadataBindingHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookBrowseSurfaceMetadata",
        installer = { inAppMetadata.artistSurfaceHooks.installBrowseHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookRecyclerViewCentralBinding",
        installer = { inAppMetadata.dataBindingHooks.installRecyclerHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppMetadata",
        installer = { inAppMetadata.queueMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppPlaybackItemConversion",
        installer = { inAppMetadata.playbackItemConversionHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookInAppActionSheetMetadata",
        installer = { inAppMetadata.actionSheetMetadataHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookSettingsCellularDataEntry",
        installer = {
            com.juren233.hle.providers.applemusic.xposed.hooks.AppleCellularDataSettingsHooks(
                runtime,
                preferences = { catalogLanguage.contentUiLanguagePrefs },
            ).install()
        },
    ),
    FunctionalAppleMusicHookModule(
        "hookCellularAvailability",
        installer = {
            com.juren233.hle.providers.applemusic.xposed.hooks.AppleCellularDataSettingsHooks(
                runtime,
                preferences = { catalogLanguage.contentUiLanguagePrefs },
            ).installCellularAvailability()
        },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaSessionMetadata",
        installer = { inAppMetadata.frameworkMetadataHooks.installMediaSessionMetadata() },
    ),
    FunctionalAppleMusicHookModule(
        "hookMediaSessionQueue",
        installer = { inAppMetadata.frameworkMetadataHooks.installMediaSessionQueue() },
    ),
    FunctionalAppleMusicHookModule(
        "hookPlaybackNotificationMetadata",
        installer = { inAppMetadata.frameworkMetadataHooks.installPlaybackNotificationMetadata() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleOfficialPronunciationLanguageMatching",
        installer = { lyricsPlayback.lyricsHooks.hookAppleOfficialPronunciationLanguageMatching() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsPreferredLanguages",
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsPreferredLanguages() },
    ),
    FunctionalAppleMusicHookModule(
        "hookApplePronunciationWordRendering",
        installer = { lyricsPlayback.lyricsHooks.hookApplePronunciationWordRendering() },
    ),
    FunctionalAppleMusicHookModule(
        "hookLyricBuildMethod",
        installer = { lyricsPlayback.lyricsHooks.hookLyricBuildMethod() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleNativeLyricsPresentation",
        installer = { lyricsPlayback.lyricsHooks.hookAppleNativeLyricsPresentation() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleSystemFontWeight",
        installer = { lyricsPlayback.lyricsHooks.hookAppleSystemFontWeight() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsBlurEffect",
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsBlurEffect() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsUiDiagnostics",
        debugOnly = true,
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsUiDiagnostics() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsBindingDiagnostics",
        debugOnly = true,
        installer = { lyricsPlayback.lyricsHooks.hookAppleLyricsBindingDiagnostics() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsSourceMenu",
        installer = { lyricsPlayback.onlineSourceMenuHooks.installSourceMenu() },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleLyricsShareExport",
        installer = {
            AppleLyricsShareExportHooks(
                runtime = runtime,
                preferences = { catalogLanguage.contentUiLanguagePrefs },
            ).install()
        },
    ),
    FunctionalAppleMusicHookModule(
        "hookAppleMissingLyricsSupplement",
        installer = { lyricsPlayback.missingLyricsHooks.installHooks() },
    ),
    FunctionalAppleMusicHookModule(
        "hookLyricsNetworkRequest",
        debugOnly = true,
        installer = { lyricsPlayback.debugNetworkHooks.installLyricsNetworkRequest() },
    ),
    FunctionalAppleMusicHookModule(
        "hookLyricsCookies",
        debugOnly = true,
        installer = { lyricsPlayback.debugNetworkHooks.installLyricsCookies() },
    ),
    FunctionalAppleMusicHookModule(
        "hookFinalLyricsHttp",
        debugOnly = true,
        installer = { lyricsPlayback.debugNetworkHooks.installFinalLyricsHttp() },
    ),
)
