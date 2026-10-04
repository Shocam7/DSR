// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// CameraPipeline — wraps CameraX Preview use case with plugin-owned LifecycleOwner.
// Feeds frames to external OES texture surface owned by GlCompositor.

package com.dubsmash.dsr_engine.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.dubsmash.dsr_engine.EngineErrorCode
import com.dubsmash.dsr_engine.EngineEvent
import com.dubsmash.dsr_engine.EngineEventType
import com.dubsmash.dsr_engine.EngineFlutterApi
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera pipeline driven by CameraX and a dedicated [PluginLifecycleOwner].
 *
 * Invariants:
 *  - Frames are directly emitted to the external GL surface (zero copy).
 *  - Front/Back camera switching cleanly unbinds and rebinds with zero leaks.
 *  - Native exceptions are caught and forwarded as typed [EngineErrorCode]s.
 */
class CameraPipeline(
    private val context: Context,
    private val sessionId: Int,
    private val lifecycleOwner: PluginLifecycleOwner,
    private val flutterApi: EngineFlutterApi,
    initialIsFrontCamera: Boolean = true,
) {
    companion object {
        private const val TAG = "DsrCameraPipeline"
    }

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null

    private var targetSurface: Surface? = null
    private var isFrontCamera: Boolean = initialIsFrontCamera
    private val isRunning = AtomicBoolean(false)
    private val isDisposed = AtomicBoolean(false)

    var onUpdateBufferSize: ((Int, Int) -> Unit)? = null

    /**
     * Attaches the output surface (backed by OpenGL external OES texture).
     */
    fun setSurface(surface: Surface?, width: Int, height: Int) {
        targetSurface = surface
        if (isRunning.get() && surface != null) {
            bindCamera()
        }
    }

    /**
     * Starts the camera pipeline and binds to [lifecycleOwner].
     */
    fun start(onReady: (() -> Unit)? = null) {
        if (isDisposed.get()) return
        isRunning.set(true)
        lifecycleOwner.start()

        val existingProvider = cameraProvider
        if (existingProvider != null) {
            bindCamera()
            onReady?.invoke()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCamera()
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ProcessCameraProvider", e)
                emitError(EngineErrorCode.CAMERA_BUSY, "Failed to get CameraProvider: ${e.message}")
            }
        }, mainExecutor)
    }

    /**
     * Temporarily pauses the camera (e.g. app backgrounded or screen paused).
     */
    fun stop() {
        isRunning.set(false)
        lifecycleOwner.pause()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera on stop", e)
        }
    }

    /**
     * Toggles between front and back camera facing.
     */
    fun switchCamera(onComplete: ((Boolean) -> Unit)? = null) {
        if (isDisposed.get()) return
        val newFacing = !isFrontCamera
        isFrontCamera = newFacing

        if (isRunning.get()) {
            bindCamera()
        }
        onComplete?.invoke(isFrontCamera)
    }

    @Suppress("DEPRECATION")
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val surface = targetSurface
        if (surface == null || !surface.isValid) {
            Log.d(TAG, "Waiting for valid surface before binding CameraX")
            return
        }

        try {
            provider.unbindAll()

            val selector = if (isFrontCamera) {
                if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
            } else {
                if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
            }

            val previewUseCase = Preview.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
                .setTargetRotation(Surface.ROTATION_0)
                .build()

            previewUseCase.setSurfaceProvider(mainExecutor) { request: SurfaceRequest ->
                if (isDisposed.get() || !isRunning.get()) {
                    request.willNotProvideSurface()
                    return@setSurfaceProvider
                }
                val resolution = request.resolution
                Log.i(TAG, "CameraX surface request resolution: ${resolution.width}x${resolution.height}")
                onUpdateBufferSize?.invoke(resolution.width, resolution.height)

                val currentSurf = targetSurface
                if (currentSurf != null && currentSurf.isValid) {
                    request.provideSurface(currentSurf, mainExecutor) { result ->
                        Log.d(TAG, "CameraX surface result code: ${result.resultCode}")
                    }
                } else {
                    request.willNotProvideSurface()
                }
            }

            preview = previewUseCase
            camera = provider.bindToLifecycle(lifecycleOwner, selector, previewUseCase)
            Log.i(TAG, "Camera bound successfully (front=$isFrontCamera)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing", e)
            emitError(EngineErrorCode.PERMISSION, "Camera permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            emitError(EngineErrorCode.CAMERA_BUSY, "Camera bind failed: ${e.message}")
        }
    }

    private fun emitError(errorCode: EngineErrorCode, message: String) {
        val event = EngineEvent(
            sessionId = sessionId.toLong(),
            type = EngineEventType.ERROR,
            errorCode = errorCode,
            payload = """{"message":"$message"}"""
        )
        flutterApi.onEvent(event) {}
    }

    /**
     * Completely release all CameraX and Lifecycle resources.
     */
    fun dispose() {
        if (isDisposed.getAndSet(true)) return
        isRunning.set(false)
        lifecycleOwner.destroy()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding CameraX on dispose", e)
        }
        preview = null
        camera = null
        targetSurface = null
        cameraProvider = null
    }
}
