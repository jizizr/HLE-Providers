/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.InputStream
import kotlin.math.roundToLong

internal object QishuiTrackIdentity {
    private val numericTrackId = Regex("""\d{5,}""")

    fun candidates(value: String?): Set<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptySet()
        return buildSet {
            add(raw)
            raw.substringAfterLast('/').substringBefore('?')
                .takeIf(String::isNotBlank)
                ?.let(::add)
            numericTrackId.findAll(raw).forEach { add(it.value) }
        }
    }

    fun apiTrackId(value: String?): String? = candidates(value)
        .firstOrNull { numericTrackId.matches(it) }
}

internal object QishuiApiClient {
    const val ENDPOINT = "https://api.qishui.com/luna/pc/track_v2"
    private const val SHARE_PAGE_ENDPOINT =
        "https://music.douyin.com/qishui/share/track"
    private const val PC_API_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    private const val SHARE_PAGE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
            "Chrome/138.0 Mobile Safari/537.36"
    private const val MAX_RESPONSE_CHARS = 2_000_000
    private const val MAX_UNBOUNDED_TIMELINE_MS = 10 * 60 * 1_000L
    private const val ROUTER_DATA_MARKER = "_ROUTER_DATA"

    fun buildRequestUrl(trackId: String): String {
        require(trackId.isNotBlank()) { "汽水 trackId 不能为空" }
        val parameters = listOf(
            "track_id" to trackId,
            "media_type" to "track",
            "aid" to "386088",
            "device_platform" to "web",
            "channel" to "pc_web",
        ).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        return "$ENDPOINT?$parameters"
    }

    fun fetch(trackId: String): QishuiLyricPayload = fetch(
        trackId = trackId,
        pcApiBody = { id ->
            requestText(
                url = buildRequestUrl(id),
                userAgent = PC_API_USER_AGENT,
                accept = "application/json,text/plain,*/*",
            )
        },
        sharePageBody = { id ->
            requestText(
                url = buildSharePageUrl(id),
                userAgent = SHARE_PAGE_USER_AGENT,
                accept = "text/html,application/xhtml+xml",
            )
        },
    )

    internal fun fetch(
        trackId: String,
        pcApiBody: (String) -> String,
        sharePageBody: (String) -> String,
    ): QishuiLyricPayload {
        val primary = runCatching {
            parseApiBody(trackId, pcApiBody(trackId))
        }
        if (primary.isSuccess) return primary.getOrThrow()

        val fallback = runCatching {
            parseSharePage(trackId, sharePageBody(trackId))
        }
        if (fallback.isSuccess) return fallback.getOrThrow()

        // 两条路径都是「服务端明确答复了没有歌词」：这是确定的结果，不是网络故障。
        if (primary.exceptionOrNull() is QishuiNoLyricsException &&
            fallback.exceptionOrNull() is QishuiNoLyricsException
        ) {
            throw QishuiNoLyricsException("汽水歌词 PC API 与官方分享页均答复没有歌词: trackId=$trackId")
        }
        val failure = IllegalStateException(
            "汽水歌词 PC API 与官方分享页均不可用: trackId=$trackId; " +
                "fallback=${fallback.exceptionOrNull()?.message}",
            fallback.exceptionOrNull(),
        )
        primary.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }

    internal fun parseApiBody(trackId: String, body: String): QishuiLyricPayload {
        check(body.isNotBlank()) { "汽水歌词 PC API 返回空响应体" }
        return parseResponse(trackId, JSONObject(body))
            ?: throw QishuiNoLyricsException("汽水歌词 PC API 响应不包含有效 lyric")
    }

    internal fun parseSharePage(trackId: String, html: String): QishuiLyricPayload {
        check(html.isNotBlank()) { "汽水歌词官方分享页返回空响应体" }
        val option = extractRouterData(html)
            ?.optJSONObject("loaderData")
            ?.optJSONObject("track_page")
            ?.optJSONObject("audioWithLyricsOption")
            ?: error("汽水歌词官方分享页缺少 track_page 数据")
        val pageTrackId = option.optString("track_id").trim()
        check(pageTrackId == trackId) {
            "汽水歌词官方分享页 track_id 不匹配: expected=$trackId actual=$pageTrackId"
        }
        val lyrics = option.optJSONObject("lyrics")
            ?: error("汽水歌词官方分享页缺少 lyrics 数据")
        val durationMs = sharePageDurationMs(option)
        val timeline = parseShareTimeline(lyrics.optJSONArray("sentences"), durationMs)
        if (timeline.isEmpty()) throw QishuiNoLyricsException("汽水歌词官方分享页不包含有效歌词")
        return QishuiLyricPayload(
            trackId = trackId,
            type = lyrics.optString("lyricType").ifBlank { "lrc" },
            content = "",
            translations = emptyList(),
            source = QishuiLyricSource.SHARE_PAGE,
            timeline = timeline,
        )
    }

    fun parseResponse(trackId: String, root: JSONObject): QishuiLyricPayload? {
        val lyric = root.optJSONObject("lyric") ?: return null
        val content = lyric.optString("content").trim()
        if (content.isBlank()) return null
        val type = lyric.optString("type").ifBlank { inferType(content) }
        val translations = buildList {
            appendTranslations(lyric.opt("translations"), this)
            appendTranslations(lyric.opt("lang_translations"), this)
        }
        return QishuiLyricPayload(
            trackId = trackId,
            type = type,
            content = content,
            translations = translations,
            source = QishuiLyricSource.PC_API,
        )
    }

    private fun buildSharePageUrl(trackId: String): String =
        "$SHARE_PAGE_ENDPOINT?track_id=" +
            URLEncoder.encode(trackId, StandardCharsets.UTF_8.name())

    private fun requestText(
        url: String,
        userAgent: String,
        accept: String,
    ): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.use(::readLimited).orEmpty()
            check(status in 200..299) {
                val summary = body.replace(Regex("\\s+"), " ").trim().take(160)
                "汽水歌词 HTTP $status, body=$summary"
            }
            check(body.isNotBlank()) {
                "汽水歌词 HTTP $status 返回空响应体: contentType=${connection.contentType}"
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val reader = stream.bufferedReader(StandardCharsets.UTF_8)
        val buffer = CharArray(8 * 1024)
        val result = StringBuilder()
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            result.append(buffer, 0, count)
            check(result.length <= MAX_RESPONSE_CHARS) {
                "汽水歌词响应超过 ${MAX_RESPONSE_CHARS} 个字符"
            }
        }
        return result.toString()
    }

    private fun extractRouterData(html: String): JSONObject? {
        var searchFrom = 0
        while (true) {
            val marker = html.indexOf(ROUTER_DATA_MARKER, searchFrom)
            if (marker < 0) return null
            var cursor = marker + ROUTER_DATA_MARKER.length
            while (cursor < html.length && html[cursor].isWhitespace()) cursor++
            if (cursor < html.length && html[cursor] == '=') {
                cursor++
                while (cursor < html.length && html[cursor].isWhitespace()) cursor++
                if (cursor < html.length && html[cursor] == '{') {
                    val end = findJsonObjectEnd(html, cursor)
                    if (end != null) {
                        runCatching {
                            JSONObject(html.substring(cursor, end))
                        }.getOrNull()?.let { return it }
                    }
                }
            }
            searchFrom = marker + ROUTER_DATA_MARKER.length
        }
    }

    private fun findJsonObjectEnd(value: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val character = value[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
    }

    private fun sharePageDurationMs(option: JSONObject): Long {
        val trackInfoDuration = option.optJSONObject("trackInfo")
            ?.optLong("duration", 0L)
            ?.takeIf { it > 0L }
        if (trackInfoDuration != null) return trackInfoDuration
        val durationSeconds = option.optDouble("duration", 0.0)
        return durationSeconds
            .takeIf { it.isFinite() && it > 0.0 }
            ?.times(1_000.0)
            ?.roundToLong()
            ?: 0L
    }

    private fun parseShareTimeline(
        sentences: JSONArray?,
        durationMs: Long,
    ): List<QishuiTimelineLine> = buildList {
        if (sentences == null) return@buildList
        repeat(sentences.length()) {
            val sentence = sentences.optJSONObject(it) ?: return@repeat
            val rawWords = sentence.optJSONArray("words")
            val words = buildList {
                if (rawWords == null) return@buildList
                repeat(rawWords.length()) {
                    val word = rawWords.optJSONObject(it) ?: return@repeat
                    val begin = word.optLong("startMs", -1L)
                    if (begin < 0L) return@repeat
                    val text = word.optString("text")
                    if (text.isEmpty()) return@repeat
                    val end = boundedEnd(
                        begin = begin,
                        rawEnd = word.optLong("endMs", begin),
                        durationMs = durationMs,
                    )
                    add(QishuiTimelineWord(begin, end, text))
                }
            }
            val begin = sentence.optLong("startMs", -1L)
            if (begin < 0L) return@repeat
            val text = sentence.optString("text").trim()
                .ifBlank { words.joinToString(separator = "") { word -> word.text }.trim() }
            if (text.isBlank()) return@repeat
            val end = maxOf(
                boundedEnd(
                    begin = begin,
                    rawEnd = sentence.optLong("endMs", begin),
                    durationMs = durationMs,
                ),
                words.maxOfOrNull(QishuiTimelineWord::end) ?: begin,
            )
            add(
                QishuiTimelineLine(
                    begin = begin,
                    end = end,
                    text = text,
                    translation = sentence.optString("translation")
                        .trim()
                        .takeIf(String::isNotBlank),
                    words = words,
                ),
            )
        }
    }.sortedBy(QishuiTimelineLine::begin)

    private fun boundedEnd(begin: Long, rawEnd: Long, durationMs: Long): Long {
        val end = rawEnd.takeIf { it > begin } ?: begin
        if (durationMs > begin) return minOf(end, durationMs)
        return if (end - begin > MAX_UNBOUNDED_TIMELINE_MS) begin else end
    }

    private fun appendTranslations(
        raw: Any?,
        destination: MutableList<QishuiTranslationPayload>,
    ) {
        when (raw) {
            is JSONObject -> raw.keys().forEach { language ->
                decodeTranslation(language, raw.opt(language))?.let(destination::add)
            }
            is JSONArray -> repeat(raw.length()) { index ->
                val value = raw.opt(index)
                val language = (value as? JSONObject)
                    ?.optString("lang")
                    ?.takeIf(String::isNotBlank)
                    ?: index.toString()
                decodeTranslation(language, value)?.let(destination::add)
            }
        }
    }

    private fun decodeTranslation(
        languageKey: String,
        value: Any?,
    ): QishuiTranslationPayload? {
        val language: String
        val type: String
        val content: String
        when (value) {
            is String -> {
                language = languageKey
                content = value.trim()
                type = inferType(content)
            }
            is JSONObject -> {
                language = value.optString("lang").ifBlank { languageKey }
                content = value.optString("content").trim()
                type = value.optString("type").ifBlank { inferType(content) }
            }
            else -> return null
        }
        if (content.isBlank()) return null
        return QishuiTranslationPayload(
            language = language,
            type = type,
            content = content,
        )
    }

    private fun inferType(content: String): String =
        if (Regex("""^\[\d+,\d+]""").containsMatchIn(content.trimStart())) "krc" else "lrc"
}

/** 服务端已正常答复、但答复里没有歌词（区别于网络失败、解析失败等无法确定的错误）。 */
internal class QishuiNoLyricsException(message: String) : IllegalStateException(message)
