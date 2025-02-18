package com.danmo.guide

import android.content.Context
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.IOException
import java.nio.ByteBuffer

class TFLiteObjectDetector(context: Context) {
    private val detector: ObjectDetector

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setNumThreads(4)
                .build()

            val modelBuffer = context.assets.open("ssd_mobilenet_v1.tflite").use { stream ->
                ByteBuffer.allocateDirect(stream.available()).apply {
                    stream.read(array())
                    rewind()
                }
            }

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(5)
                .setScoreThreshold(0.5f)
                .build()

            detector = ObjectDetector.createFromBufferAndOptions(modelBuffer, options)
        } catch (e: IOException) {
            throw RuntimeException("模型加载失败: ${e.message}")
        }
    }

    fun detect(image: TensorImage): List<DetectionResult> {
        return detector.detect(image).map {
            DetectionResult(
                it.categories.first().label,
                it.boundingBox,
                it.categories.first().score
            )
        }
    }

    fun close() {
        detector.close()
    }

    data class DetectionResult(
        val label: String,
        val location: RectF,
        val confidence: Float
    )
}