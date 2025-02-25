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

    fun initializeCamera(surfaceProvider: Preview.SurfaceProvider) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

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
                Log.e("CameraManager", "Failed to initialize camera", e)
                // 提供错误回调或显示错误信息
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // 新增闪光灯控制方法
    fun enableTorchMode(
        enabled: Boolean,
        onSuccess: (() -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        if (cameraControl == null) {
            Log.e("CameraManager", "CameraControl is not initialized. Cannot enable torch.")
            onError?.invoke(IllegalStateException("CameraControl is not initialized."))
            return
        }

        try {
            cameraControl?.enableTorch(enabled)?.addListener({
                onSuccess?.invoke()
            }, executor)
        } catch (e: CameraInfoUnavailableException) {
            onError?.invoke(e)
            Log.e("CameraManager", "Flash control failed", e)
        } catch (e: Exception) {
            onError?.invoke(e)
            Log.e("CameraManager", "Unexpected error while enabling torch", e)
        }
    }
}