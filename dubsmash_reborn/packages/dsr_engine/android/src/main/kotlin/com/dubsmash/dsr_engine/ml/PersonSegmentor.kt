// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// PersonSegmentor — MediaPipe Tasks ImageSegmenter on a dedicated "dsr-ml" thread.
// The segmenter is created AND used on the ML thread (required by the GPU delegate).
// The GL thread hands frames in via submitFrame() and reads the newest mask via
// getMask() from an AtomicReference (lock-free).
// Requires: implementation 'com.google.mediapipe:tasks-vision:0.10.14'
package com.dubsmash.dsr_engine.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class MaskResult(
    val maskPixels: FloatArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
)

class PersonSegmentor(
    private val context: Context,
    private val modelBuffer: ByteBuffer,
    val delegateType: DelegateType,
) {
    private val mlThread = HandlerThread("dsr-ml").apply { start() }
    private val handler = Handler(mlThread.looper)

    private val latestMask = AtomicReference<MaskResult?>()
    private val isProcessing = AtomicBoolean(false)

    /** Duration of the most recent inference in ms (0 until the first one finishes). */
    @Volatile
    var lastInferenceMs: Double = 0.0
        private set

    private var segmenter: ImageSegmenter? = null
    private var bitmap: Bitmap? = null

    companion object {
        private const val TAG = "PersonSegmentor"
    }

    /**
     * Creates the segmenter on the ML thread and runs a warm-up inference.
     * Returns the warm inference latency in ms, or throws if creation/inference fails.
     * Must NOT be called from the main or GL thread (blocks up to 20 s).
     */
    fun initialize(): Double {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        var latency = 0.0
        handler.post {
            try {
                val modelFile = java.io.File(context.cacheDir, "selfie_segmentation.tflite")
                val resolved = ModelLoader.resolveAssetPath(context, "selfie_segmentation.tflite")
                val assetFd = try { context.assets.openFd(resolved) } catch (e: Exception) { null }
                val needsCopy = !modelFile.exists() || (assetFd != null && modelFile.length() != assetFd.length)
                assetFd?.close()
                if (needsCopy) {
                    context.assets.open(resolved).use { input ->
                        java.io.FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                val base = BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(if (delegateType == DelegateType.GPU) Delegate.GPU else Delegate.CPU)
                    .build()
                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build()
                val seg = ImageSegmenter.createFromOptions(context, options)
                segmenter = seg

                // Run warm-up inferences with un-recycled bitmaps
                val t0 = System.nanoTime()
                val runs = 5
                repeat(runs) {
                    val warm = Bitmap.createBitmap(144, 256, Bitmap.Config.ARGB_8888)
                    val img = BitmapImageBuilder(warm).build()
                    val res = seg.segment(img)
                    img.close()
                }
                latency = (System.nanoTime() - t0) / 1_000_000.0 / runs
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw IllegalStateException("Segmenter init timed out ($delegateType)")
        }
        error?.let { throw it }
        return latency
    }

    /**
     * Submits an RGBA, top-row-first frame. Drops the frame if the previous
     * inference is still running (backpressure).
     */
    fun submitFrame(rgba: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (!isProcessing.compareAndSet(false, true)) return

        handler.post {
            try {
                val seg = segmenter ?: return@post
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))

                val t0 = System.nanoTime()
                val mpImage = BitmapImageBuilder(bmp).build()
                val result = seg.segment(mpImage)
                mpImage.close()

                val confMasksOpt = result.confidenceMasks()
                if (confMasksOpt.isPresent) {
                    val masks = confMasksOpt.get()
                    if (masks.isNotEmpty()) {
                        // For selfie_segmenter, masks[0] (or masks.last()) is person confidence
                        val mask = masks[masks.size - 1]
                        val buf = ByteBufferExtractor.extract(mask).asFloatBuffer()
                        val w = mask.width
                        val h = mask.height
                        val pixels = FloatArray(w * h)
                        buf.rewind()
                        val count = minOf(buf.remaining(), pixels.size)
                        buf.get(pixels, 0, count)
                        lastInferenceMs = (System.nanoTime() - t0) / 1_000_000.0
                        latestMask.set(MaskResult(pixels, w, h, timestampNs))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Inference failed", t)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    fun getMask(): MaskResult? = latestMask.get()

    /** True while an inference is running; the GL thread skips readback then. */
    fun isBusy(): Boolean = isProcessing.get()

    fun dispose() {
        handler.post {
            try {
                segmenter?.close()
            } catch (t: Throwable) {
                Log.e(TAG, "Error closing segmenter", t)
            }
            segmenter = null
            bitmap?.recycle()
            bitmap = null
            mlThread.quitSafely()
        }
    }
}
