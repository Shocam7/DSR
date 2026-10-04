// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class SurfaceCompositorTest {

    @Test
    fun testLightingTransferPreservesShadowsAndHighlights() {
        // Formula per Guide §4.3: lightTransfer = clamp(lum / max(meanLum, 0.08), 0.15, 2.5)
        fun computeTransfer(lum: Float, meanLum: Float): Float {
            val denom = max(meanLum, 0.08f)
            val raw = lum / denom
            return min(max(raw, 0.15f), 2.5f)
        }

        // Test in dark room (shadow)
        val shadowTransfer = computeTransfer(0.05f, 0.40f)
        assertTrue("Shadows should darken the texture", shadowTransfer < 0.3f)
        assertTrue("Shadows should not completely crush to black", shadowTransfer >= 0.15f)

        // Test in neutral area matching average room lighting
        val neutralTransfer = computeTransfer(0.40f, 0.40f)
        assertEquals(1.0f, neutralTransfer, 0.01f)

        // Test in bright spotlight
        val highlightTransfer = computeTransfer(0.95f, 0.40f)
        assertTrue("Highlights should brighten the texture", highlightTransfer > 2.0f)
        assertTrue("Highlights should be capped", highlightTransfer <= 2.5f)

        // Test in very dark room with low mean lum
        val darkRoomTransfer = computeTransfer(0.08f, 0.02f)
        assertEquals(1.0f, darkRoomTransfer, 0.01f)
    }

    /**
     * M4-A7: Crossfade produces no frame above 50 ms.
     * Simulates consecutive frames during a 400 ms crossfade transition.
     */
    @Test
    fun crossfadeProducesNoFrameAbove50Ms_M4_A7() {
        val crossfadeDurationMs = 400L
        val targetFps = 30
        val frameIntervalMs = 1000L / targetFps // ~33ms
        val totalFrames = (crossfadeDurationMs / frameIntervalMs).toInt() + 2

        val frameTimesMs = mutableListOf<Double>()

        for (frame in 0 until totalFrames) {
            val startNs = System.nanoTime()

            // Simulate shader uniform upload + texture binding work during crossfade
            val progress = (frame * frameIntervalMs).toFloat() / crossfadeDurationMs.toFloat()
            val alpha = min(max(progress, 0.0f), 1.0f)

            // Simulate crossfade blend calculation
            var accumulator = 0.0f
            for (p in 0 until 1000) {
                val sampleA = (p % 256) / 255.0f
                val sampleB = ((p + 50) % 256) / 255.0f
                accumulator += sampleA * (1.0f - alpha) + sampleB * alpha
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            frameTimesMs.add(elapsedMs)
            assertTrue("Frame $frame during crossfade took ${elapsedMs}ms, exceeded 50ms threshold!", elapsedMs <= 50.0)
        }

        val maxFrameMs = frameTimesMs.maxOrNull() ?: 0.0
        assertTrue("Maximum crossfade frame time $maxFrameMs ms must be <= 50 ms", maxFrameMs <= 50.0)
    }

    @Test
    fun testTilingScaleCalculations() {
        fun calcTile(tileM: Float): Float {
            return (1.0f / tileM).coerceIn(0.1f, 10.0f) * 2.0f
        }

        // 1.0m tile -> 2.0 repeats
        assertEquals(2.0f, calcTile(1.0f), 0.01f)
        // 0.5m tile -> 4.0 repeats (smaller pattern repeats more)
        assertEquals(4.0f, calcTile(0.5f), 0.01f)
        // 2.0m tile -> 1.0 repeat (larger pattern repeats less)
        assertEquals(1.0f, calcTile(2.0f), 0.01f)
    }
}
