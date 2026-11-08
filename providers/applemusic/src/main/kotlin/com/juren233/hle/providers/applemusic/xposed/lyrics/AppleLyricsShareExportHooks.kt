/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.juren233.hle.providers.applemusic.xposed.AppleMenuText
import com.juren233.hle.providers.applemusic.xposed.AppleMusicHookPoint
import com.juren233.hle.providers.applemusic.xposed.AppleMusicRuntimeMember
import com.juren233.hle.providers.applemusic.xposed.AppleProviderEnvironment
import com.juren233.hle.providers.applemusic.xposed.AppleMusicProviderRuntime
import com.juren233.hle.providers.applemusic.xposed.ProviderLogger
import com.juren233.hyperlyricsenhanced.contract.AppleLyricsSharePoster
import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys
import java.io.File
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCall
import java.util.concurrent.atomic.AtomicReference

/**
 * Adds "Save Image" and "Share Image" destinations to Apple Music's lyric share sheet. Apple
 * renders the card through its own pipeline (j1.h → i1.apply); we wrap it into a poster with a
 * song QR code, then answer i1.apply with an empty holder so the progress dialog closes without
 * launching the platform Intent it built (Snapchat-shaped even for our injected destinations).
 *
 * - Save writes the poster to Pictures/HyperLyrics_Enhanced as "title-artist-time".
 * - Share never touches the gallery and opens the chooser at the tap. Apple keeps rendering in the
 *   background; reads of the share file through Apple's own FileProvider wait until the poster is
 *   published, so the chooser preview and the receiving app always see the finished image.
 */
internal class AppleLyricsShareExportHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val preferences: () -> SharedPreferences?,
) {
    /** One export in flight; expires so an abandoned export never captures a later real share. */
    private enum class Mode(val id: String) { SAVE("hleSaveImage"), SHARE("hleShareImage") }

    private class Session(val mode: Mode, val startedAt: Long) {
        @Volatile var songUrl: String? = null
        @Volatile var songTitle: String? = null
        @Volatile var songArtist: String? = null
        @Volatile var progressFragment: Any? = null
        @Volatile var saved = false
        /** Share mode only: where the poster will appear and the gate that releases readers. */
        @Volatile var shareTarget: File? = null
        val shareReady = CountDownLatch(1)
    }

    /** Every Apple identifier comes from AppleMusicHookProfiles (1614); see LYRICS_SHARE_*. */
    private class Targets(
        val destinationList: Class<*>,
        val destinationConstructor: Constructor<*>,
        val destinationListField: Field,
        val itemConstructor: Constructor<*>,
        val icon: Method,
        val click: Method,
        val itemIdMethod: String,
        val itemUrlMethod: String,
        val itemTitleMethod: String,
        val itemArtistMethod: String,
        val progressCreate: Method,
        val progressCreateView: Method,
        val progressItemKey: String,
        val progressUrlKey: String,
        val rendered: Method,
        val resultEmptyMethod: String,
        val resultValueMethod: String,
        val emptyResultConstructor: Constructor<*>,
        val providerOpen: Method,
        val providerQuery: Method,
        val fileUriMethod: Method,
    )

    private val session = AtomicReference<Session?>(null)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val methodCache = ConcurrentHashMap<String, Method?>()
    private val firstHits = ConcurrentHashMap.newKeySet<String>()
    private lateinit var targets: Targets

    private fun enabled(): Boolean = preferences()?.getBoolean(
        AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_EXPORT,
        AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_EXPORT,
    ) ?: AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_EXPORT

    /** Diagnostic builds record the first real callback of each hook group. */
    private fun hit(group: String) {
        if (AppleProviderEnvironment.diagnosticsEnabled && firstHits.add(group)) {
            ProviderLogger.diagnostic("Apple Music 歌词图片导出 Hook 首次命中: $group")
        }
    }

    private fun activeSession(): Session? {
        val current = session.get() ?: return null
        if (SystemClock.elapsedRealtime() - current.startedAt <= SESSION_TIMEOUT_MS) return current
        replaceSession(current, null)
        return null
    }

    /** Never leaves a FileProvider reader blocked on a session that has been dropped. */
    private fun replaceSession(expected: Session?, next: Session?): Boolean {
        val replaced = if (expected == null) {
            session.getAndSet(next).also { it?.shareReady?.countDown() }; true
        } else session.compareAndSet(expected, next)
        if (replaced) expected?.shareReady?.countDown()
        return replaced
    }

    fun install() {
        // All-or-nothing: a button without its capture path would leak to the real Instagram flow.
        val resolved = runCatching(::resolveTargets).getOrElse { error ->
            ProviderLogger.diagnostic("Apple Music 歌词图片导出不支持当前版本，已跳过: ${error.message}")
            return
        }
        targets = resolved
        runCatching {
            installLyricsDestinationHook()
            installDestinationIconHook()
            installLyricsDestinationClickHook()
            installShareProgressHooks()
            installRenderedFileHook()
            installShareFileGateHooks()
            ProviderLogger.info("Apple Music 歌词图片导出 Hook 已安装")
        }.onFailure { error ->
            ProviderLogger.error("Apple Music 歌词导出 Hook 安装失败", error)
        }
    }

    private fun resolveTargets(): Targets {
        val resolver = runtime.hookResolver
        fun method(point: AppleMusicHookPoint) = resolver.resolveMethod(point)
        val list = resolver.resolveClass(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_LIST)
        val listTarget = list.target
        val itemClass = runtime.classLoader.loadClass(
            listTarget.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_DESTINATION_ITEM_CLASS),
        )
        val click = method(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_CLICK)
        val progress = method(AppleMusicHookPoint.LYRICS_SHARE_PROGRESS_CREATE)
        val rendered = method(AppleMusicHookPoint.LYRICS_SHARE_RENDERED_IMAGE)
        val providerOpen = method(AppleMusicHookPoint.LYRICS_SHARE_FILE_PROVIDER_OPEN)
        val uriMethodName = providerOpen.target.runtimeMemberName(
            AppleMusicRuntimeMember.LYRICS_SHARE_FILE_PROVIDER_URI_METHOD,
        )
        return Targets(
            destinationList = list.clazz,
            destinationConstructor = constructor(
                list.clazz,
                listTarget.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_DESTINATION_LIST_CONSTRUCTOR),
            ),
            destinationListField = list.clazz.getDeclaredField(
                listTarget.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_DESTINATION_LIST_FIELD),
            ).apply {
                isAccessible = true
                check(MutableList::class.java.isAssignableFrom(type)) { "Destination list is $type" }
            },
            itemConstructor = constructor(
                itemClass,
                listTarget.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_DESTINATION_ITEM_CONSTRUCTOR),
            ),
            icon = method(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_ICON).method,
            click = click.method,
            itemIdMethod = click.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_ITEM_ID_METHOD),
            itemUrlMethod = click.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_ITEM_URL_METHOD),
            itemTitleMethod = click.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_ITEM_TITLE_METHOD),
            itemArtistMethod = click.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_ITEM_ARTIST_METHOD),
            progressCreate = progress.method,
            progressCreateView = method(AppleMusicHookPoint.LYRICS_SHARE_PROGRESS_CREATE_VIEW).method,
            progressItemKey = progress.method.declaringClass.name + progress.target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_SHARE_PROGRESS_ITEM_KEY_SUFFIX,
            ),
            progressUrlKey = progress.method.declaringClass.name + progress.target.runtimeMemberName(
                AppleMusicRuntimeMember.LYRICS_SHARE_PROGRESS_LYRICS_URL_KEY_SUFFIX,
            ),
            rendered = rendered.method,
            resultEmptyMethod = rendered.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_RESULT_EMPTY_METHOD),
            resultValueMethod = rendered.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_RESULT_VALUE_METHOD),
            emptyResultConstructor = constructor(
                runtime.classLoader.loadClass(
                    rendered.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_RESULT_CLASS),
                ),
                rendered.target.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_RESULT_EMPTY_CONSTRUCTOR),
            ),
            providerOpen = providerOpen.method,
            providerQuery = method(AppleMusicHookPoint.LYRICS_SHARE_FILE_PROVIDER_QUERY).method,
            fileUriMethod = providerOpen.method.declaringClass.getDeclaredMethod(
                uriMethodName, Context::class.java, File::class.java, String::class.java,
            ).apply {
                isAccessible = true
                check(Modifier.isStatic(modifiers) && returnType == Uri::class.java) { "Unexpected $this" }
            },
        )
    }

    /** Profile constructors are declared as comma-separated binary type names; "" is the no-arg constructor. */
    private fun constructor(owner: Class<*>, parameterTypeNames: String): Constructor<*> {
        val wanted = parameterTypeNames.split(',').map(String::trim).filter(String::isNotEmpty)
        return owner.declaredConstructors.single { candidate ->
            candidate.parameterTypes.map(Class<*>::getName) == wanted
        }.apply { isAccessible = true }
    }

    private fun installLyricsDestinationHook() {
        val listField = targets.destinationListField
        runtime.hookRegistrar.installHook(targets.destinationConstructor, after = { chain, _ ->
            // Only the lyric sheet passes a null referrer and an Instagram-capable package set.
            if (chain.args.getOrNull(1) != null) return@installHook
            val allowedPackages = chain.args.getOrNull(2) as? Set<*> ?: return@installHook
            if (INSTAGRAM_PACKAGE !in allowedPackages || !enabled()) return@installHook
            hit("destination_list")
            val destination = chain.thisObject ?: return@installHook
            @Suppress("UNCHECKED_CAST")
            val list = listField.get(destination) as? MutableList<Any?> ?: return@installHook
            if (list.any { item -> Mode.entries.any { it.id == itemId(item) } }) return@installHook
            runCatching {
                Mode.entries.map { mode ->
                    // (size, id, title, package name, referrer)
                    targets.itemConstructor.newInstance(0L, mode.id, destinationTitle(mode), MODULE_PACKAGE, EXPORT_REFERRER)
                }
            }
                .onSuccess { list.addAll(it) }
                .onFailure { ProviderLogger.error("Apple Music 歌词导出入口创建失败", it) }
        })
    }

    /** The item looks icons up by package; Apple can't see ours, so supply drawn tiles instead. */
    private fun installDestinationIconHook() {
        runtime.hookRegistrar.installResultOverrideHook(targets.icon) { call, result ->
            val glyph = when (itemId(call.thisObject)) {
                Mode.SAVE.id -> AppleLyricsShareIcons.Glyph.SAVE
                Mode.SHARE.id -> AppleLyricsShareIcons.Glyph.SHARE
                else -> return@installResultOverrideHook result
            }
            hit("destination_icon")
            runCatching { AppleLyricsShareIcons.drawable(runtime.application, glyph) }.getOrDefault(result)
        }
    }

    private fun installLyricsDestinationClickHook() {
        runtime.hookRegistrar.installArgumentRewriteHook(targets.click) { call ->
            val selectedId = itemId(call.args.getOrNull(2))
            val mode = Mode.entries.firstOrNull { it.id == selectedId }
            if (mode != null) {
                hit("destination_click")
                val next = Session(mode, SystemClock.elapsedRealtime())
                replaceSession(null, next)
                ProviderLogger.diagnostic("Apple Music 歌词导出开始: mode=$mode")
                if (mode == Mode.SHARE) openShareEarly(next)
            } else {
                // Any real destination cancels a stale export so it is never intercepted.
                replaceSession(null, null)
            }
            null
        }
    }

    private fun installShareProgressHooks() {
        runtime.hookRegistrar.installHook(targets.progressCreate, after = { chain, _ ->
            val current = activeSession() ?: return@installHook
            val fragment = chain.thisObject ?: return@installHook
            hit("progress_create")
            current.progressFragment = fragment
            val arguments = invoke(fragment, "getArguments") as? Bundle
            @Suppress("DEPRECATION")
            val item = arguments?.getSerializable(targets.progressItemKey)
            val itemUrl = item?.let { invoke(it, targets.itemUrlMethod) as? String }
            current.songUrl = itemUrl?.takeIf { it.isNotBlank() } ?: arguments?.getString(targets.progressUrlKey)
            current.songTitle = item?.let { invoke(it, targets.itemTitleMethod) as? String }
            current.songArtist = item?.let { invoke(it, targets.itemArtistMethod) as? String }
        })

        runtime.hookRegistrar.installHook(targets.progressCreateView, after = { _, result ->
            val current = activeSession() ?: return@installHook
            hit("progress_create_view")
            ((result as? ViewGroup)?.getChildAt(0) as? TextView)?.text = progressTitle(current.mode)
        })
    }

    /**
     * Receives Apple's freshly rendered PNGs on a worker thread and rewrites the result holder.
     * `i1.apply` combines two renders: arg 0 is the lyric card, arg 1 is the story background
     * (`stickerBackground.png`, the same image Apple puts behind the card for Instagram stories).
     * While an export session is active its holder is replaced with an empty one, so the progress
     * dialog's observer never launches the platform Intent via startActivityForResult.
     */
    private fun installRenderedFileHook() {
        runtime.hookRegistrar.installResultOverrideHook(targets.rendered) { call, result ->
            val current = activeSession()
            val file = renderedFile(call.args.firstOrNull())
            val decision = AppleLyricsShareResultGate.decide(
                sessionActive = current != null,
                alreadySaved = current?.saved == true,
                hasRenderedFile = file != null,
            )
            if (decision.compose && current != null && file != null) {
                hit("rendered_image")
                val storyBackground = renderedFile(call.args.getOrNull(1))
                current.saved = when (current.mode) {
                    Mode.SAVE -> exportComposed(current, file, storyBackground)
                    Mode.SHARE -> publishShare(current) { output ->
                        AppleLyricsShareCardComposer.compose(
                            source = file,
                            background = background(storyBackground),
                            songUrl = current.songUrl,
                            withQr = qrCodeEnabled(),
                            title = qrTitle(),
                            subtitle = qrSubtitle(),
                            output = output,
                        ) || run { file.inputStream().use { it.copyTo(output) }; true }
                    }
                }
            }
            if (!decision.swallowIntent) return@installResultOverrideHook result
            // If the empty holder cannot be built, keep Apple's result rather than null it out.
            runCatching { targets.emptyResultConstructor.newInstance() }.getOrNull() ?: result
        }
    }

    /** Apple's Optional-like holder: the empty check returns false when a file is present. */
    private fun renderedFile(holder: Any?): File? {
        if (holder == null || invoke(holder, targets.resultEmptyMethod) as? Boolean != false) return null
        return invoke(holder, targets.resultValueMethod) as? File
    }

    private fun itemId(item: Any?): String? = item?.let { invoke(it, targets.itemIdMethod) as? String }

    private fun invoke(target: Any, name: String): Any? {
        val key = target.javaClass.name + "#" + name
        val method = methodCache.getOrPut(key) {
            runCatching { target.javaClass.getMethod(name) }.getOrNull()
        } ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    /** Runs on the tap: the chooser opens before Apple has even started rendering. */
    private fun openShareEarly(current: Session) {
        val directory = runtime.application.getExternalFilesDir(null) ?: return
        directory.listFiles { file -> file.name.startsWith(SHARE_FILE_PREFIX) }?.forEach { it.delete() }
        val target = File(directory, "$SHARE_FILE_PREFIX${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            targets.fileUriMethod.invoke(
                null, runtime.application, target, "${runtime.application.packageName}.provider",
            ) as? Uri
        }.onFailure { ProviderLogger.error("Apple Music 歌词分享 URI 生成失败", it) }.getOrNull() ?: return
        current.shareTarget = target
        mainHandler.post { presentChooser(uri) }
    }

    /** Writes the poster next to Apple's render, renames it into place and releases readers. */
    private fun publishShare(current: Session, write: (java.io.OutputStream) -> Boolean): Boolean {
        val target = current.shareTarget ?: return false
        val startedAt = SystemClock.elapsedRealtime()
        val temp = File(target.parentFile, "${target.name}.tmp")
        val published = runCatching {
            temp.outputStream().use(write) && temp.renameTo(target)
        }.onFailure { ProviderLogger.error("Apple Music 歌词分享图片生成失败", it) }.getOrDefault(false)
        current.shareReady.countDown()
        mainHandler.post { dismissProgress(current) }
        ProviderLogger.diagnostic(
            "Apple Music 歌词分享图片已就绪: published=$published, " +
                "compose=${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "sinceTap=${SystemClock.elapsedRealtime() - current.startedAt}ms",
        )
        return published
    }

    /**
     * The chooser may request the preview before Apple finishes rendering. Hold reads of the
     * pending share file (on the provider's binder thread) until it is published.
     */
    private fun installShareFileGateHooks() {
        val gate: (OfficialProviderCall) -> Unit = { call ->
            val uri = call.args.firstOrNull() as? Uri
            val current = session.get()
            val target = current?.shareTarget
            if (uri != null && target != null && uri.lastPathSegment == target.name) {
                hit("file_provider_gate")
                current.shareReady.await(SHARE_READ_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
        runtime.hookRegistrar.installHook(targets.providerOpen, before = gate)
        runtime.hookRegistrar.installHook(targets.providerQuery, before = gate)
    }

    /** Composes straight into the MediaStore stream: one decode, one encode, no temp file. */
    private fun exportComposed(current: Session, source: File, storyBackground: File?): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        var backgroundKind = ""
        val saved = writeToGallery(current, "jpg", "image/jpeg") { output ->
            val background = background(storyBackground)
            backgroundKind = background.label
            AppleLyricsShareCardComposer.compose(
                source = source,
                background = background,
                songUrl = current.songUrl,
                withQr = qrCodeEnabled(),
                title = qrTitle(),
                subtitle = qrSubtitle(),
                output = output,
            )
        } ?: return exportRaw(current) { source.inputStream() }
        ProviderLogger.diagnostic(
            "Apple Music 歌词图片已生成: ${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "background=$backgroundKind",
        )
        return saved
    }

    /**
     * Background chosen in the core settings. The custom image falls back to Apple's story
     * background when it is missing or unreadable (no image picked yet, or a core without
     * Remote File access); Apple's story background falls back to the cover colour.
     */
    private fun background(storyBackground: File?): AppleLyricsShareCardComposer.Background {
        val prefs = preferences()
        val mode = prefs?.getInt(
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND,
            AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND,
        ) ?: AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND
        return when (mode) {
            AppleMusicPreferenceKeys.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COVER_COLOR ->
                AppleLyricsShareCardComposer.Background.CoverColor(storyBackground)
            AppleMusicPreferenceKeys.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_CUSTOM_COLOR ->
                AppleLyricsShareCardComposer.Background.Flat(
                    prefs?.getInt(
                        AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
                        AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
                    ) ?: AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_COLOR,
                )
            AppleMusicPreferenceKeys.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_CUSTOM_IMAGE ->
                customImageBackground() ?: storyImageBackground(storyBackground)
            else -> storyImageBackground(storyBackground)
        }
    }

    private fun storyImageBackground(file: File?): AppleLyricsShareCardComposer.Background =
        file?.let { runCatching { AppleLyricsShareCardComposer.decode(it) }.getOrNull() }
            ?.let { AppleLyricsShareCardComposer.Background.Image(it, "cover_blur") }
            ?: AppleLyricsShareCardComposer.Background.CoverColor(null)

    /** runCatching also covers an older core whose host predates openRemoteFile. */
    private fun customImageBackground(): AppleLyricsShareCardComposer.Background? = runCatching {
        runtime.host.openRemoteFile(AppleMusicPreferenceKeys.APPLE_MUSIC_LYRICS_IMAGE_BACKGROUND_FILE)
            ?.use { AppleLyricsShareCardComposer.decode(it.fileDescriptor) }
    }.onFailure {
        ProviderLogger.error("Apple Music 歌词图片自定义背景读取失败", it)
    }.getOrNull()?.let { AppleLyricsShareCardComposer.Background.Image(it, "custom_image") }

    private fun qrCodeEnabled(): Boolean = preferences()?.getBoolean(
        AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
        AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE,
    ) ?: AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LYRICS_IMAGE_QR_CODE

    private fun exportRaw(current: Session, openInput: () -> InputStream?): Boolean =
        writeToGallery(current, "png", "image/png") { output ->
            openInput()?.use { it.copyTo(output) } != null
        } ?: false

    /** Returns null when [write] reports it could not produce an image (caller may fall back). */
    private fun writeToGallery(
        current: Session,
        extension: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Boolean,
    ): Boolean? {
        val resolver = runtime.application.contentResolver
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                AppleLyricsShareFileName.build(current.songTitle, current.songArtist, System.currentTimeMillis(), extension),
            )
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val written = runCatching {
            resolver.openOutputStream(destination).use { output ->
                requireNotNull(output) { "Unable to open MediaStore destination: $destination" }
                write(output)
            }
        }.onFailure { ProviderLogger.error("Apple Music 歌词图片导出失败", it) }.getOrDefault(false)
        if (!written) {
            resolver.delete(destination, null, null)
            return null
        }
        resolver.update(
            destination,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        mainHandler.post {
            dismissProgress(current)
            Toast.makeText(runtime.application, exportToast(), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun dismissProgress(current: Session) {
        current.progressFragment?.let { fragment ->
            current.progressFragment = null
            invoke(fragment, "dismissAllowingStateLoss")
        }
    }

    private fun presentChooser(uri: Uri) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            runtime.application.startActivity(
                Intent.createChooser(share, exportShareTitle())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }.onFailure { ProviderLogger.error("Apple Music 歌词图片分享面板打开失败", it) }
    }

    private fun destinationTitle(mode: Mode) = when (mode) {
        Mode.SAVE -> AppleMenuText.pick("保存图片", "Save Image")
        Mode.SHARE -> AppleMenuText.pick("分享图片", "Share Image")
    }
    private fun progressTitle(mode: Mode) = when (mode) {
        Mode.SAVE -> AppleMenuText.pick("正在保存图片…", "Saving image…")
        Mode.SHARE -> AppleMenuText.pick("正在生成图片…", "Creating image…")
    }
    private fun exportShareTitle() = AppleMenuText.pick("分享歌词图片", "Share Lyrics Image")
    private fun exportToast() = AppleMenuText.pick(
        "已保存到相册 Pictures/$ALBUM_NAME",
        "Saved to Pictures/$ALBUM_NAME",
    )
    private fun qrTitle() =
        AppleMenuText.pick(AppleLyricsSharePoster.qrTitle(chinese = true), AppleLyricsSharePoster.qrTitle(chinese = false))
    private fun qrSubtitle() =
        AppleMenuText.pick(AppleLyricsSharePoster.qrSubtitle(chinese = true), AppleLyricsSharePoster.qrSubtitle(chinese = false))

    private companion object {
        const val SHARE_FILE_PREFIX = "hle-lyrics-share-"
        const val EXPORT_REFERRER = "export=image"
        const val MODULE_PACKAGE = "com.juren233.hyperlyricsenhanced"
        const val ALBUM_NAME = "HyperLyrics_Enhanced"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val SESSION_TIMEOUT_MS = 30_000L
        const val SHARE_READ_WAIT_MS = 15_000L
    }
}
