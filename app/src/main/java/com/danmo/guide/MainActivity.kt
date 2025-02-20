package com.danmo.guide

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.danmo.guide.databinding.ActivityMainBinding
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var overlayView: OverlayView  // 添加OverlayView引用
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null
    private lateinit var tts: TextToSpeech
    private var vibrator: Vibrator? = null
    private var lastSpeakTime = 0L
    private val speakCooldown = 3000L // 3 seconds cooldown
    private var isTtsReady = false

    // 修改权限提示的Toast
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else showToast("Camera permission required for guide feature")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        System.loadLibrary("tensorflowlite")
        System.loadLibrary("task_vision_jni")
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        cameraExecutor = Executors.newSingleThreadExecutor()
        overlayView = binding.overlayView  // 初始化OverlayView

        initializeDetector()
        initializeTts()
        checkCameraPermission()
    }

    private fun initializeDetector() {
        try {
            val modelFile = "efficientdet_lite0.tflite"
            val inputStream = assets.open(modelFile)
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
            if (objectDetector == null) {
                throw IllegalStateException("ObjectDetector 初始化失败")
            }
        } catch (e: Exception) {
            Log.e("ObjectDetector", "初始化失败: ${e.stackTraceToString()}")
            showToast("Object detection module initialization failed")
            finish()
        }
    }

    private fun initializeTts() {
        tts = TextToSpeech(this, this)
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> permissionsLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            preview.setSurfaceProvider(binding.previewView.surfaceProvider)

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeImage(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("Camera", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val bitmap = ImageProxyUtils.toBitmap(imageProxy) ?: return
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        val imageProcessor = ImageProcessor.Builder()
            .add(Rot90Op(-rotationDegrees / 90))
            .build()

        val processedImage = imageProcessor.process(tensorImage)
        val results = objectDetector?.detect(processedImage) ?: emptyList()

        handleDetectionResults(results)
        updateStatusUI(results)

        // 更新OverlayView的绘制内容
        updateOverlayView(results, imageProxy.imageInfo.rotationDegrees)

        imageProxy.close()
    }

    private fun updateOverlayView(results: List<Detection>, rotationDegrees: Int) {
        runOnUiThread {
            overlayView.updateDetections(results, rotationDegrees)  // 调用OverlayView的更新方法
        }
    }

    private fun handleDetectionResults(results: List<Detection>) {
        if (!isTtsReady) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpeakTime < speakCooldown) return

        results.maxByOrNull { it.categories[0].score }?.let { topResult ->
            when {
                isDangerousDistance(topResult) -> {
                    speakSafetyWarning(topResult)
                    vibrate(500)  // 危险情况长震动
                }
                else -> {
                    speakObjectNameWithDirection(topResult)
                    vibrate(200)  // 普通情况短震动
                }
            }
            lastSpeakTime = currentTime
        }
    }

    private fun vibrate(durationMs: Long) {
        if (vibrator?.hasVibrator() != true) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.e("Vibration", "Vibration failed", e)
        }
    }

    private fun isDangerousDistance(result: Detection): Boolean {
        val boxArea = result.boundingBox.width() * result.boundingBox.height()
        val screenArea = binding.previewView.width * binding.previewView.height
        return boxArea > 0.25f * screenArea
    }

    private fun speakSafetyWarning(result: Detection) {
        val label = result.categories[0].label
        val warning = when {
            label.contains("car", true) -> "Danger! Vehicle approaching ahead"
            label.contains("person", true) -> "Caution! Person approaching"
            label.contains("stair", true) -> "Warning! Stairs ahead"
            else -> "Attention! $label nearby"
        }
        speak(warning)
        showToast(warning)  // 显示 Toast 提示
    }

    private fun speakObjectNameWithDirection(result: Detection) {
        val label = result.categories[0].label
        val direction = getDirection(result.boundingBox)
        val message = "Detected $label $direction"
        speak(message)
        showToast(message)  // 显示 Toast 提示
    }

    private fun getDirection(boundingBox: RectF): String {
        val screenWidth = binding.previewView.width
        val screenHeight = binding.previewView.height
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2

        val boxCenterX = (boundingBox.left + boundingBox.right) / 2
        val boxCenterY = (boundingBox.top + boundingBox.bottom) / 2

        return when {
            boxCenterX < centerX / 2 -> "on the far left"
            boxCenterX > centerX * 3 / 2 -> "on the far right"
            boxCenterY < centerY / 2 -> "above"
            boxCenterY > centerY * 3 / 2 -> "below"
            boxCenterX < centerX -> "on the left"
            boxCenterX > centerX -> "on the right"
            else -> "in the center"
        }
    }

    private fun updateStatusUI(results: List<Detection>) {
        runOnUiThread {
            val status = if (results.isEmpty()) "No obstacles detected" else
                "Detected: ${results.joinToString { it.categories[0].label }}"
            binding.statusText.text = status
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            isTtsReady = when (result) {
                TextToSpeech.LANG_MISSING_DATA -> {
                    showToast("Missing English speech data")
                    false
                }
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    showToast("English language not supported")
                    false
                }
                else -> true
            }
        } else {
            showToast("TTS engine initialization failed")
        }
    }

    override fun onDestroy() {
        objectDetector?.close()
        tts.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}