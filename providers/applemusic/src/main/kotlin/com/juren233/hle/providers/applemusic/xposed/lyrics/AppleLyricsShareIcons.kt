/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * App-icon-sized tiles for the injected share destinations, matching the size Apple uses for
 * Instagram & co. so labels line up in the grid.
 */
internal object AppleLyricsShareIcons {
    enum class Glyph { SAVE, SHARE }

    @Volatile private var cache: Map<Glyph, Bitmap> = emptyMap()

    fun drawable(context: Context, glyph: Glyph): Drawable {
        val bitmap = cache[glyph] ?: render(iconSize(context), glyph).also { cache = cache + (glyph to it) }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun iconSize(context: Context): Int {
        val resources = context.resources
        val id = resources.getIdentifier("app_icon_size", "dimen", context.packageName)
        return if (id != 0) resources.getDimensionPixelSize(id)
        else (48 * resources.displayMetrics.density).roundToInt()
    }

    private fun render(size: Int, glyph: Glyph): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, s, TOP_COLOR, BOTTOM_COLOR, Shader.TileMode.CLAMP)
        }
        val radius = s * 0.225f
        canvas.drawRoundRect(RectF(0f, 0f, s, s), radius, radius, tile)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = s * 0.075f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun p(x: Float, y: Float) = floatArrayOf(x * s, y * s)
        fun path(vararg points: FloatArray) = Path().apply {
            moveTo(points[0][0], points[0][1])
            points.drop(1).forEach { lineTo(it[0], it[1]) }
        }
        when (glyph) {
            Glyph.SAVE -> {
                // Down arrow into an open tray.
                canvas.drawPath(path(p(0.5f, 0.24f), p(0.5f, 0.58f)), stroke)
                canvas.drawPath(path(p(0.36f, 0.45f), p(0.5f, 0.59f), p(0.64f, 0.45f)), stroke)
                canvas.drawPath(path(p(0.28f, 0.58f), p(0.28f, 0.74f), p(0.72f, 0.74f), p(0.72f, 0.58f)), stroke)
            }
            Glyph.SHARE -> {
                // Up arrow out of an open box (system share glyph).
                canvas.drawPath(path(p(0.5f, 0.6f), p(0.5f, 0.22f)), stroke)
                canvas.drawPath(path(p(0.37f, 0.34f), p(0.5f, 0.21f), p(0.63f, 0.34f)), stroke)
                canvas.drawPath(
                    path(p(0.39f, 0.45f), p(0.29f, 0.45f), p(0.29f, 0.77f), p(0.71f, 0.77f), p(0.71f, 0.45f), p(0.61f, 0.45f)),
                    stroke,
                )
            }
        }
        return bitmap
    }

    // Apple Music's brand red, as a soft vertical gradient.
    private const val TOP_COLOR = 0xFFFC5C6E.toInt()
    private const val BOTTOM_COLOR = 0xFFFA2D48.toInt()
}
