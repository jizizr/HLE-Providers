/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Wraps Apple's rendered lyric card into a poster: tinted margin, soft shadow and a footer
 * whose QR code points at the song. Every size derives from the card width so the result keeps
 * its proportions across Apple's render densities.
 */
internal object AppleLyricsShareCardComposer {
    /** Pure layout in pixels; kept separate from drawing so proportions stay unit-testable. */
    internal data class Layout(
        val width: Int,
        val height: Int,
        val padding: Int,
        val cardLeft: Int,
        val cardTop: Int,
        val footerTop: Int,
        val qrTile: Int,
        val qrInset: Int,
        val qrLeft: Int,
        val qrTop: Int,
        val textLeft: Int,
        val textRight: Int,
        val titleSize: Float,
        val subtitleSize: Float,
        val tileRadius: Float,
        val shadowRadius: Float,
    )

    internal fun layout(cardWidth: Int, cardHeight: Int, withQr: Boolean): Layout {
        val unit = cardWidth / 100f
        val padding = (unit * 6f).roundToInt().coerceAtLeast(16)
        val qrTile = if (withQr) (unit * 22f).roundToInt() else 0
        val qrInset = (unit * 1.6f).roundToInt().coerceAtLeast(6)
        val footerTop = padding + cardHeight + if (withQr) (unit * 5f).roundToInt() else 0
        val height = if (withQr) footerTop + qrTile + padding else footerTop + padding
        val width = cardWidth + padding * 2
        val qrLeft = width - padding - qrTile
        return Layout(
            width = width,
            height = height,
            padding = padding,
            cardLeft = padding,
            cardTop = padding,
            footerTop = footerTop,
            qrTile = qrTile,
            qrInset = qrInset,
            qrLeft = qrLeft,
            qrTop = footerTop,
            textLeft = padding + (unit * 1.5f).roundToInt(),
            textRight = qrLeft - (unit * 5f).roundToInt(),
            titleSize = unit * 4.6f,
            subtitleSize = unit * 3.3f,
            tileRadius = unit * 3f,
            shadowRadius = unit * 3.5f,
        )
    }

    /** Light card colours get a deeper backdrop; returns (background, foreground). */
    internal fun palette(sampled: Int): Pair<Int, Int> {
        val background = blend(sampled, BLACK, 0.42f)
        val foreground = if (luminance(background) > 0.6f) rgb(28, 28, 30) else WHITE
        return background to foreground
    }

    /**
     * Writes the poster as JPEG straight into [output]. Opaque output lets JPEG replace the slow
     * lossless PNG pass; returns false when Apple's PNG cannot be decoded.
     */
    fun compose(
        source: File,
        songUrl: String?,
        title: String,
        subtitle: String,
        output: OutputStream,
    ): Boolean {
        val card = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return false
        val qr = songUrl?.takeIf { it.isNotBlank() }?.let { runCatching { qrBitmap(it) }.getOrNull() }
        val spec = layout(card.width, card.height, withQr = qr != null)
        val poster = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        try {
            val (background, foreground) = palette(averageOpaqueColor(card))
            val canvas = Canvas(poster)
            canvas.drawColor(background)

            val cardRect = RectF(
                spec.cardLeft.toFloat(),
                spec.cardTop.toFloat(),
                (spec.cardLeft + card.width).toFloat(),
                (spec.cardTop + card.height).toFloat(),
            )
            // Apple's card has transparent rounded corners: an inset rounded shadow matches it.
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = background
                setShadowLayer(spec.shadowRadius, 0f, spec.shadowRadius / 3f, 0x55000000)
            }
            val cornerRadius = card.width * 0.035f
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, shadowPaint)
            canvas.drawBitmap(card, spec.cardLeft.toFloat(), spec.cardTop.toFloat(), null)

            if (qr != null) drawFooter(canvas, spec, qr, foreground, title, subtitle)
            return poster.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        } finally {
            card.recycle()
            qr?.recycle()
            poster.recycle()
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        spec: Layout,
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
            alpha = 170
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

    // Plain bit math (no android.graphics.Color) so palette() runs in JVM unit tests.
    private fun channel(color: Int, shift: Int) = (color shr shift) and 0xFF

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val keep = 1f - ratio
        return rgb(
            (channel(from, 16) * keep + channel(to, 16) * ratio).roundToInt(),
            (channel(from, 8) * keep + channel(to, 8) * ratio).roundToInt(),
            (channel(from, 0) * keep + channel(to, 0) * ratio).roundToInt(),
        )
    }

    internal fun luminance(color: Int): Float =
        (0.2126f * channel(color, 16) + 0.7152f * channel(color, 8) + 0.0722f * channel(color, 0)) / 255f

    private const val SAMPLE_SIZE = 12
    private const val JPEG_QUALITY = 95
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
