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
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Wraps Apple's rendered lyric card into a poster: Apple's own story background behind the card,
 * a soft shadow and a footer whose QR code points at the song. Every size derives from the card
 * width so the result keeps its proportions across Apple's render densities.
 *
 * The background is the image Apple renders for the Instagram/Facebook story share (the lyrics
 * background layer drawn from the artwork); when it is unavailable the poster falls back to a
 * flat colour taken from the card.
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
        return background to foregroundFor(background)
    }

    /** White or near-black, whichever has the higher WCAG contrast on [background]. */
    internal fun foregroundFor(background: Int): Int {
        val backgroundLuminance = relativeLuminance(background)
        val onWhite = 1.05 / (backgroundLuminance + 0.05)
        val onDark = (backgroundLuminance + 0.05) / (relativeLuminance(DARK_TEXT) + 0.05)
        return if (onWhite >= onDark) WHITE else DARK_TEXT
    }

    /** Source window, in source pixels, that fills a destination of the given size. */
    internal data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Centred "cover" crop: scales uniformly to fill the destination and trims the overflow. */
    internal fun coverCrop(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Crop {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return Crop(0, 0, srcWidth, srcHeight)
        }
        // Source is wider than the destination when srcW / srcH > dstW / dstH.
        return if (srcWidth.toLong() * dstHeight > dstWidth.toLong() * srcHeight) {
            val width = (srcHeight.toLong() * dstWidth / dstHeight).toInt().coerceIn(1, srcWidth)
            val left = (srcWidth - width) / 2
            Crop(left, 0, left + width, srcHeight)
        } else {
            val height = (srcWidth.toLong() * dstHeight / dstWidth).toInt().coerceIn(1, srcHeight)
            val top = (srcHeight - height) / 2
            Crop(0, top, srcWidth, top + height)
        }
    }

    /**
     * Writes the poster as JPEG straight into [output]. Opaque output lets JPEG replace the slow
     * lossless PNG pass; returns false when Apple's PNG cannot be decoded. [backdrop] is Apple's
     * story background image; null or undecodable falls back to a flat colour.
     */
    fun compose(
        source: File,
        backdrop: File?,
        songUrl: String?,
        title: String,
        subtitle: String,
        output: OutputStream,
    ): Boolean {
        val card = decode(source) ?: return false
        val qr = songUrl?.takeIf { it.isNotBlank() }?.let { runCatching { qrBitmap(it) }.getOrNull() }
        val backdropBitmap = backdrop?.let { runCatching { decode(it) }.getOrNull() }
        val spec = layout(card.width, card.height, withQr = qr != null)
        val poster = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        try {
            val (base, baseForeground) = palette(averageOpaqueColor(card))
            val canvas = Canvas(poster)
            // The flat colour is also the opaque base under the backdrop, so JPEG never sees alpha.
            canvas.drawColor(base)
            var foreground = baseForeground
            if (backdropBitmap != null) {
                drawBackdrop(canvas, backdropBitmap, spec)
                if (qr != null) {
                    foreground = foregroundFor(
                        averageColor(poster, spec.textLeft, spec.qrTop, spec.textRight, spec.qrTop + spec.qrTile),
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
                color = SHADOW_COLOR
                maskFilter = BlurMaskFilter(spec.shadowRadius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            }
            val shadowRect = RectF(cardRect).apply { offset(0f, spec.shadowRadius / 3f) }
            val cornerRadius = card.width * 0.035f
            canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)
            canvas.drawBitmap(card, spec.cardLeft.toFloat(), spec.cardTop.toFloat(), null)

            if (qr != null) drawFooter(canvas, spec, qr, foreground, title, subtitle)
            return poster.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        } finally {
            card.recycle()
            qr?.recycle()
            backdropBitmap?.recycle()
            poster.recycle()
        }
    }

    private fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
    )

    private fun drawBackdrop(canvas: Canvas, backdrop: Bitmap, spec: Layout) {
        val crop = coverCrop(backdrop.width, backdrop.height, spec.width, spec.height)
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

    /** WCAG relative luminance (linearised sRGB), 0 = black, 1 = white. */
    private fun relativeLuminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val value = channel(color, shift) / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private const val SAMPLE_SIZE = 12
    private const val JPEG_QUALITY = 95
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val DARK_TEXT = 0xFF1C1C1E.toInt()
    private const val SHADOW_COLOR = 0x55000000
}
