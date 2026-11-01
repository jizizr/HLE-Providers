/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米音乐（com.miui.player）的歌词接口只接受数字歌曲 ID，歌词下载按数字 ID 进行。
 * 数字 ID 只有来源可信（HD/MIUI 播放队列快照）时才透传；QQ 手机版的 MEDIA_ID 是会话
 * 原值，本地歌曲时是本地库数字 ID 而非在线歌曲 ID（issue #62：本地库 ID 恰好命中另一首
 * 在线歌的歌词，顶着会话标题发布并按 id 永久缓存），必须先经搜索校验（见
 * resolveNumericId）。
 *
 * 4.44.0.9 真机证伪（2026-09-27）：小米音乐的 MediaSession MEDIA_ID 不是 QQ songmid
 * （三首不同歌曲的 MEDIA_ID 在单曲接口一律 code=0 且 data 为空数组），而系统媒体卡片
 * mediaFocusParam.shareData 携带的 songmid 才是真身（Exhale 实测：MEDIA_ID=
 * 001Q5MKR47b4JH 查无此歌，shareData 的 004OZHd728dJgm 返回正确歌曲）。因此
 * songmid → 数字 ID 的主路径优先级低于会话 extras 提取（见 QQMusicPluginEntry），
 * 本换算只在 extras 没有提供 songmid 时兜底：单曲接口（songmid 精确主键查询，必须带
 * songmid 与 format=json 参数：裸端点返回 HTML 页而非 JSON）→ 搜索接口。
 * 接口对无版权/小众歌曲返回 code=0 且 data 为空数组（如 000Afpal3mCeFw、
 * 003ubw8F1sZAyy 实测），此时走搜索接口兜底：候选必须「标题精确相等且歌手列表
 * 精确包含」才采纳（搜索命中的条目可能与输入 songmid 不同——同一首歌的不同版本
 * 条目，歌词一致）。
 * 搜索走 u.y.qq.com/cgi-bin/musicu.fcg 统一网关的
 * music.search.SearchCgiService（DoSearchForQQMusicDesktop）：旧搜索端点
 * soso/fcgi-bin/client_search_cp 已被 QQ 服务端下线（2026-09-27 实测所有
 * 查询一律 HTTP 500，真机侧表现为 FileNotFoundException，1.0.19 及之前
 * 依赖它兜底的换算全部失败）。
 * 响应同时携带权威 name/singer，车载歌词污染场景下用它覆盖 MediaSession 标题。
 * 负缓存按 mid+标题+歌手 组合键：污染首行失败不会挡住归一化后的重试；
 * 限流/网关错误只进 60s 短冷却（见 THROTTLED_RETRY_MS）。
 */
internal object QQMusicSongMidResolver {
    private const val TAG = "HLEProvider/QQMusic"
    private const val SINGLE_SONG_URL =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
    private const val SEARCH_URL =
        "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val REQUEST_TIMEOUT_MS = 15_000
    private const val FAILED_RETRY_MS = 10 * 60_000L

    /**
     * musicu.fcg 搜索网关按 IP 限流（服务层 code=2001，2026-09-27 真机与 PC 双侧实测）。
     * 全局搜索节流：任何一次真实搜索后至少间隔这么久才允许下一次，防止车载歌词
     * 逐行改写元数据把每行都变成一次真实搜索（同 IP 的桌面端调试流量也会连累手机）。
     */
    private const val SEARCH_MIN_INTERVAL_MS = 10_000L

    /** 限流/网关错误导致的失败只挡 60s（瞬时故障），与「查无此歌」的 10 分钟负缓存区分。 */
    private const val THROTTLED_RETRY_MS = 60_000L

    private val numericPattern = Regex("""^\d+$""")

    data class SongMidResolution(
        val numericSongId: String,
        val songName: String?,
        val singerName: String?,
    )

    private val resolved = ConcurrentHashMap<String, SongMidResolution>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val throttledAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastSearchStartedAtMs = 0L

    internal sealed interface SearchOutcome {
        data class Found(val candidates: List<SongMidResolution>) : SearchOutcome
        data object NotFound : SearchOutcome
        data object Throttled : SearchOutcome
    }

    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    fun isNumericSongId(id: String): Boolean = numericPattern.matches(id)

    /**
     * 小米音乐 qqmusicsdk 的 SongInfomation.getId() 把真实歌曲 ID 与来源标志位合并成长整型
     * （4.44.0.9 真机实测：id = songId | 0x2000_0000_0000_0000，陈粒《虚拟》107762076 →
     * 2305843009321456028）。QQ 歌曲数字 ID 不超过 32 位，超出的高位只能是标志位；
     * 原样透传会让歌词接口返回空内容，反而覆盖已取得的歌词。
     */
    internal fun sanitizeNumericSongId(id: String): String {
        val value = id.toLongOrNull() ?: return id
        if (value <= 0xFFFF_FFFFL) return id
        return (value and 0xFFFF_FFFFL).toString()
    }

    fun resolve(id: String, title: String?, artist: String?, trustedId: Boolean = false): SongMidResolution {
        if (isNumericSongId(id)) return resolveNumericId(id, title, artist, trustedId)
        resolved[id]?.let { return it }
        val failureKey = "$id|$title|$artist"
        failedAt[failureKey]?.let { failedAtMs ->
            if (elapsedRealtimeMs() - failedAtMs < FAILED_RETRY_MS) {
                throw IllegalStateException("QQ 歌曲 ID 换算失败(命中负缓存): mid=$id")
            }
            failedAt.remove(failureKey)
        }
        throttledAt[failureKey]?.let { throttledAtMs ->
            if (elapsedRealtimeMs() - throttledAtMs < THROTTLED_RETRY_MS) {
                throw IllegalStateException("QQ 歌曲 ID 换算失败(搜索限流冷却中): mid=$id")
            }
            throttledAt.remove(failureKey)
        }
        val resolution = fetchSingleSong(id) ?: when (val search = fetchBySearch(title, artist)) {
            is SearchOutcome.Found -> search.candidates.first()
            SearchOutcome.Throttled -> {
                throttledAt[failureKey] = elapsedRealtimeMs()
                throw IllegalStateException("QQ 歌曲 ID 换算失败(搜索限流或节流跳过): mid=$id")
            }
            SearchOutcome.NotFound, null -> {
                failedAt[failureKey] = elapsedRealtimeMs()
                throw IllegalStateException("QQ 歌曲 ID 换算失败(单曲与搜索接口均无此歌): mid=$id")
            }
        }
        resolved[id] = resolution
        Log.i(TAG, "QQ 歌曲 ID 已换算: mid=$id songId=${resolution.numericSongId}")
        return resolution
    }

    /**
     * 不可信数字 ID 的校验与换算。搜索命中同 ID 即确认在线歌；命中同曲但 ID 不同
     * （本地库数字 ID 的典型特征，issue #62）改用搜索到的 ID 取词，本地歌也能拿到
     * 正确的在线词；查无此歌不发布（与 songmid 换算路径同语义，负缓存 10 分钟）；
     * 限流/网络异常放行透传且不写正缓存——在线歌不受限流窗口误伤，下次重试校验。
     * 正/负缓存按 id|标题|歌手 组合键：本地库数字 ID 可能被复用，不能只按 id 记住换算。
     */
    internal fun resolveNumericId(
        id: String,
        title: String?,
        artist: String?,
        trustedId: Boolean,
        search: () -> SearchOutcome = { fetchBySearch(title, artist) },
    ): SongMidResolution {
        val numericId = sanitizeNumericSongId(id)
        if (trustedId) return SongMidResolution(numericId, null, null)
        val failureKey = "$id|$title|$artist"
        resolved[failureKey]?.let { return it }
        failedAt[failureKey]?.let { failedAtMs ->
            if (elapsedRealtimeMs() - failedAtMs < FAILED_RETRY_MS) {
                throw IllegalStateException("QQ 歌曲 ID 校验失败(命中负缓存): id=$id")
            }
            failedAt.remove(failureKey)
        }
        val outcome = try {
            search()
        } catch (error: Exception) {
            Log.w(TAG, "QQ 数字 ID 校验未完成(搜索异常), 放行透传: id=$id", error)
            return SongMidResolution(numericId, null, null)
        }
        val candidates = when (outcome) {
            is SearchOutcome.Found -> outcome.candidates
            SearchOutcome.Throttled -> {
                Log.w(TAG, "QQ 数字 ID 校验被限流跳过, 放行透传: id=$id")
                return SongMidResolution(numericId, null, null)
            }
            SearchOutcome.NotFound -> {
                failedAt[failureKey] = elapsedRealtimeMs()
                Log.i(TAG, "QQ 数字 ID 校验未命中同曲, 不发布: id=$id, title=$title, artist=$artist")
                throw IllegalStateException("QQ 歌曲 ID 校验失败(搜索无同曲): id=$id")
            }
        }
        val resolution = candidates.firstOrNull { it.numericSongId == numericId }
            ?: candidates.first().also {
                Log.i(
                    TAG,
                    "QQ 数字 ID 校验: 会话 ID 非在线歌曲 ID, 改用搜索结果: " +
                        "mediaId=$numericId -> songId=${it.numericSongId}, title=${it.songName}",
                )
            }
        resolved[failureKey] = resolution
        Log.i(TAG, "QQ 数字 ID 校验通过: id=$numericId -> songId=${resolution.numericSongId}")
        return resolution
    }

    private fun fetchSingleSong(songMid: String): SongMidResolution? {
        val url = "$SINGLE_SONG_URL?songmid=${URLEncoder.encode(songMid, "UTF-8")}&format=json"
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
        }
        return try {
            connection.getInputStream().bufferedReader().use { reader ->
                parseSingleSongResponse(reader.readText())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchBySearch(title: String?, artist: String?): SearchOutcome {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return SearchOutcome.NotFound
        val now = elapsedRealtimeMs()
        if (now - lastSearchStartedAtMs < SEARCH_MIN_INTERVAL_MS) return SearchOutcome.Throttled
        lastSearchStartedAtMs = now
        val url = "$SEARCH_URL?data=${URLEncoder.encode(searchRequestBody("$title $artist"), "UTF-8")}"
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
        }
        return try {
            val raw = connection.getInputStream().bufferedReader().use { reader ->
                reader.readText()
            }
            when {
                parseSearchThrottled(raw) -> SearchOutcome.Throttled
                else -> parseSearchCandidates(raw, title, artist)
                    ?.takeIf(List<SongMidResolution>::isNotEmpty)
                    ?.let { SearchOutcome.Found(it) }
                    ?: SearchOutcome.NotFound
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 搜索网关的失败分两类：服务层/顶层 code 非 0（限流 code=2001、网关错误等，
     * 瞬时性，短冷却后重试）；code=0 但结果为空（查无此歌，进 10 分钟负缓存）。
     */
    internal fun parseSearchThrottled(raw: String): Boolean = runCatching {
        val root = org.json.JSONObject(raw)
        if (root.optInt("code", 0) != 0) return@runCatching true
        val service = root.optJSONObject("music.search.SearchCgiService")
            ?: return@runCatching false
        service.optInt("code", 0) != 0
    }.getOrDefault(true)

    private fun searchRequestBody(query: String): String =
        org.json.JSONObject()
            .put(
                "music.search.SearchCgiService",
                org.json.JSONObject()
                    .put("method", "DoSearchForQQMusicDesktop")
                    .put("module", "music.search.SearchCgiService")
                    .put(
                        "param",
                        org.json.JSONObject()
                            .put("search_type", 0)
                            .put("query", query)
                            .put("page_num", 1)
                            .put("num_per_page", 10),
                    ),
            )
            .toString()

    internal fun parseSingleSongResponse(raw: String): SongMidResolution? = runCatching {
        val root = org.json.JSONObject(raw)
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        // 响应里 album.id 等嵌套 id 出现在歌曲 id 之前，必须取 data[0] 的顶层 id 字段
        val song = data.getJSONObject(0)
        val songId = song.optLong("id", -1L).takeIf { it > 0L }?.toString() ?: return null
        SongMidResolution(
            numericSongId = songId,
            songName = song.optString("name").takeIf(String::isNotBlank),
            singerName = song.optJSONArray("singer")?.optJSONObject(0)
                ?.optString("name")?.takeIf(String::isNotBlank),
        )
    }.getOrNull()

    internal fun parseSearchResponse(raw: String, title: String, artist: String): SongMidResolution? =
        parseSearchCandidates(raw, title, artist)?.firstOrNull()

    /**
     * 搜索兜底采纳「标题匹配 + 歌手精确相等」的候选（忽略大小写与空白差异）；
     * 标题匹配两轮扫描：先精确相等，再互相包含（小米音乐等端上会截断长标题，
     * 如 golden hour ⊂ In your golden hour），包含要求较短侧 ≥4 字符防误配。
     * 精确轮命中的全部候选优先于包含轮（songmid 兜底取第一个；数字 ID 校验在
     * 全部候选里优先取与会话同 ID 的条目，其余按序作为本地库 ID 的替换来源）。
     * 命中条目允许与输入 songmid 不同（同曲不同版本条目，歌词一致）。
     * musicu.fcg 网关的条目字段是 id/name（旧 client_search_cp 是 songid/songname），
     * 服务层与顶层各有一个 code，任一非 0 都视为失败。
     */
    internal fun parseSearchCandidates(raw: String, title: String, artist: String): List<SongMidResolution>? =
        runCatching<List<SongMidResolution>> {
        val root = org.json.JSONObject(raw)
        if (root.optInt("code", -1) != 0) return null
        val service = root.optJSONObject("music.search.SearchCgiService") ?: return null
        if (service.optInt("code", -1) != 0) return null
        val songs = service.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("song")?.optJSONArray("list") ?: return null
        val wantedTitle = normalizeForMatch(title)
        // 元数据歌手可能是「多人斜杠串」（R3HAB/Noah Neiman/Miranda Glory），
        // 拆段后按段匹配候选歌手，整串匹配仅作兜底
        val wantedArtistSegments = artist.split('/', '、')
            .map(::normalizeForMatch)
            .filter(String::isNotEmpty)
            .toSet()
        if (wantedTitle.isEmpty() || wantedArtistSegments.isEmpty()) return null
        for (allowContains in booleanArrayOf(false, true)) {
            val matched = ArrayList<SongMidResolution>()
            for (index in 0 until songs.length()) {
                val song = songs.getJSONObject(index)
                val candidateTitle = normalizeForMatch(song.optString("name"))
                val titleMatch = if (allowContains) {
                    minOf(candidateTitle.length, wantedTitle.length) >= 4 &&
                        (candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle))
                } else {
                    candidateTitle == wantedTitle
                }
                if (!titleMatch) continue
                val singers = song.optJSONArray("singer") ?: continue
                var matchedSinger: String? = null
                for (singerIndex in 0 until singers.length()) {
                    val singerName = singers.optJSONObject(singerIndex)?.optString("name")
                    val candidateSinger = normalizeForMatch(singerName ?: "")
                    if (candidateSinger.isNotEmpty() && candidateSinger in wantedArtistSegments) {
                        matchedSinger = singerName
                        break
                    }
                }
                matchedSinger ?: continue
                val songId = song.optLong("id", -1L).takeIf { it > 0L }?.toString() ?: continue
                matched.add(
                    SongMidResolution(
                        numericSongId = songId,
                        songName = song.optString("name").takeIf(String::isNotBlank),
                        singerName = matchedSinger,
                    ),
                )
            }
            if (matched.isNotEmpty()) return matched
        }
        emptyList()
    }.getOrNull()

    internal fun normalizeForMatch(value: String): String =
        value.trim().collapseWhitespace().lowercase()

    private fun String.collapseWhitespace(): String =
        replace(Regex("\\s+"), " ")
}

