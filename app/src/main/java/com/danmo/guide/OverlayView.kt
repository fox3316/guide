package com.danmo.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.tensorflow.lite.task.vision.detector.Detection

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, android.R.color.holo_blue_bright)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private var detections: List<Detection> = emptyList()

    fun updateDetections(detections: List<Detection>) {
        this.detections = detections
        invalidate()  // 重绘视图
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (detection in detections) {
            val box = detection.boundingBox
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
        }
    }
}