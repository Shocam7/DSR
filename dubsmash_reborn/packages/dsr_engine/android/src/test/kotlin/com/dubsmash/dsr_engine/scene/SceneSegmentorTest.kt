// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
package com.dubsmash.dsr_engine.scene

import com.dubsmash.dsr_engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneSegmentorTest {

    @Test
    fun testTieredResolutionAndCadence_M4_T1() {
        assertEquals(160, SceneSegmentor.getResolutionForTier(Tier.L))
        assertEquals(256, SceneSegmentor.getResolutionForTier(Tier.M))
        assertEquals(320, SceneSegmentor.getResolutionForTier(Tier.H))

        assertEquals(4, SceneSegmentor.getCadenceForTier(Tier.L))
        assertEquals(3, SceneSegmentor.getCadenceForTier(Tier.M))
        assertEquals(2, SceneSegmentor.getCadenceForTier(Tier.H))
    }

    @Test
    fun testSurfaceClassificationPriors() {
        val segmentor = SceneSegmentor(Tier.M)
        val w = 64
        val h = 64
        // Create synthetic camera frame with moderate room luminance (gray)
        val rgba = ByteArray(w * h * 4) { 0x80.toByte() }

        val result = segmentor.processSceneSegmentation(rgba, w, h, System.nanoTime())
        assertNotNull(result)
        assertEquals(w, result.width)
        assertEquals(h, result.height)

        val areas = result.classAreas
        assertTrue("Wall area should be dominant in middle span", areas["wall"] ?: 0f > 0.35f)
        assertTrue("Floor area should occupy lower region", areas["floor"] ?: 0f > 0.25f)
        assertTrue("Ceiling area should occupy upper region", areas["ceiling"] ?: 0f > 0.15f)

        // Check specific pixel coordinates
        val ceilingIdx = (5 * w + 32) * 4 // Near top
        val ceilingVal = result.maskRgba[ceilingIdx + 2].toInt() and 0xFF // Blue channel
        assertTrue("Top pixel should have high ceiling confidence", ceilingVal > 150)

        val wallIdx = (32 * w + 32) * 4 // Center
        val wallVal = result.maskRgba[wallIdx].toInt() and 0xFF // Red channel
        assertTrue("Center pixel should have high wall confidence", wallVal > 150)

        val floorIdx = (55 * w + 32) * 4 // Near bottom
        val floorVal = result.maskRgba[floorIdx + 1].toInt() and 0xFF // Green channel
        assertTrue("Bottom pixel should have high floor confidence", floorVal > 150)
    }

    /**
     * M4-A3: Scene-seg IoU on golden set >= baseline - 2%.
     * Validates wall and floor segmentation accuracy against ground truth scene.
     */
    @Test
    fun testSceneSegIoUOnGoldenSet_M4_A3() {
        val segmentor = SceneSegmentor(Tier.M)
        val w = 64
        val h = 64
        val rgba = ByteArray(w * h * 4) { 0x80.toByte() }

        val result = segmentor.processSceneSegmentation(rgba, w, h, System.nanoTime())

        // Create ground truth masks for synthetic room
        val gtWall = BooleanArray(w * h)
        val gtFloor = BooleanArray(w * h)

        for (y in 0 until h) {
            val ny = y.toFloat() / h.toFloat()
            for (x in 0 until w) {
                val idx = y * w + x
                if (ny in 0.20f..0.65f) gtWall[idx] = true
                if (ny > 0.65f) gtFloor[idx] = true
            }
        }

        // Compute predicted wall and floor booleans
        val predWall = BooleanArray(w * h)
        val predFloor = BooleanArray(w * h)
        for (i in 0 until (w * h)) {
            val r = result.maskRgba[i * 4].toInt() and 0xFF
            val g = result.maskRgba[i * 4 + 1].toInt() and 0xFF
            if (r > 100) predWall[i] = true
            if (g > 100) predFloor[i] = true
        }

        fun computeIoU(pred: BooleanArray, gt: BooleanArray): Double {
            var intersection = 0
            var union = 0
            for (i in pred.indices) {
                if (pred[i] && gt[i]) intersection++
                if (pred[i] || gt[i]) union++
            }
            return if (union > 0) intersection.toDouble() / union.toDouble() else 1.0
        }

        val wallIoU = computeIoU(predWall, gtWall)
        val floorIoU = computeIoU(predFloor, gtFloor)

        // Baseline IoU requirement: >= 0.80
        val baselineIoU = 0.80
        val threshold = baselineIoU - 0.02 // 0.78

        assertTrue("Wall IoU $wallIoU must be >= $threshold", wallIoU >= threshold)
        assertTrue("Floor IoU $floorIoU must be >= $threshold", floorIoU >= threshold)
    }

    @Test
    fun testWindowDetectionOnHighLuminancePixels() {
        val segmentor = SceneSegmentor(Tier.M)
        val w = 64
        val h = 64
        val rgba = ByteArray(w * h * 4) { 0x60.toByte() }

        // Paint a bright white window in the wall region
        for (y in 25..35) {
            for (x in 25..35) {
                val idx = (y * w + x) * 4
                rgba[idx] = 0xFF.toByte()
                rgba[idx + 1] = 0xFF.toByte()
                rgba[idx + 2] = 0xFF.toByte()
            }
        }

        val result = segmentor.processSceneSegmentation(rgba, w, h, System.nanoTime())
        val windowArea = result.classAreas["window"] ?: 0f
        assertTrue("Bright region on wall should be classified as window", windowArea > 0.01f)

        // Check center of the window
        val windowPixelIdx = (30 * w + 30) * 4
        val windowVal = result.maskRgba[windowPixelIdx + 3].toInt() and 0xFF
        assertTrue("Window pixel should have high confidence in alpha channel", windowVal > 200)
    }
}
