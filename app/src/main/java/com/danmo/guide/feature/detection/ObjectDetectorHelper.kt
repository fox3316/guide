package com.danmo.guide.feature.detection

import android.content.Context
import android.util.Log
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ObjectDetectorHelper(context: Context) {
    private var objectDetector: ObjectDetector? = null
    private val assetManager = context.assets

    init {
        initializeDetector()
    }

    private fun initializeDetector() {
        try {
            val modelFile = "efficientdet_lite0.tflite"
            val inputStream = assetManager.open(modelFile)
            val modelBuffer = inputStream.readBytes()
            val byteBuffer = ByteBuffer.allocateDirect(modelBuffer.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelBuffer)
                rewind()
            }
            inputStream.close()

            val baseOptions = BaseOptions.builder()
                .setNumThreads(4)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(5)
                .setScoreThreshold(0.5f)
                .build()

            objectDetector = ObjectDetector.createFromBufferAndOptions(byteBuffer, options)
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Initialization failed: ${e.stackTraceToString()}")
        }
    }

    fun detect(image: TensorImage, rotationDegrees: Int): List<Detection> {
        val imageProcessor = ImageProcessor.Builder()
            .add(Rot90Op(-rotationDegrees / 90))
            .build()

        val processedImage = imageProcessor.process(image)
        return objectDetector?.detect(processedImage) ?: emptyList()
    }

    fun close() {
        objectDetector?.close()
    }
}