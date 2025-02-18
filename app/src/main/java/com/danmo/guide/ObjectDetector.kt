package com.danmo.guide

import android.content.Context
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.vision.detector.ObjectDetector.ObjectDetectorOptions
import java.io.FileInputStream

class ObjectDetector(context: Context, modelPath: String = "ssd_mobilenet_v1.tflite") {
    private var detector: ObjectDetector
    private var labels: List<String> = listOf()

    init {
        // 加载标签
        context.assets.open("labels.txt").use { stream ->
            labels = stream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.toList()
            }
        }

        // 配置检测器选项
        val options = ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()

        // 加载模型
        try {
            val modelFile = context.assets.openFd(modelPath).use { fd ->
                // 正确获取文件通道
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel
                }
            }

            detector = ObjectDetector.createFromFileAndOptions(
                context,
                modelFile.toString(),
                options
            )
        } catch (e: Exception) {
            Log.e("ObjectDetector", "初始化失败: ${e.message}")
            throw e
        }
    }

    fun detect(image: TensorImage): List<DetectionResult> {
        return try {
            detector.detect(image).map { detection ->
                DetectionResult(
                    label = labels.getOrElse(detection.categories.first().index) { "未知物体" },
                    confidence = detection.categories.first().score,
                    location = detection.boundingBox
                )
            }
        } catch (e: Exception) {
            Log.e("ObjectDetector", "检测失败: ${e.message}")
            emptyList()
        }
    }

    data class DetectionResult(
        val label: String,
        val confidence: Float,
        val location: RectF
    )
}