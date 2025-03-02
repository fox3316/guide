package com.danmo.guide.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.tensorflow.lite.task.vision.detector.Detection
import android.util.Log // 导入 Log 类

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {
    private var boxPaint: Paint? = null
    private var textPaint: Paint? = null
    private var detections: List<Detection>? = null
    private var rotationDegrees = 0
    private var width = 0
    private var height = 0

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        this.width = w
        this.height = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections == null) return

        for (detection in detections!!) {
            val boundingBox = detection.boundingBox

            // 调整检测框位置
            adjustBoundingBoxForRotation(boundingBox)

            // 获取屏幕尺寸
            val (deviceWidth, deviceHeight) = getDeviceDimensions()

            // 计算并应用缩放比例
            applyScalingToBoundingBox(boundingBox, deviceWidth, deviceHeight)

            // 绘制检测框和标签
            drawDetection(canvas, boundingBox, detection)
        }
    }

    /**
     * 根据旋转角度调整检测框的位置
     */
    private fun adjustBoundingBoxForRotation(boundingBox: RectF) {
        when (rotationDegrees) {
            90 -> {
                val temp = boundingBox.left
                boundingBox.left = boundingBox.top
                boundingBox.top = height - boundingBox.right
                boundingBox.right = boundingBox.bottom
                boundingBox.bottom = height - temp
            }
            180 -> {
                boundingBox.left = width - boundingBox.right
                boundingBox.top = height - boundingBox.bottom
                boundingBox.right = width - boundingBox.left
                boundingBox.bottom = height - boundingBox.top
            }
            270 -> {
                val temp = boundingBox.left
                boundingBox.left = width - boundingBox.bottom
                boundingBox.top = boundingBox.right
                boundingBox.right = width - boundingBox.top
                boundingBox.bottom = temp
            }
        }
    }

    /**
     * 获取设备屏幕的宽度和高度
     */
    private fun getDeviceDimensions(): Pair<Float, Float> {
        val displayMetrics = context.resources.displayMetrics
        return Pair(displayMetrics.widthPixels.toFloat(), displayMetrics.heightPixels.toFloat())
    }

    /**
     * 计算并应用缩放比例到检测框
     */
    private fun applyScalingToBoundingBox(boundingBox: RectF, deviceWidth: Float, deviceHeight: Float) {
        val scaleX = deviceWidth / width
        val scaleY = deviceHeight / height

        boundingBox.left *= scaleX
        boundingBox.top *= scaleY
        boundingBox.right *= scaleX
        boundingBox.bottom *= scaleY

        // 输出调试信息
        Log.d("OverlayView", "Device Width: $deviceWidth, Device Height: $deviceHeight")
        Log.d("OverlayView", "View Width: $width, View Height: $height")
        Log.d("OverlayView", "Scale X: $scaleX, Scale Y: $scaleY")
        Log.d(
            "OverlayView",
            "Bounding Box: left=${boundingBox.left}, top=${boundingBox.top}, right=${boundingBox.right}, bottom=${boundingBox.bottom}"
        )
    }

    /**
     * 绘制检测框和标签
     */
    private fun drawDetection(canvas: Canvas, boundingBox: RectF, detection: Detection) {
        // 绘制检测框
        canvas.drawRect(boundingBox, boxPaint!!)

        // 绘制标签
        val label = detection.categories[0].label
        canvas.drawText(label, boundingBox.left, boundingBox.top - 10, textPaint!!)
    }
}