package com.danmo.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import org.tensorflow.lite.task.vision.detector.Detection

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {
    private var boxPaint: Paint? = null
    private var textPaint: Paint? = null
    private var detections: List<Detection>? = null
    private var rotationDegrees = 0

    init {
        initPaint()
    }

    private fun initPaint() {
        boxPaint = Paint()
        boxPaint!!.color = -0xff0100 // 绿色
        boxPaint!!.style = Paint.Style.STROKE
        boxPaint!!.strokeWidth = 5f

        textPaint = Paint()
        textPaint!!.color = -0x1 // 白色
        textPaint!!.textSize = 50f
        textPaint!!.isAntiAlias = true
    }

    fun updateDetections(detections: List<Detection>, rotationDegrees: Int) {
        this.detections = detections
        this.rotationDegrees = rotationDegrees
        invalidate() // 请求重绘
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections == null) return

        for (detection in detections!!) {
            val boundingBox = detection.boundingBox

            // 根据旋转角度调整检测框的位置
            if (rotationDegrees == 90) {
                val temp = boundingBox.left
                boundingBox.left = boundingBox.top
                boundingBox.top = width - boundingBox.right
                boundingBox.right = boundingBox.bottom
                boundingBox.bottom = width - temp
            } else if (rotationDegrees == 180) {
                boundingBox.left = width - boundingBox.right
                boundingBox.top = height - boundingBox.bottom
                boundingBox.right = width - boundingBox.left
                boundingBox.bottom = height - boundingBox.top
            } else if (rotationDegrees == 270) {
                val temp = boundingBox.left
                boundingBox.left = height - boundingBox.bottom
                boundingBox.top = boundingBox.right
                boundingBox.right = height - boundingBox.top
                boundingBox.bottom = boundingBox.right
            }

            // 绘制检测框
            canvas.drawRect(boundingBox, boxPaint!!)

            // 绘制标签
            val label = detection.categories[0].label
            canvas.drawText(label, boundingBox.left, boundingBox.top - 10, textPaint!!)
        }
    }
}