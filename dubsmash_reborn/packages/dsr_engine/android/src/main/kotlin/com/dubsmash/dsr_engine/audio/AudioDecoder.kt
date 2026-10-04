// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// AudioDecoder — decodes audio files to 16-bit linear PCM.

package com.dubsmash.dsr_engine.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes audio files (MP3, AAC, WAV, M4A, OGG) to 16-bit linear PCM mono.
 */
object AudioDecoder {
    private const val TAG = "AudioDecoder"

    fun decodeToPcm(filePath: String, targetSampleRate: Int = 44100): ShortArray? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file does not exist: $filePath")
            return null
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(filePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.e(TAG, "No audio track found in $filePath")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            val fileSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                targetSampleRate
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmList = ArrayList<Short>(targetSampleRate * 15) // ~15s initial capacity
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false

            while (!isEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                    if (inBuffer != null) {
                        inBuffer.clear()
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        if (channels >= 2) {
                            // Downmix stereo to mono
                            while (shortBuf.remaining() >= channels) {
                                var sum = 0
                                for (c in 0 until channels) {
                                    sum += shortBuf.get()
                                }
                                pcmList.add((sum / channels).toShort())
                            }
                        } else {
                            while (shortBuf.hasRemaining()) {
                                pcmList.add(shortBuf.get())
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            val result = ShortArray(pcmList.size)
            for (i in pcmList.indices) {
                result[i] = pcmList[i]
            }

            if (fileSampleRate != targetSampleRate && fileSampleRate > 0) {
                val ratio = targetSampleRate.toDouble() / fileSampleRate.toDouble()
                val resampledLength = (result.size * ratio).toInt()
                val resampled = ShortArray(resampledLength)
                for (i in 0 until resampledLength) {
                    val srcPos = i / ratio
                    val srcIndex = srcPos.toInt()
                    val frac = srcPos - srcIndex
                    if (srcIndex + 1 < result.size) {
                        val s0 = result[srcIndex]
                        val s1 = result[srcIndex + 1]
                        resampled[i] = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767).toShort()
                    } else if (srcIndex < result.size) {
                        resampled[i] = result[srcIndex]
                    }
                }
                Log.i(TAG, "Decoded and resampled from ${fileSampleRate}Hz to ${targetSampleRate}Hz (${resampled.size} samples) from $filePath")
                return resampled
            }

            Log.i(TAG, "Decoded ${result.size} mono PCM samples from $filePath")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio to PCM: $filePath", e)
            return null
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (e: Exception) {}
        }
    }
}
