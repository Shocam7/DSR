// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// AudioMixerTest — verifies gain, clipping protection, and offset application (M2-A2).

package com.dubsmash.dsr_engine.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioMixerTest {

    private val mixer = AudioMixer()

    @Test
    fun `test gain control scales audio amplitudes accurately`() {
        val input = shortArrayOf(0, 1000, -2000, 10000, -15000)

        // Unity gain
        val unity = mixer.applyGain(input, 1.0f)
        for (i in input.indices) {
            assertEquals(input[i], unity[i])
        }

        // Half gain (attenuation)
        val half = mixer.applyGain(input, 0.5f)
        assertEquals(0.toShort(), half[0])
        assertEquals(500.toShort(), half[1])
        assertEquals((-1000).toShort(), half[2])
        assertEquals(5000.toShort(), half[3])
        assertEquals((-7500).toShort(), half[4])

        // Zero gain (mute)
        val muted = mixer.applyGain(input, 0.0f)
        for (sample in muted) {
            assertEquals(0.toShort(), sample)
        }
    }

    @Test
    fun `test clipping protection prevents overflow and clamps within 16-bit range`() {
        // Test values that would exceed 16-bit signed short [-32768, 32767]
        val extremePositive = 65536
        val extremeNegative = -65536

        val clampedPos = mixer.clampSample(extremePositive)
        val clampedNeg = mixer.clampSample(extremeNegative)

        assertTrue(clampedPos <= AudioMixer.MAX_PCM_VALUE)
        assertTrue(clampedPos >= 30000)
        assertTrue(clampedNeg >= AudioMixer.MIN_PCM_VALUE)
        assertTrue(clampedNeg <= -30000)

        // Multiple overlapping loud signals
        val loudTrack = shortArrayOf(25000, -25000, 30000, -32000)
        val loudVoice = shortArrayOf(25000, -25000, 30000, -32000)

        val mixed = mixer.mixSamples(loudTrack, loudVoice, trackGain = 1.0f, voiceGain = 1.0f)

        for (sample in mixed) {
            assertTrue(sample in AudioMixer.MIN_PCM_VALUE..AudioMixer.MAX_PCM_VALUE,
                "Sample $sample should be within [-32768, 32767]")
        }
    }

    @Test
    fun `test positive audio offset delays voice with leading silence`() {
        val voice = shortArrayOf(100, 200, 300, 400)
        val sampleRate = 1000 // 1000 samples/sec -> 1 sample per ms
        val offsetMs = 10 // Should delay by 10 samples

        val delayed = mixer.applyOffset(voice, offsetMs = offsetMs, sampleRate = sampleRate)

        assertEquals(14, delayed.size)
        // Leading 10 samples must be silence (0)
        for (i in 0 until 10) {
            assertEquals(0.toShort(), delayed[i], "Sample at index $i should be silence")
        }
        // Following samples must match original voice
        assertEquals(100.toShort(), delayed[10])
        assertEquals(200.toShort(), delayed[11])
        assertEquals(300.toShort(), delayed[12])
        assertEquals(400.toShort(), delayed[13])
    }

    @Test
    fun `test negative audio offset advances voice by trimming leading samples`() {
        val voice = shortArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val sampleRate = 1000
        val offsetMs = -3 // Advances by 3 samples (skips first 3)

        val advanced = mixer.applyOffset(voice, offsetMs = offsetMs, sampleRate = sampleRate)

        assertEquals(5, advanced.size)
        assertEquals(40.toShort(), advanced[0])
        assertEquals(50.toShort(), advanced[1])
        assertEquals(60.toShort(), advanced[2])
        assertEquals(70.toShort(), advanced[3])
        assertEquals(80.toShort(), advanced[4])
    }

    @Test
    fun `test complete mix with track, voice, gains, and offset`() {
        val sampleRate = 1000
        val track = shortArrayOf(1000, 1000, 1000, 1000, 1000)
        val voice = shortArrayOf(2000, 2000, 2000)
        val offsetMs = 2 // Voice starts at sample 2

        val mixed = mixer.mixSamples(
            track = track,
            voice = voice,
            trackGain = 0.5f, // track -> 500
            voiceGain = 0.5f, // voice -> 1000
            sampleRate = sampleRate,
            offsetMs = offsetMs,
        )

        assertEquals(5, mixed.size)
        // Indices 0 and 1: only track (500)
        assertEquals(500.toShort(), mixed[0])
        assertEquals(500.toShort(), mixed[1])
        // Indices 2, 3, 4: track + voice (500 + 1000 = 1500)
        assertEquals(1500.toShort(), mixed[2])
        assertEquals(1500.toShort(), mixed[3])
        assertEquals(1500.toShort(), mixed[4])
    }
}
