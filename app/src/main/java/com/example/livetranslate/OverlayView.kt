package com.example.livetranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }

    private var overlays: List<TranslationViewModel.TextOverlay> = emptyList()
    private var imgWidth: Int = 1
    private var imgHeight: Int = 1

    fun update(
        newOverlays: List<TranslationViewModel.TextOverlay>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        overlays = newOverlays
        imgWidth = imageWidth
        imgHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (overlays.isEmpty() || imgWidth <= 1) return

        val scale = maxOf(width.toFloat() / imgWidth, height.toFloat() / imgHeight)
        val offsetX = (width - imgWidth * scale) / 2f
        val offsetY = (height - imgHeight * scale) / 2f

        overlays.forEach { overlay ->
            val l = overlay.left * scale + offsetX
            val t = overlay.top * scale + offsetY
            val r = overlay.right * scale + offsetX
            val b = overlay.bottom * scale + offsetY

            val boxW = r - l
            val boxH = b - t

            // 縦書き判定: 高さが幅の1.5倍超なら縦書き
            val isVertical = boxH > boxW * 1.5f

            // 縦書きの場合はキャンバスを-90°回転して横書きとして描画する
            // 回転後: 使える幅=boxH、使える高さ=boxW
            val availW = if (isVertical) boxH else boxW
            val availH = if (isVertical) boxW else boxH

            // テキストサイズを使える高さに合わせ、幅に収まるよう縮小
            textPaint.textSize = (availH * 0.72f).coerceIn(14f, 56f)
            val tw = textPaint.measureText(overlay.translated)
            if (tw > availW - 8f && tw > 0f) {
                textPaint.textSize *= (availW - 8f) / tw
            }

            val cx = (l + r) / 2f
            val cy = (t + b) / 2f

            canvas.save()
            if (isVertical) canvas.rotate(-90f, cx, cy)

            // 回転座標系でのボックス
            val rl = cx - availW / 2f
            val rt = cy - availH / 2f
            val rr = cx + availW / 2f
            val rb = cy + availH / 2f

            canvas.drawRect(rl, rt, rr, rb, bgPaint)
            canvas.drawText(overlay.translated, rl + 4f, rb - 6f, textPaint)

            canvas.restore()
        }
    }
}
