// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// AudioCaptureAndEncoder — mic capture via AudioRecord + AAC encoding via MediaCodec (M2-T2, M2-T3).

package com.dubsmash.dsr_engine.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import com.dubsmash.dsr_engine.encoder.MediaMuxerManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureAndEncoder(
    private val muxerManager: MediaMuxerManager,
    private val routeManager: AudioRouteManager,
    private val audioOffsetMs: Int = 0,
    private val sampleRate: Int = 44100,
    private val bitrateBps: Int = 128_000,
    private val backingAudioPath: String? = null,
) {
    companion object {
        private const val TAG = "DsrAudioCaptureEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Hardware camera sensor + ISP pipeline latency compensation.
        // On Android (CameraX/HAL3), visual frames reach OpenGL ~150ms after physical sensor exposure.
        // Audio capture hardware has only ~15ms latency.
        // To achieve frame-perfect lip-sync (M2-H1 <= 40ms), audio is aligned with camera capture.
        const val DEFAULT_CAMERA_PIPELINE_DELAY_MS = 150
    }

    private val mixer = AudioMixer()
    private val isRecording = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var recordThread: Thread? = null
    private var backingPcm: ShortArray? = null

    private var audioSessionId: Int = 0

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.getAndSet(true)) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            // Decode backing audio if provided
            Log.i(TAG, "Checking backingAudioPath: $backingAudioPath")
            if (!backingAudioPath.isNullOrEmpty()) {
                try {
                    backingPcm = AudioDecoder.decodeToPcm(backingAudioPath, sampleRate)
                    Log.i(TAG, "Loaded backing PCM: ${backingPcm?.size ?: 0} samples from $backingAudioPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode backing audio: $backingAudioPath", e)
                }
            }

            val hasBackingAudio = backingPcm != null && backingPcm!!.isNotEmpty()

            if (!hasBackingAudio) {
                // Original audio mode: Use microphone
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                audioRecord = record
                audioSessionId = record.audioSessionId

                // Configure AEC if speaker mode
                routeManager.configureEchoCancellation(audioSessionId)
                record.startRecording()
                Log.i(TAG, "Microphone recording started for original audio")
            } else {
                Log.i(TAG, "Background audio selected: microphone is switched off to prevent echo/layering")
            }

            // Initialize AAC encoder
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            recordThread = Thread({
                if (hasBackingAudio) {
                    backingAudioEncodeLoop(bufferSize)
                } else {
                    micCaptureAndEncodeLoop(bufferSize)
                }
            }, "dsr-audio-record").also { it.start() }

            Log.i(TAG, "Audio capture and encoder started (sampleRate=$sampleRate, offset=${audioOffsetMs}ms, hasBacking=$hasBackingAudio)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording and encoding", e)
            isRecording.set(false)
        }
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return

        try {
            recordThread?.join(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for audio record thread to finish", e)
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                audioCodec?.stop()
                audioCodec?.release()
                audioCodec = null

                backingPcm = null
                routeManager.releaseEchoCanceler()
            } catch (e: Exception) {
                Log.e(TAG, "Error tearing down audio recording", e)
            }
        }
        Log.i(TAG, "Audio capture and encoder stopped")
    }

    private fun feedSilenceToCodec(
        codec: MediaCodec,
        totalSilenceSamples: Int,
        bufferSize: Int,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        val chunkSize = (bufferSize / 2).coerceAtLeast(1024)
        var remaining = totalSilenceSamples
        var ptsUs = 0L

        while (remaining > 0 && isRecording.get()) {
            val toWrite = minOf(remaining, chunkSize)
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex)
                if (inputBuf != null) {
                    inputBuf.clear()
                    for (i in 0 until toWrite) {
                        inputBuf.putShort(0)
                    }
                    val numBytes = toWrite * 2
                    codec.queueInputBuffer(inputIndex, 0, numBytes, ptsUs, 0)
                    ptsUs += (toWrite.toLong() * 1_000_000L) / sampleRate
                    remaining -= toWrite
                }
            }
            drainAudioCodec(codec, bufferInfo)
        }
    }

    private fun micCaptureAndEncodeLoop(bufferSize: Int) {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return

        val pcmBuffer = ShortArray(bufferSize / 2)
        val bufferInfo = MediaCodec.BufferInfo()
        var presentationTimeUs = 0L

        // Initial delay compensation: aligns audio with camera sensor + ISP pipeline latency (~150ms).
        val totalDelayMs = (DEFAULT_CAMERA_PIPELINE_DELAY_MS + audioOffsetMs).coerceAtLeast(0)
        val initialSilenceSamples = (totalDelayMs.toLong() * sampleRate / 1000L).toInt()
        if (initialSilenceSamples > 0) {
            feedSilenceToCodec(codec, initialSilenceSamples, bufferSize, bufferInfo)
            presentationTimeUs += (initialSilenceSamples.toLong() * 1_000_000L) / sampleRate
            Log.i(TAG, "Prepend initial audio alignment silence: ${totalDelayMs}ms ($initialSilenceSamples samples)")
        }

        while (isRecording.get()) {
            val readSamples = record.read(pcmBuffer, 0, pcmBuffer.size)
            if (readSamples <= 0) continue

            val micSamples = pcmBuffer.copyOf(readSamples)

            // Feed PCM into MediaCodec input buffer
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex)
                if (inputBuf != null) {
                    inputBuf.clear()
                    for (sample in micSamples) {
                        inputBuf.putShort(sample)
                    }
                    val numBytes = micSamples.size * 2
                    val currentPtsUs = presentationTimeUs
                    presentationTimeUs += (micSamples.size.toLong() * 1_000_000L) / sampleRate.toLong()
                    codec.queueInputBuffer(inputIndex, 0, numBytes, currentPtsUs, 0)
                }
            }

            // Drain encoded AAC packets
            drainAudioCodec(codec, bufferInfo)
        }

        // Send EOS to audio encoder
        val eosInputIndex = codec.dequeueInputBuffer(10_000)
        if (eosInputIndex >= 0) {
            codec.queueInputBuffer(eosInputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainAudioCodec(codec, bufferInfo, drainAll = true)
    }

    private fun backingAudioEncodeLoop(bufferSize: Int) {
        val codec = audioCodec ?: return
        val backing = backingPcm ?: return
        if (backing.isEmpty()) return

        val bufferInfo = MediaCodec.BufferInfo()
        val chunkSize = (bufferSize / 2).coerceAtLeast(1024)
        val chunk = ShortArray(chunkSize)
        var backingCursor = 0
        var samplesWritten = 0L

        // Initial delay compensation: aligns backing audio with camera sensor + ISP pipeline latency
        val totalDelayMs = (DEFAULT_CAMERA_PIPELINE_DELAY_MS + audioOffsetMs).coerceAtLeast(0)
        val initialSilenceSamples = (totalDelayMs.toLong() * sampleRate / 1000L).toInt()
        if (initialSilenceSamples > 0) {
            feedSilenceToCodec(codec, initialSilenceSamples, bufferSize, bufferInfo)
            samplesWritten += initialSilenceSamples
            Log.i(TAG, "Prepend initial backing audio alignment silence: ${totalDelayMs}ms ($initialSilenceSamples samples)")
        }

        val startTimeMs = android.os.SystemClock.elapsedRealtime()

        while (isRecording.get()) {
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - startTimeMs
            val targetSamples = initialSilenceSamples + (elapsedMs * sampleRate) / 1000L

            if (samplesWritten > targetSamples) {
                val sleepMs = ((samplesWritten - targetSamples) * 1000L) / sampleRate
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs.coerceAtMost(20))
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                continue
            }

            for (i in 0 until chunkSize) {
                chunk[i] = backing[(backingCursor + i) % backing.size]
            }
            backingCursor = (backingCursor + chunkSize) % backing.size

            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex)
                if (inputBuf != null) {
                    inputBuf.clear()
                    for (sample in chunk) {
                        inputBuf.putShort(sample)
                    }
                    val numBytes = chunkSize * 2
                    val currentPtsUs = (samplesWritten * 1_000_000L) / sampleRate
                    samplesWritten += chunkSize
                    codec.queueInputBuffer(inputIndex, 0, numBytes, currentPtsUs, 0)
                }
            }

            drainAudioCodec(codec, bufferInfo)
        }

        // Catch up any remaining samples to match total elapsed recording duration
        val finalElapsedMs = android.os.SystemClock.elapsedRealtime() - startTimeMs
        val finalTargetSamples = initialSilenceSamples + (finalElapsedMs * sampleRate) / 1000L
        while (samplesWritten < finalTargetSamples) {
            val toWrite = (finalTargetSamples - samplesWritten).coerceAtMost(chunkSize.toLong()).toInt()
            if (toWrite <= 0) break
            for (i in 0 until toWrite) {
                chunk[i] = backing[(backingCursor + i) % backing.size]
            }
            backingCursor = (backingCursor + toWrite) % backing.size

            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inputIndex)
                if (inputBuf != null) {
                    inputBuf.clear()
                    for (i in 0 until toWrite) {
                        inputBuf.putShort(chunk[i])
                    }
                    val numBytes = toWrite * 2
                    val currentPtsUs = (samplesWritten * 1_000_000L) / sampleRate
                    samplesWritten += toWrite
                    codec.queueInputBuffer(inputIndex, 0, numBytes, currentPtsUs, 0)
                }
            } else {
                break
            }
            drainAudioCodec(codec, bufferInfo)
        }

        // Send EOS to audio encoder
        val eosInputIndex = codec.dequeueInputBuffer(10_000)
        if (eosInputIndex >= 0) {
            val finalPtsUs = (samplesWritten * 1_000_000L) / sampleRate
            codec.queueInputBuffer(eosInputIndex, 0, 0, finalPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainAudioCodec(codec, bufferInfo, drainAll = true)
    }

    private fun drainAudioCodec(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, drainAll: Boolean = false) {
        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, 5_000)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!drainAll) return
                    break
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    Log.i(TAG, "AudioEncoder output format changed: $format")
                    muxerManager.addAudioTrack(format)
                }
                status >= 0 -> {
                    val encodedBuffer = codec.getOutputBuffer(status)
                    if (encodedBuffer != null && bufferInfo.size > 0) {
                        muxerManager.writeSampleData(muxerManager.audioTrackIndex, encodedBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(status, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "AudioEncoder reached EOS")
                        muxerManager.signalEos(muxerManager.audioTrackIndex)
                        return
                    }
                }
            }
        }
    }
}
