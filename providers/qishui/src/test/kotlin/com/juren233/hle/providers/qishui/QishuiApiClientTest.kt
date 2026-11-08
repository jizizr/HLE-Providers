/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QishuiApiClientTest {
    @Test
    fun `builds the verified PC track endpoint request`() {
        val url = QishuiApiClient.buildRequestUrl("7592139829673068587")

        assertTrue(url.startsWith("https://api.qishui.com/luna/pc/track_v2?"))
        assertTrue(url.contains("track_id=7592139829673068587"))
        assertTrue(url.contains("media_type=track"))
        assertTrue(url.contains("aid=386088"))
        assertTrue(url.contains("device_platform=web"))
        assertTrue(url.contains("channel=pc_web"))
    }

    @Test
    fun `parses PC translations without dropping their language key`() {
        val payload = requireNotNull(
            QishuiApiClient.parseResponse(
                "7592139829673068587",
                JSONObject(
                    """
                    {
                      "lyric": {
                        "type": "krc",
                        "content": "[22010,1000]<0,1000,0>Hello",
                        "translations": {
                          "cn": "[00:22.01]你好"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("krc", payload.type)
        assertEquals(1, payload.translations.size)
        assertEquals("cn", payload.translations.single().language)
        assertEquals("[00:22.01]你好", payload.translations.single().content)
    }

    @Test
    fun `parses Android lang translations with their own lyric type`() {
        val payload = requireNotNull(
            QishuiApiClient.parseResponse(
                "7234441209933596674",
                JSONObject(
                    """
                    {
                      "lyric": {
                        "type": "krc",
                        "content": "[22010,1000]<0,1000,0>Hello",
                        "lang_translations": {
                          "ZH-HANS-CN": {
                            "id": "7316704686453737473",
                            "lang": "ZH-HANS-CN",
                            "type": "lrc",
                            "content": "[00:22.01]你好"
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(1, payload.translations.size)
        assertEquals("ZH-HANS-CN", payload.translations.single().language)
        assertEquals("lrc", payload.translations.single().type)
        assertEquals("[00:22.01]你好", payload.translations.single().content)
    }

    @Test
    fun `extracts numeric track id from system media URI`() {
        assertEquals(
            "7592139829673068587",
            QishuiTrackIdentity.apiTrackId(
                "luna://track/7592139829673068587?scene=player",
            ),
        )
    }

    @Test
    fun `falls back to the official share page when the PC API body is empty`() {
        var sharePageRequested = false

        val payload = QishuiApiClient.fetch(
            trackId = "7356655103695652881",
            pcApiBody = { "" },
            sharePageBody = {
                sharePageRequested = true
                sharePageFixture(trackId = it)
            },
        )

        assertTrue(sharePageRequested)
        assertEquals(QishuiLyricSource.SHARE_PAGE, payload.source)
        assertEquals(2, payload.timeline.size)
        assertEquals("Oh, I leave", payload.timeline.first().text)
        assertEquals(listOf(390L, 700L), payload.timeline.first().words.map { it.begin })
        assertEquals(3_000L, payload.timeline.last().end)
    }

    @Test
    fun `keeps the PC API ahead of the share page fallback`() {
        var sharePageRequested = false

        val payload = QishuiApiClient.fetch(
            trackId = "7356655103695652881",
            pcApiBody = {
                """
                {
                  "lyric": {
                    "type": "lrc",
                    "content": "[00:01.00]primary"
                  }
                }
                """.trimIndent()
            },
            sharePageBody = {
                sharePageRequested = true
                sharePageFixture(trackId = it)
            },
        )

        assertFalse(sharePageRequested)
        assertEquals(QishuiLyricSource.PC_API, payload.source)
        assertEquals("[00:01.00]primary", payload.content)
    }

    @Test
    fun `rejects a share page whose embedded track id does not match`() {
        try {
            QishuiApiClient.parseSharePage(
                trackId = "7356655103695652881",
                html = sharePageFixture(trackId = "7592139829673068587"),
            )
            fail("Expected mismatched track id to be rejected")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("track_id 不匹配"))
        }
    }

    @Test
    fun `caps repeated retry delay instead of stopping self repair`() {
        assertEquals(15_000L, QishuiRetryPolicy.delayMs(0))
        assertEquals(30_000L, QishuiRetryPolicy.delayMs(1))
        assertEquals(300_000L, QishuiRetryPolicy.delayMs(20))
    }

    private fun sharePageFixture(trackId: String): String =
        """
        <!doctype html>
        <script>window.fake = "_ROUTER_DATA = not-json";</script>
        <script>
          _ROUTER_DATA = {
            "loaderData": {
              "track_page": {
                "audioWithLyricsOption": {
                  "track_id": "$trackId",
                  "duration": 3.0,
                  "trackInfo": {"duration": 3000},
                  "lyrics": {
                    "lyricType": "krc",
                    "sentences": [
                      {
                        "text": "Oh, I leave",
                        "startMs": 0,
                        "endMs": 1500,
                        "words": [
                          {"text": "Oh, ", "startMs": 390, "endMs": 700},
                          {"text": "I leave", "startMs": 700, "endMs": 1500}
                        ]
                      },
                      {
                        "text": "Contributor",
                        "startMs": 2500,
                        "endMs": 9007199254740991,
                        "words": []
                      }
                    ]
                  }
                }
              }
            }
          };
        </script>
        """.trimIndent()

    @Test
    fun `an unusable share page keeps a PC no lyrics answer from being definitive`() {
        val failure = runCatching {
            QishuiApiClient.fetch(
                trackId = "1",
                pcApiBody = { """{"lyric":{"content":""}}""" },
                sharePageBody = { error("share page must not be used when it cannot be parsed") },
            )
        }.exceptionOrNull()
        // The share page body is unusable here, so the result is not definitive.
        assertFalse(failure is QishuiNoLyricsException)
    }

    @Test
    fun `a PC answer without lyrics alone is classified as no lyrics`() {
        val failure = runCatching {
            QishuiApiClient.parseApiBody("1", """{"lyric":{"content":""}}""")
        }.exceptionOrNull()
        assertTrue(failure is QishuiNoLyricsException)
    }

    @Test
    fun `an empty response body stays an ordinary failure`() {
        val failure = runCatching { QishuiApiClient.parseApiBody("1", "") }.exceptionOrNull()
        assertFalse(failure is QishuiNoLyricsException)
    }

    @Test
    fun `reports lyrics unavailable after the first retry fails`() {
        assertEquals(2, QishuiRetryPolicy.REPORT_AFTER_FAILURES)
    }
}
