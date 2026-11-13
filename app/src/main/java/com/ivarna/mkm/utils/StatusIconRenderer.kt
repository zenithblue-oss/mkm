package com.ivarna.mkm.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders one or two short values (e.g. "36", "5.2") as a white-on-transparent status bar icon.
 * Two values are stacked so a single notification icon can show two metrics.
 */
object StatusIconRenderer {
    private const val SIZE = 96

    fun render(text: String): Bitmap = render(listOf(text))

    fun render(lines: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val rows = lines.take(2).ifEmpty { listOf("") }
        val rowHeight = SIZE / rows.size.toFloat()
        val canvas = Canvas(bitmap)
        rows.forEachIndexed { i, text ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = rowHeight * 0.8f
            }
            // Shrink to fit the icon width
            val width = paint.measureText(text)
            if (width > SIZE) paint.textSize *= SIZE / width
            val y = rowHeight * i + rowHeight / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(text, SIZE / 2f, y, paint)
        }
        return bitmap
    }
}
