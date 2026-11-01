/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicSongMidResolverTest {
    @Test
    fun `treats numeric media ids as numeric song ids`() {
        assertTrue(QQMusicSongMidResolver.isNumericSongId("97773"))
        assertTrue(QQMusicSongMidResolver.isNumericSongId("0"))
        assertTrue(QQMusicSongMidResolver.isNumericSongId("509076686"))
    }

    @Test
    fun `treats songmid shaped ids as non numeric`() {
        assertFalse(QQMusicSongMidResolver.isNumericSongId("0039MnYb0qxYhV"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("003Qui1q2u1Zho"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId(""))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("97773abc"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId(" 97773"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("-1"))
    }

    @Test
    fun `parses authoritative song name and singer from single song response`() {
        val raw = """
            {"code":0,"data":[{"id":268716958,"name":"Love Somebody",
              "singer":[{"name":"LAUV","mid":"002MDGgE0VbTcV"}],"album":{"id":123}}]}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSingleSongResponse(raw)!!
        assertEquals("268716958", resolution.numericSongId)
        assertEquals("Love Somebody", resolution.songName)
        assertEquals("LAUV", resolution.singerName)
    }

    @Test
    fun `gray songs return empty data and parse to null`() {
        // 无版权/灰色歌曲实测返回 code=0 且 data 为空数组
        val raw = """{"code":0,"data":[],"url":null,"url1":{},"extra_data":[]}"""
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse(raw))
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse("""{"code":0}"""))
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse("""{"code":2000}"""))
        // 裸端点返回的 HTML 页必须解析为 null 而不是抛异常
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse("<!DOCTYPE html><html></html>"))
    }

    @Test
    fun `numeric passthrough keeps song id without names`() {
        val resolution = QQMusicSongMidResolver.resolve("97773", null, null, trustedId = true)
        assertEquals("97773", resolution.numericSongId)
        assertNull(resolution.songName)
        assertNull(resolution.singerName)
    }

    @Test
    fun `numeric passthrough strips SDK flag bits from implausible ids`() {
        // qqmusicsdk SongInfomation.getId() 携带高位标志位：songId | 0x2000000000000000
        assertEquals(
            "107762076",
            QQMusicSongMidResolver.sanitizeNumericSongId("2305843009321456028"),
        )
        assertEquals(
            "107762076",
            QQMusicSongMidResolver.resolve("2305843009321456028", null, null, trustedId = true).numericSongId,
        )
        // 正常范围（≤32 位）的数字 ID 原样保留
        assertEquals("107762076", QQMusicSongMidResolver.sanitizeNumericSongId("107762076"))
        assertEquals("97773", QQMusicSongMidResolver.sanitizeNumericSongId("97773"))
        // 非数字输入原样返回，交给 songmid 换算路径
        assertEquals("0039MnYb0qxYhV", QQMusicSongMidResolver.sanitizeNumericSongId("0039MnYb0qxYhV"))
    }

    // musicu.fcg 网关真实响应样本（music.search.SearchCgiService / DoSearchForQQMusicDesktop）
    private val searchRaw = """
        {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
          {"id":390478855,"mid":"004c8nzy40CLsL","name":"菲律宾没有雪",
           "singer":[{"name":"一个小孩"}]},
          {"id":717479938,"mid":"004BO87l2BofMf","name":"菲律宾没有雪(我想要的)",
           "singer":[{"name":"一个小孩"},{"name":"听风叙晚"}]}
        ]}}}}}
    """.trimIndent()

    @Test
    fun `search fallback accepts candidate with exact title and singer`() {
        val resolution = QQMusicSongMidResolver.parseSearchResponse(
            searchRaw, "菲律宾没有雪", "一个小孩",
        )!!
        assertEquals("390478855", resolution.numericSongId)
        assertEquals("菲律宾没有雪", resolution.songName)
        assertEquals("一个小孩", resolution.singerName)
    }

    @Test
    fun `search fallback rejects mismatched singer or title`() {
        // 标题相等但歌手不相等：精确轮拒绝；包含轮可命中「菲律宾没有雪(我想要的)」
        // 合作版条目（同曲不同版本，歌词一致，听风叙晚在候选歌手列表内）
        val collab = QQMusicSongMidResolver.parseSearchResponse(searchRaw, "菲律宾没有雪", "听风叙晚")
        assertEquals("717479938", collab?.numericSongId)
        // 标题不相等（即使歌手相等）：拒绝
        assertNull(QQMusicSongMidResolver.parseSearchResponse(searchRaw, "不存在的歌曲名", "一个小孩"))
        // 缺少服务层键：拒绝
        assertNull(QQMusicSongMidResolver.parseSearchResponse("""{"code":0}""", "x", "y"))
        // 顶层 code 非 0：拒绝
        assertNull(QQMusicSongMidResolver.parseSearchResponse("""{"code":2000}""", "x", "y"))
        // 服务层 code 非 0：拒绝
        assertNull(
            QQMusicSongMidResolver.parseSearchResponse(
                """{"code":0,"music.search.SearchCgiService":{"code":2000,"data":{}}}""", "x", "y",
            ),
        )
        // 非 JSON 响应（如网关返回错误页）：拒绝且不抛异常
        assertNull(QQMusicSongMidResolver.parseSearchResponse("<html></html>", "x", "y"))
    }

    @Test
    fun `search matches multi artist slash separated metadata strings`() {
        // 元数据歌手是多人斜杠串（小米音乐车载场景实测形态），按段匹配
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":99,"mid":"x","name":"I Like Me Better","singer":[{"name":"Zach Herron"},{"name":"Lauv"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(
            raw, "I Like Me Better", "Zach Herron/Lauv",
        )!!
        assertEquals("99", resolution.numericSongId)
    }

    @Test
    fun `search fallback accepts exact title when singer list has extra members`() {
        // 候选歌手列表含额外成员（合作版），但标题精确相等且目标歌手在列表内：采纳
        val resolution = QQMusicSongMidResolver.parseSearchResponse(
            searchRaw, "菲律宾没有雪(我想要的)", "一个小孩",
        )!!
        assertEquals("717479938", resolution.numericSongId)
    }

    @Test
    fun `match normalization ignores case and whitespace`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":42,"mid":"m","name":"Love  Somebody","singer":[{"name":"lauv"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(raw, "love somebody", "LAUV")!!
        assertEquals("42", resolution.numericSongId)
    }

    @Test
    fun `search accepts truncated title via containment after exact pass misses`() {
        // 真机实测：App 标题 golden hour，QQ 真名 In your golden hour（精确轮不中，包含轮采纳）
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":236084449,"mid":"002jsOVo03DMQG","name":"In your golden hour","singer":[{"name":"JVKE"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(raw, "golden hour", "JVKE")!!
        assertEquals("236084449", resolution.numericSongId)
        assertEquals("In your golden hour", resolution.songName)
    }

    @Test
    fun `search prefers exact title over containment candidate listed earlier`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":1,"mid":"a","name":"golden hour (Live)","singer":[{"name":"JVKE"}]},
              {"id":2,"mid":"b","name":"golden hour","singer":[{"name":"JVKE"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(raw, "golden hour", "JVKE")!!
        assertEquals("2", resolution.numericSongId)
    }

    @Test
    fun `search containment requires at least four normalized characters`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":3,"mid":"c","name":"晴天笑了","singer":[{"name":"周杰伦"}]}
            ]}}}}}
        """.trimIndent()
        assertNull(QQMusicSongMidResolver.parseSearchResponse(raw, "晴天", "周杰伦"))
    }

    @Test
    fun `search throttle detector flags service level rate limit code`() {
        val raw = """
            {"code":0,"ts":1790509010743,"music.search.SearchCgiService":{"code":2001,"data":{
              "body":{"song":{"list":[]}},"code":0}}}
        """.trimIndent()
        assertTrue(QQMusicSongMidResolver.parseSearchThrottled(raw))
    }

    @Test
    fun `search throttle detector flags top level gateway errors`() {
        val raw = """{"code":500001,"ts":1790508681101,"traceid":"x"}"""
        assertTrue(QQMusicSongMidResolver.parseSearchThrottled(raw))
    }

    @Test
    fun `search throttle detector passes valid empty result through`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[]}},"code":0}}}
        """.trimIndent()
        assertTrue(!QQMusicSongMidResolver.parseSearchThrottled(raw))
    }

    @Test
    fun `search throttle detector treats malformed payload as retryable`() {
        assertTrue(QQMusicSongMidResolver.parseSearchThrottled("<html>gateway</html>"))
    }

    @Test
    fun `search candidates collect every match of the winning round in order`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":1,"mid":"a","name":"晴天","singer":[{"name":"周杰伦"}]},
              {"id":2,"mid":"b","name":"晴天","singer":[{"name":"周杰伦"}]}
            ]}}}}}
        """.trimIndent()
        val candidates = QQMusicSongMidResolver.parseSearchCandidates(raw, "晴天", "周杰伦")!!
        assertEquals(listOf("1", "2"), candidates.map { it.numericSongId })
        // 原单候选语义不变：取第一个
        assertEquals("1", QQMusicSongMidResolver.parseSearchResponse(raw, "晴天", "周杰伦")!!.numericSongId)
    }

    // ---- 数字 ID 校验（issue #62：QQ 手机版本地歌 MEDIA_ID 是本地库数字 ID）----

    private fun found(vararg ids: String) = QQMusicSongMidResolver.SearchOutcome.Found(
        ids.map { QQMusicSongMidResolver.SongMidResolution(it, "分手快乐", "梁静茹") },
    )

    @Test
    fun `trusted numeric id passes through without search`() {
        var searches = 0
        val resolution = QQMusicSongMidResolver.resolveNumericId("97773", null, null, true) { searches++; found("1") }
        assertEquals("97773", resolution.numericSongId)
        assertNull(resolution.songName)
        assertEquals(0, searches)
    }

    @Test
    fun `untrusted numeric id is confirmed when a candidate carries the same id`() {
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "4958007", "Without Me", "Eminem", false,
        ) { found("105589678", "4958007") }
        assertEquals("4958007", resolution.numericSongId)
        assertEquals("分手快乐", resolution.songName)
        assertEquals("梁静茹", resolution.singerName)
    }

    @Test
    fun `untrusted numeric id is replaced by searched id for local library ids`() {
        // issue #62：本地库数字 ID 与在线同曲候选 ID 不同 → 改用搜索结果，本地歌拿到正确在线词
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "267483012", "分手快乐", "梁静茹", false,
        ) { found("97773") }
        assertEquals("97773", resolution.numericSongId)
        assertEquals("分手快乐", resolution.songName)
    }

    @Test
    fun `untrusted numeric id without matching song throws and writes negative cache`() {
        var searches = 0
        val search = { searches++; QQMusicSongMidResolver.SearchOutcome.NotFound }
        assertThrows(IllegalStateException::class.java) {
            QQMusicSongMidResolver.resolveNumericId("987001", "冷门歌", "某人", false, search)
        }
        // 负缓存生效：第二次不再发起搜索，直接抛出
        assertThrows(IllegalStateException::class.java) {
            QQMusicSongMidResolver.resolveNumericId("987001", "冷门歌", "某人", false, search)
        }
        assertEquals(1, searches)
    }

    @Test
    fun `untrusted numeric id falls back to passthrough when search throttles`() {
        var searches = 0
        val search = { searches++; QQMusicSongMidResolver.SearchOutcome.Throttled }
        val first = QQMusicSongMidResolver.resolveNumericId("987002", "A Song", "B", false, search)
        assertEquals("987002", first.numericSongId)
        assertNull(first.songName)
        // 放行不写正缓存：下次仍会重试校验
        val second = QQMusicSongMidResolver.resolveNumericId("987002", "A Song", "B", false, search)
        assertEquals("987002", second.numericSongId)
        assertEquals(2, searches)
    }

    @Test
    fun `untrusted numeric id falls back to passthrough when search fails`() {
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "987003", "A Song", "B", false,
        ) { throw java.io.IOException("network down") }
        assertEquals("987003", resolution.numericSongId)
        assertNull(resolution.songName)
    }

    @Test
    fun `verified numeric resolution is cached per song identity`() {
        var searches = 0
        val search = {
            searches++
            QQMusicSongMidResolver.SearchOutcome.Found(
                listOf(QQMusicSongMidResolver.SongMidResolution("97773", "分手快乐", "梁静茹")),
            )
        }
        val first = QQMusicSongMidResolver.resolveNumericId("987004", "分手快乐", "梁静茹", false, search)
        val second = QQMusicSongMidResolver.resolveNumericId("987004", "分手快乐", "梁静茹", false, search)
        assertEquals(first, second)
        assertEquals(1, searches)
        // 本地库数字 ID 可能被复用为另一首歌：不同标题/歌手组合不命中旧换算缓存
        // （若误按裸 id 命中旧换算会直接返回 97773 而不抛异常）
        assertThrows(IllegalStateException::class.java) {
            QQMusicSongMidResolver.resolveNumericId("987004", "另一首", "别人", false) {
                QQMusicSongMidResolver.SearchOutcome.NotFound
            }
        }
    }

    @Test
    fun `untrusted numeric id strips flag bits before comparing with candidates`() {
        // SDK 长整型 ID：先掩码高位标志位再与候选比较（掩码后与候选同 ID 即确认在线）
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "2305843009321456028", "虚拟", "陈粒", false,
        ) { found("107762076") }
        assertEquals("107762076", resolution.numericSongId)
    }
}
