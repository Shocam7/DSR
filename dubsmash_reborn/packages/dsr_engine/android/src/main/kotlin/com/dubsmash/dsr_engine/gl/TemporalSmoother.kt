// Copyright (c) 2026 DubSmash Reborn. All rights reserved.

package com.dubsmash.dsr_engine.gl

class TemporalSmoother(
    private val minAlpha: Float = 0.55f, // Still subject: strong smoothing, 0 flicker
    private val maxAlpha: Float = 0.95f, // Fast motion: snap to current mask, 0 lag
) {
    private var previousMask: FloatArray? = null

    /** Last computed motion delta (0.0 = still, > 0.06 = rapid motion). */
    @Volatile
    var lastMotionDelta: Float = 0f
        private set

    fun smooth(currentMask: FloatArray): FloatArray {
        val prev = previousMask
        if (prev == null || prev.size != currentMask.size) {
            previousMask = currentMask.clone()
            lastMotionDelta = 0f
            return currentMask
        }

        // 1. Sample frame-to-frame absolute difference (motion delta) across every 4th pixel (<0.05ms)
        var totalDiff = 0f
        var sampled = 0
        val step = 4
        for (i in currentMask.indices step step) {
            totalDiff += kotlin.math.abs(currentMask[i] - prev[i])
            sampled++
        }
        val delta = if (sampled > 0) totalDiff / sampled else 0f
        lastMotionDelta = delta

        // 2. Motion-adaptive dynamic alpha mapping:
        // delta <= 0.02f -> minAlpha (0.55f) for maximum temporal stability
        // delta >= 0.07f -> maxAlpha (0.95f) for instant snap and zero ghosting lag
        val motionFactor = ((delta - 0.02f) / (0.07f - 0.02f)).coerceIn(0f, 1f)
        val alpha = minAlpha + (maxAlpha - minAlpha) * motionFactor

        val smoothed = FloatArray(currentMask.size)
        val oneMinusAlpha = 1f - alpha
        for (i in currentMask.indices) {
            smoothed[i] = alpha * currentMask[i] + oneMinusAlpha * prev[i]
        }
        previousMask = smoothed
        return smoothed
    }

    fun reset() {
        previousMask = null
        lastMotionDelta = 0f
    }
}
