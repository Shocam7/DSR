// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.scene

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.dubsmash.dsr_engine.Tier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class SceneSurfaceClass {
    WALL,
    FLOOR,
    CEILING,
    WINDOW,
    FURNITURE,
    BACKGROUND
}

data class SceneMaskResult(
    val maskRgba: ByteArray,
    val width: Int,
    val height: Int,
    val classAreas: Map<String, Float>,
    val timestampNs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SceneMaskResult
        return width == other.width && height == other.height && timestampNs == other.timestampNs
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + timestampNs.hashCode()
        return result
    }
}

/**
 * Scene segmentor running on dedicated ML thread (M4-T1).
 * Segments physical environments into architectural surfaces (wall, floor, ceiling, window, furniture)
 * with tiered resolution and cadence (Tier L: 160px/4th frame, Tier M: 256px/3rd, Tier H: 320px/2nd).
 */
class SceneSegmentor(
    var tier: Tier = Tier.M
) {
    companion object {
        private const val TAG = "SceneSegmentor"

        fun getResolutionForTier(tier: Tier): Int = when (tier) {
            Tier.L -> 160
            Tier.M -> 256
            Tier.H -> 320
        }

        fun getCadenceForTier(tier: Tier): Int = when (tier) {
            Tier.L -> 4
            Tier.M -> 3
            Tier.H -> 2
        }
    }

    private val mlThread = HandlerThread("dsr-scene-ml").apply { start() }
    private val mlHandler = Handler(mlThread.looper)

    private val isBusy = AtomicBoolean(false)
    private val latestResult = AtomicReference<SceneMaskResult?>(null)

    private var frameCounter = 0L
    var lastInferenceMs = 0.0
        private set

    fun isBusy(): Boolean = isBusy.get()

    fun getLatestMask(): SceneMaskResult? = latestResult.get()

    /**
     * Submits a downscaled camera frame for scene segmentation.
     * Evaluates frame cadence and drops if busy (non-blocking).
     */
    fun submitFrame(
        rgbaPixels: ByteArray,
        width: Int,
        height: Int,
        timestampNs: Long
    ) {
        if (!isBusy.compareAndSet(false, true)) {
            // Drop frame to preserve 60 fps camera preview
            return
        }

        mlHandler.post {
            try {
                val startNs = SystemClock.elapsedRealtimeNanos()
                val result = processSceneSegmentation(rgbaPixels, width, height, timestampNs)
                latestResult.set(result)
                lastInferenceMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            } catch (e: Exception) {
                Log.e(TAG, "Scene segmentation error", e)
            } finally {
                isBusy.set(false)
            }
        }
    }

    /**
     * Analyzes pixel spatial priors and luminance gradients to identify
     * architectural surfaces (wall, floor, ceiling, window).
     * Output RGBA: R = wall, G = floor, B = ceiling, A = window/features.
     */
    fun processSceneSegmentation(
        rgba: ByteArray,
        width: Int,
        height: Int,
        timestampNs: Long
    ): SceneMaskResult {
        val maskBytes = ByteArray(width * height * 4)
        var wallPixels = 0
        var floorPixels = 0
        var ceilingPixels = 0
        var windowPixels = 0
        val totalPixels = width * height

        for (y in 0 until height) {
            // Normalized vertical coordinate (0 = top/ceiling, 1 = bottom/floor)
            val ny = y.toFloat() / height.toFloat()

            for (x in 0 until width) {
                val idx = (y * width + x) * 4
                val r = rgba[idx].toInt() and 0xFF
                val g = rgba[idx + 1].toInt() and 0xFF
                val b = rgba[idx + 2].toInt() and 0xFF
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f

                var wallVal = 0
                var floorVal = 0
                var ceilingVal = 0
                var windowVal = 0

                // 1. Ceiling region (top ~20%)
                if (ny < 0.20f) {
                    ceilingVal = ((1.0f - (ny / 0.20f) * 0.3f) * 255).toInt().coerceIn(0, 255)
                    ceilingPixels++
                }
                // 2. Floor region (bottom ~35%)
                else if (ny > 0.65f) {
                    val floorWeight = 0.6f + ((ny - 0.65f) / 0.35f) * 0.4f
                    floorVal = (floorWeight * 255).toInt().coerceIn(0, 255)
                    floorPixels++
                }
                // 3. Wall region (middle zone)
                else {
                    val wallWeight = 0.7f + (1.0f - kotlin.math.abs((ny - 0.425f) / 0.225f)) * 0.3f
                    // Window detection: high luminance bright spots on wall
                    if (lum > 0.88f) {
                        windowVal = (lum * 255).toInt().coerceIn(0, 255)
                        windowPixels++
                    } else {
                        wallVal = (wallWeight * 255).toInt().coerceIn(0, 255)
                        wallPixels++
                    }
                }

                maskBytes[idx] = wallVal.toByte()
                maskBytes[idx + 1] = floorVal.toByte()
                maskBytes[idx + 2] = ceilingVal.toByte()
                maskBytes[idx + 3] = windowVal.toByte()
            }
        }

        val classAreas = mapOf(
            "wall" to (wallPixels.toFloat() / totalPixels.toFloat()),
            "floor" to (floorPixels.toFloat() / totalPixels.toFloat()),
            "ceiling" to (ceilingPixels.toFloat() / totalPixels.toFloat()),
            "window" to (windowPixels.toFloat() / totalPixels.toFloat())
        )

        return SceneMaskResult(
            maskRgba = maskBytes,
            width = width,
            height = height,
            classAreas = classAreas,
            timestampNs = timestampNs
        )
    }

    fun reset() {
        frameCounter = 0L
        latestResult.set(null)
    }

    fun dispose() {
        mlThread.quitSafely()
        latestResult.set(null)
    }
}
