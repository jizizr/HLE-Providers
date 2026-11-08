/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.juren233.hle.providers.applemusic.xposed.ProviderLogger
import com.juren233.hyperlyricsenhanced.common.color.ColorExtractor
import com.juren233.hyperlyricsenhanced.contract.AppleLyricsSharePoster
import java.io.File
import java.io.FileDescriptor
import java.io.OutputStream

/**
 * Wraps Apple's rendered lyric card into a poster: a background behind the card, a soft shadow
 * and, optionally, a footer whose QR code points at the song. Geometry and colours come from
 * [AppleLyricsSharePoster], shared with the core's background preview.
 *
 * The background is one of [Background]: an image (Apple's own story background, i.e. the lyrics
 * background drawn from the artwork, or the user's prepared image) cover-cropped to the poster,
 * the artwork's darkened dominant colour, or a flat colour.
 */
internal object AppleLyricsShareCardComposer {
    sealed interface Background {
        /** Short name for diagnostics (class names are obfuscated in release Packs). */
        val label: String

        /** Cover-cropped image; [bitmap] is recycled by [compose]. */
        class Image(val bitmap: Bitmap, override val label: String) : Background

        /**
         * The artwork's dominant colour, darkened. Taken from the artwork thumbnail inside
         * Apple's card; [storyBackground] (Apple's blurred artwork) is used when the thumbnail
         * cannot be found.
         */
        class CoverColor(val storyBackground: File?) : Background {
            override val label = "cover_color"
        }

        /** A flat colour; alpha is ignored so JPEG never sees transparency. */
        data class Flat(val color: Int) : Background {
            override val label = "custom_color"
        }
    }

    fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
    )

    fun decode(descriptor: FileDescriptor): Bitmap? = BitmapFactory.decodeFileDescriptor(
        descriptor,
        null,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
    )

    /**
     * Writes the poster as JPEG straight into [output]. Opaque output lets JPEG replace the slow
     * lossless PNG pass; returns false when Apple's PNG cannot be decoded. The footer is drawn
     * only when [withQr] and a QR code can be built from [songUrl].
     */
    fun compose(
        source: File,
        background: Background,
        songUrl: String?,
        withQr: Boolean,
        title: String,
        subtitle: String,
        output: OutputStream,
    ): Boolean {
        val backdrop = (background as? Background.Image)?.bitmap
        val card = decode(source) ?: run {
            backdrop?.recycle()
            return false
        }
        val qr = songUrl?.takeIf { withQr && it.isNotBlank() }
            ?.let { runCatching { qrBitmap(it) }.getOrNull() }
        val spec = AppleLyricsSharePoster.layout(card.width, card.height, withQr = qr != null)
        val poster = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        try {
            // The flat colour is also the opaque base under an image, so JPEG never sees alpha.
            val base = when (background) {
                is Background.Flat -> background.color or OPAQUE
                is Background.CoverColor ->
                    AppleLyricsSharePoster.coverColorBackground(coverColor(card, background.storyBackground))
                is Background.Image -> OPAQUE
            }
            val canvas = Canvas(poster)
            canvas.drawColor(base)
            var foreground = AppleLyricsSharePoster.foregroundFor(base)
            if (backdrop != null) {
                drawBackdrop(canvas, backdrop, spec)
                if (qr != null) {
                    val area = AppleLyricsSharePoster.footerTextSampleArea(spec)
                    foreground = AppleLyricsSharePoster.foregroundFor(
                        averageColor(poster, area.left, area.top, area.right, area.bottom),
                    )
                }
            }

            val cardRect = RectF(
                spec.cardLeft.toFloat(),
                spec.cardTop.toFloat(),
                (spec.cardLeft + card.width).toFloat(),
                (spec.cardTop + card.height).toFloat(),
            )
            // Blur-only shadow (no solid fill), so Apple's transparent card corners stay clean
            // over any backdrop; the rounded rect approximates the card outline.
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AppleLyricsSharePoster.SHADOW_COLOR
                maskFilter = BlurMaskFilter(spec.shadowRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            val shadowRect = RectF(cardRect).apply { offset(0f, spec.shadowRadius / 3f) }
            canvas.drawRoundRect(shadowRect, spec.cardCornerRadius, spec.cardCornerRadius, shadowPaint)
            canvas.drawBitmap(card, spec.cardLeft.toFloat(), spec.cardTop.toFloat(), null)

            if (qr != null) drawFooter(canvas, spec, qr, foreground, title, subtitle)
            return poster.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        } finally {
            card.recycle()
            qr?.recycle()
            backdrop?.recycle()
            poster.recycle()
        }
    }

    private fun drawBackdrop(canvas: Canvas, backdrop: Bitmap, spec: AppleLyricsSharePoster.Layout) {
        val crop = AppleLyricsSharePoster.coverCrop(backdrop.width, backdrop.height, spec.width, spec.height)
        canvas.drawBitmap(
            backdrop,
            Rect(crop.left, crop.top, crop.right, crop.bottom),
            RectF(0f, 0f, spec.width.toFloat(), spec.height.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }

    /** Mean colour of an evenly spaced sample grid over the given area of an opaque bitmap. */
    private fun averageColor(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        val l = left.coerceIn(0, bitmap.width - 1)
        val t = top.coerceIn(0, bitmap.height - 1)
        val r = right.coerceIn(l + 1, bitmap.width)
        val b = bottom.coerceIn(t + 1, bitmap.height)
        var red = 0L
        var green = 0L
        var blue = 0L
        for (row in 0 until SAMPLE_SIZE) {
            val y = t + (b - t) * (2 * row + 1) / (2 * SAMPLE_SIZE)
            for (column in 0 until SAMPLE_SIZE) {
                val x = l + (r - l) * (2 * column + 1) / (2 * SAMPLE_SIZE)
                val pixel = bitmap.getPixel(x.coerceAtMost(bitmap.width - 1), y.coerceAtMost(bitmap.height - 1))
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
            }
        }
        val count = SAMPLE_SIZE * SAMPLE_SIZE
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun drawFooter(
        canvas: Canvas,
        spec: AppleLyricsSharePoster.Layout,
        qr: Bitmap,
        foreground: Int,
        title: String,
        subtitle: String,
    ) {
        val tile = RectF(
            spec.qrLeft.toFloat(),
            spec.qrTop.toFloat(),
            (spec.qrLeft + spec.qrTile).toFloat(),
            (spec.qrTop + spec.qrTile).toFloat(),
        )
        canvas.drawRoundRect(tile, spec.tileRadius, spec.tileRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
        val qrArea = RectF(
            tile.left + spec.qrInset,
            tile.top + spec.qrInset,
            tile.right - spec.qrInset,
            tile.bottom - spec.qrInset,
        )
        // Nearest-neighbour scaling keeps module edges crisp for scanners.
        canvas.drawBitmap(qr, null, qrArea, Paint().apply { isFilterBitmap = false })

        val available = (spec.textRight - spec.textLeft).toFloat().coerceAtLeast(1f)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = spec.titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            alpha = AppleLyricsSharePoster.SUBTITLE_ALPHA
            textSize = spec.subtitleSize
        }
        val titleText = TextUtils.ellipsize(title, titlePaint, available, TextUtils.TruncateAt.END)
        val subtitleText = TextUtils.ellipsize(subtitle, subtitlePaint, available, TextUtils.TruncateAt.END)
        val gap = spec.subtitleSize * 0.55f
        val titleHeight = titlePaint.fontMetrics.let { it.descent - it.ascent }
        val subtitleHeight = subtitlePaint.fontMetrics.let { it.descent - it.ascent }
        val blockTop = tile.centerY() - (titleHeight + gap + subtitleHeight) / 2f
        canvas.drawText(
            titleText, 0, titleText.length,
            spec.textLeft.toFloat(), blockTop - titlePaint.fontMetrics.ascent, titlePaint,
        )
        canvas.drawText(
            subtitleText, 0, subtitleText.length,
            spec.textLeft.toFloat(),
            blockTop + titleHeight + gap - subtitlePaint.fontMetrics.ascent,
            subtitlePaint,
        )
    }

    /** One pixel per module; the canvas scales it up. Avoids per-pixel setPixel calls. */
    private fun qrBitmap(content: String): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
            ),
        )
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Dominant artwork colour by the same extractor as the core's island and media-card cover
     * colours. Falls back to Apple's story background, then to the card's opaque average.
     */
    private fun coverColor(card: Bitmap, storyBackground: File?): Int {
        artworkDominantColor(card)?.let { return it }
        storyBackground?.let { file ->
            runCatching { decode(file) }.getOrNull()?.let { bitmap ->
                try {
                    dominantColor(bitmap)?.let {
                        ProviderLogger.diagnostic("Apple Music 歌词图片封面色: source=story_background")
                        return it
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        ProviderLogger.diagnostic("Apple Music 歌词图片封面色: source=card_average")
        return averageOpaqueColor(card)
    }

    private fun artworkDominantColor(card: Bitmap): Int? {
        val width = card.width
        val height = card.height
        val pixels = IntArray(width * height)
        card.getPixels(pixels, 0, width, 0, 0, width, height)
        val box = AppleLyricsShareArtworkLocator.locate(width, height) { x, y ->
            pixels[y * width + x] ushr 24
        } ?: return null
        val artwork = Bitmap.createBitmap(card, box.left, box.top, box.right - box.left, box.bottom - box.top)
        return try {
            dominantColor(artwork)?.also {
                ProviderLogger.diagnostic(
                    "Apple Music 歌词图片封面色: source=artwork box=${box.left},${box.top}," +
                        "${box.right},${box.bottom} card=${width}x$height",
                )
            }
        } finally {
            if (artwork !== card) artwork.recycle()
        }
    }

    private fun dominantColor(bitmap: Bitmap): Int? = runCatching {
        ColorExtractor.extractThemePalette(bitmap, 1).rawColors.firstOrNull()
    }.onFailure {
        ProviderLogger.error("Apple Music 歌词图片封面取色失败", it)
    }.getOrNull()

    private fun averageOpaqueColor(card: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(card, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (sample !== card) sample.recycle()
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 200) continue
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
            count++
        }
        if (count == 0) return Color.DKGRAY
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private const val SAMPLE_SIZE = AppleLyricsSharePoster.SAMPLE_GRID
    private const val JPEG_QUALITY = 95
    private const val OPAQUE = 0xFF000000.toInt()
}
