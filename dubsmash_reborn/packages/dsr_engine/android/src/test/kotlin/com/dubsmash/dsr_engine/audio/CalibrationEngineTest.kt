// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// CalibrationEngineTest — verifies calibration algorithm recovers synthetic loopback offset
// within +/- 5 ms (M2-A3).

package com.dubsmash.dsr_engine.audio

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs

class CalibrationEngineTest {

    private val engine = CalibrationEngine()
    private val sampleRate = 44100

    @Test
    fun `test synthetic loopback without noise recovers exact offset within 5 ms`() {
        val reference = engine.generateReferencePulse(sampleRate = sampleRate, durationMs = 25)

        // Test delays: 30 ms, 85 ms, 142 ms, 260 ms
        val testDelaysMs = intArrayOf(30, 85, 142, 260)

        for (delayMs in testDelaysMs) {
            val delaySamples = (delayMs * sampleRate) / 1000
            val recorded = ShortArray(delaySamples + reference.size + 1000)

            // Inject reference pulse at delaySamples offset
            System.arraycopy(reference, 0, recorded, delaySamples, reference.size)

            val recoveredOffsetMs = engine.estimateLatencyOffsetMs(
                reference = reference,
                recorded = recorded,
                sampleRate = sampleRate,
            )

            val errorMs = abs(recoveredOffsetMs - delayMs)
            assertTrue(
                errorMs <= 5,
                "Expected delay $delayMs ms, recovered $recoveredOffsetMs ms (error $errorMs ms > 5 ms)"
            )
        }
    }

    @Test
    fun `test synthetic loopback with background noise and attenuation recovers offset within 5 ms`() {
        val reference = engine.generateReferencePulse(sampleRate = sampleRate, durationMs = 30)
        val injectedDelayMs = 95
        val delaySamples = (injectedDelayMs * sampleRate) / 1000

        val totalSamples = delaySamples + reference.size + 2000
        val recorded = ShortArray(totalSamples)

        val random = Random(42) // Deterministic seed

        // Add background noise
        for (i in recorded.indices) {
            // Noise floor around +/- 500 (reference is ~24000)
            recorded[i] = ((random.nextDouble() - 0.5) * 1000).toInt().toShort()
        }

        // Add attenuated reference pulse at delay offset (simulating room acoustics: 40% amplitude)
        for (i in reference.indices) {
            val attenuated = (reference[i] * 0.4).toInt()
            val existing = recorded[delaySamples + i].toInt()
            recorded[delaySamples + i] = (existing + attenuated).coerceIn(-32768, 32767).toShort()
        }

        val recoveredOffsetMs = engine.estimateLatencyOffsetMs(
            reference = reference,
            recorded = recorded,
            sampleRate = sampleRate,
        )

        val errorMs = abs(recoveredOffsetMs - injectedDelayMs)
        assertTrue(
            errorMs <= 5,
            "Expected $injectedDelayMs ms with noise, recovered $recoveredOffsetMs ms (error $errorMs ms <= 5 ms requirement)"
        )
    }
}
