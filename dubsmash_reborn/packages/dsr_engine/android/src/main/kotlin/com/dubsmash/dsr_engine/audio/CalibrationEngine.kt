// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// CalibrationEngine — measures round-trip audio latency using cross-correlation
// on a synthetic chirp / pulse loopback (M2-T4, M2-A3).

package com.dubsmash.dsr_engine.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Audio latency calibration engine.
 *
 * Emits a known reference pulse and cross-correlates it against the mic input
 * to detect the exact round-trip latency (audio output + acoustic path + mic input).
 */
class CalibrationEngine {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_PULSE_DURATION_MS = 25
        const val DEFAULT_FREQUENCY_HZ = 1200.0
    }

    /**
     * Generates a smooth Hann-windowed sinusoidal beep pulse used as the reference signal.
     */
    fun generateReferencePulse(
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        durationMs: Int = DEFAULT_PULSE_DURATION_MS,
        frequencyHz: Double = DEFAULT_FREQUENCY_HZ,
    ): ShortArray {
        val numSamples = (durationMs * sampleRate) / 1000
        val pulse = ShortArray(numSamples)
        val angularFreq = 2.0 * PI * frequencyHz / sampleRate

        for (i in 0 until numSamples) {
            // Hann window: 0.5 * (1 - cos(2*pi*n / (N-1)))
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (numSamples - 1)))
            val sine = sin(angularFreq * i)
            val sample = (window * sine * 24000.0).roundToInt()
            pulse[i] = sample.coerceIn(-32768, 32767).toShort()
        }
        return pulse
    }

    /**
     * Estimates the delay in milliseconds between [reference] and [recorded] signals.
     *
     * @param reference The known emitted signal
     * @param recorded The recorded microphone stream
     * @param sampleRate Sampling rate in Hz
     * @param maxSearchMs Maximum lag to search for (default 500 ms)
     * @return Estimated latency offset in milliseconds
     */
    fun estimateLatencyOffsetMs(
        reference: ShortArray,
        recorded: ShortArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        maxSearchMs: Int = 500,
    ): Int {
        if (reference.isEmpty() || recorded.isEmpty()) return 0

        val maxLagSamples = min(
            (maxSearchMs.toLong() * sampleRate / 1000L).toInt(),
            recorded.size - 1
        )
        if (maxLagSamples <= 0) return 0

        val bestLag = findCrossCorrelationPeakLag(reference, recorded, maxLagSamples)
        return ((bestLag.toLong() * 1000L) / sampleRate.toLong()).toInt()
    }

    /**
     * Computes the cross-correlation R_xy(lag) = sum_n (x[n] * y[n + lag])
     * for lag in [0, maxLagSamples] and returns the lag with the maximum correlation peak.
     */
    fun findCrossCorrelationPeakLag(
        reference: ShortArray,
        recorded: ShortArray,
        maxLagSamples: Int,
    ): Int {
        var maxCorrelation = Double.NEGATIVE_INFINITY
        var peakLag = 0

        val refLen = reference.size
        val recLen = recorded.size

        // For performance and energy normalization
        var refEnergy = 0.0
        for (sample in reference) {
            refEnergy += sample.toDouble() * sample.toDouble()
        }
        if (refEnergy == 0.0) return 0

        for (lag in 0..maxLagSamples) {
            var sum = 0.0
            val overlap = min(refLen, recLen - lag)
            if (overlap <= 0) break

            for (n in 0 until overlap) {
                sum += reference[n].toDouble() * recorded[lag + n].toDouble()
            }

            if (sum > maxCorrelation) {
                maxCorrelation = sum
                peakLag = lag
            }
        }

        return peakLag
    }
}
