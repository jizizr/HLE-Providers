/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

/**
 * What the `i1.apply` result override does for one invocation. Kept free of Android types so
 * the rule runs in JVM unit tests.
 *
 * @property compose consume the freshly rendered files and produce the export (poster save or
 * share publish); false on re-delivery, so an export is never written twice.
 * @property swallowIntent answer with an empty holder instead of Apple's, keeping the progress
 * dialog's observer from launching its platform Intent (startActivityForResult) — that Intent is
 * Snapchat-shaped even for the injected export destinations, so phones with Snapchat installed
 * would otherwise see Snapchat launched after every export.
 */
internal data class AppleLyricsShareResultGate(val compose: Boolean, val swallowIntent: Boolean) {
    companion object {
        fun decide(sessionActive: Boolean, alreadySaved: Boolean, hasRenderedFile: Boolean) =
            when (sessionActive) {
                // No export in flight: Apple's own shares must keep their platform Intents.
                false -> AppleLyricsShareResultGate(compose = false, swallowIntent = false)
                // The platform Intent is swallowed for every call of the session, including
                // re-deliveries after a successful export and renders that produced no file.
                true -> AppleLyricsShareResultGate(
                    compose = hasRenderedFile && !alreadySaved,
                    swallowIntent = true,
                )
            }
    }
}
