// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// AudioMixer — 16-bit PCM audio mixer with gain control, soft-clipping protection,
// and latency offset compensation.

package com.dubsmash.dsr_engine.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 16-bit linear PCM audio mixer.
 *
 * M2 Tasks:
 *  - Mixes backing track audio with mic input audio
 *  - Applies individual gains for track and voice
 *  - Prevents distortion via limiter / clamping to [-32768, 32767]
 *  - Compensates for latency offset measured by calibration
 */
class AudioMixer {

    companion object {
        const val MIN_PCM_VALUE = -32768
        const val MAX_PCM_VALUE = 32767
        private const val SOFT_CLIP_THRESHOLD = 30000
    }

    /**
     * Clamps an integer sample value to the 16-bit PCM range [-32768, 32767].
     * Uses soft-knee compression above [SOFT_CLIP_THRESHOLD] to avoid harsh harmonic distortion.
     */
    fun clampSample(sample: Int): Short {
        if (sample > SOFT_CLIP_THRESHOLD) {
            val excess = sample - SOFT_CLIP_THRESHOLD
            val headroom = MAX_PCM_VALUE - SOFT_CLIP_THRESHOLD
            val compressed = SOFT_CLIP_THRESHOLD + (excess.toDouble() / (1.0 + (excess.toDouble() / headroom))).roundToInt()
            return min(compressed, MAX_PCM_VALUE).toShort()
        } else if (sample < -SOFT_CLIP_THRESHOLD) {
            val excess = -sample - SOFT_CLIP_THRESHOLD
            val headroom = -MIN_PCM_VALUE - SOFT_CLIP_THRESHOLD
            val compressed = SOFT_CLIP_THRESHOLD + (excess.toDouble() / (1.0 + (excess.toDouble() / headroom))).roundToInt()
            return max(-compressed, MIN_PCM_VALUE).toShort()
        }
        return sample.toShort()
    }

    /**
     * Scales an array of 16-bit PCM samples by [gain].
     */
    fun applyGain(samples: ShortArray, gain: Float): ShortArray {
        val result = ShortArray(samples.size)
        for (i in samples.indices) {
            val scaled = (samples[i] * gain).roundToInt()
            result[i] = clampSample(scaled)
        }
        return result
    }

    /**
     * Shifts [voice] audio by [offsetMs] relative to the track at [sampleRate] (Hz).
     *
     * If offsetMs > 0: voice audio is delayed (starts later), padded with silence at the beginning.
     * If offsetMs < 0: voice audio is advanced (starts earlier), trimmed from the start.
     */
    fun applyOffset(
        voice: ShortArray,
        offsetMs: Int,
        sampleRate: Int,
        channels: Int = 1,
    ): ShortArray {
        if (offsetMs == 0 || voice.isEmpty()) return voice.copyOf()

        val sampleShift = ((offsetMs.toLong() * sampleRate * channels) / 1000L).toInt()

        return if (sampleShift > 0) {
            // Delay voice: pad beginning with zeros
            val result = ShortArray(voice.size + sampleShift)
            System.arraycopy(voice, 0, result, sampleShift, voice.size)
            result
        } else {
            // Advance voice: skip beginning samples
            val skip = min(-sampleShift, voice.size)
            val resultSize = voice.size - skip
            val result = ShortArray(resultSize)
            System.arraycopy(voice, skip, result, 0, resultSize)
            result
        }
    }

    /**
     * Mixes backing [track] and [voice] samples together into a single 16-bit PCM buffer.
     * Applies [trackGain], [voiceGain], and latency [offsetMs].
     *
     * The output buffer length matches the maximum length needed to cover both tracks.
     */
    fun mixSamples(
        track: ShortArray,
        voice: ShortArray,
        trackGain: Float = 1.0f,
        voiceGain: Float = 1.0f,
        sampleRate: Int = 44100,
        offsetMs: Int = 0,
        channels: Int = 1,
    ): ShortArray {
        val offsetVoice = applyOffset(voice, offsetMs, sampleRate, channels)
        val outLength = max(track.size, offsetVoice.size)
        val mixed = ShortArray(outLength)

        for (i in 0 until outLength) {
            val tVal = if (i < track.size) (track[i] * trackGain).roundToInt() else 0
            val vVal = if (i < offsetVoice.size) (offsetVoice[i] * voiceGain).roundToInt() else 0
            mixed[i] = clampSample(tVal + vVal)
        }

        return mixed
    }
}
