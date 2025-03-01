package com.danmo.guide.feature.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

class CameraManager(
    private val context: Context,
    private val executor: Executor,
    private val analyzer: ImageAnalysis.Analyzer
) {
    private var cameraControl: CameraControl? = null
    private var isTorchActive = false

    fun initializeCamera(surfaceProvider: Preview.SurfaceProvider) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                val camera = cameraProvider.bindToLifecycle(
                    context as LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                cameraControl = camera.cameraControl
            } catch (e: Exception) {
                Log.e("CameraManager", "相机初始化失败", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun enableTorchMode(
        enabled: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        if (isTorchActive == enabled) {
            onComplete?.invoke()
            return
        }

        try {
            cameraControl?.enableTorch(enabled)?.addListener({
                isTorchActive = enabled
                onComplete?.invoke()
            }, executor)
        } catch (e: CameraInfoUnavailableException) {
            Log.e("CameraManager", "闪光灯控制失败", e)
        }
    }

}