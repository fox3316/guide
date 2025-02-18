package com.danmo.guide

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null
    private lateinit var tts: TextToSpeech

    private var lastSpeakTime = 0L
    private val speakCooldown = 3000L // 3 seconds cooldown
    private var isTtsReady = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else showToast("需要相机权限才能使用导盲功能")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        System.loadLibrary("tensorflowlite")
        System.loadLibrary("task_vision_jni")
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        initializeDetector()
        initializeTts()
        checkCameraPermission()
    }

    private fun initializeDetector() {
        try {
            val modelFile = "ssd_mobilenet_v1.tflite"
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
            showToast("物体检测模块初始化失败")
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

        imageProxy.close()
    }

    private fun handleDetectionResults(results: List<Detection>) {
        if (!isTtsReady) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpeakTime < speakCooldown) return

        results.maxByOrNull { it.categories[0].score }?.let { topResult ->
            when {
                isDangerousDistance(topResult) -> speakSafetyWarning(topResult)
                else -> speakObjectName(topResult)
            }
            lastSpeakTime = currentTime
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
            label.contains("car", true) -> "危险！前方有车辆靠近"
            label.contains("person", true) -> "注意！前方有人靠近"
            label.contains("stair", true) -> "警告！前方有台阶"
            else -> "注意！$label 接近"
        }
        speak(warning)
    }

    private fun speakObjectName(result: Detection) {
        speak("检测到${result.categories[0].label}")
    }

    private fun updateStatusUI(results: List<Detection>) {
        runOnUiThread {
            val status = if (results.isEmpty()) "未检测到障碍物" else
                "检测到：${results.joinToString { it.categories[0].label }}"
            binding.statusText.text = status
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.CHINESE)
            isTtsReady = when (result) {
                TextToSpeech.LANG_MISSING_DATA -> {
                    showToast("缺少中文语音数据")
                    false
                }
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    showToast("不支持中文语音")
                    false
                }
                else -> true
            }
        } else {
            showToast("语音引擎初始化失败")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        objectDetector?.close()
        tts.shutdown()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}