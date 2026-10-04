// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// VideoEncoder — H.264 hardware surface encoder via MediaCodec (M2-T1).

package com.dubsmash.dsr_engine.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrateBps: Int = 10_000_000,
    private val frameRate: Int = 30,
    private val iFrameIntervalSecs: Int = 1,
    private val muxerManager: MediaMuxerManager,
) {
    companion object {
        private const val TAG = "DsrVideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
    val inputSurface: Surface

    private val isRecording = AtomicBoolean(false)
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSecs)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        Log.i(TAG, "VideoEncoder configured ($width x $height @ $bitrateBps bps)")
    }

    fun start() {
        if (isRecording.getAndSet(true)) return
        codec.start()

        // Explicitly request an immediate IDR sync frame for frame 0
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
            Log.i(TAG, "Requested immediate sync frame on VideoEncoder start")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request immediate sync frame", e)
        }

        drainThread = Thread({
            drainEncoder()
        }, "dsr-video-encoder").also { it.start() }
        Log.i(TAG, "VideoEncoder started")
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return

        try {
            codec.signalEndOfInputStream()
            drainThread?.join(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling EOS to VideoEncoder", e)
        } finally {
            try {
                codec.stop()
                codec.release()
                inputSurface.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing VideoEncoder", e)
            }
        }
        Log.i(TAG, "VideoEncoder stopped and released")
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get() || !Thread.currentThread().isInterrupted) {
            val status = codec.dequeueOutputBuffer(bufferInfo, 10_000)

            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!isRecording.get()) {
                        // When stopped, check if EOS was signaled
                        break
                    }
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "VideoEncoder output format changed: $newFormat")
                    muxerManager.addVideoTrack(newFormat)
                }
                status >= 0 -> {
                    val encodedBuffer = codec.getOutputBuffer(status)
                    if (encodedBuffer != null) {
                        if (bufferInfo.size > 0) {
                            muxerManager.writeSampleData(muxerManager.videoTrackIndex, encodedBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(status, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "VideoEncoder received EOS flag")
                        muxerManager.signalEos(muxerManager.videoTrackIndex)
                        break
                    }
                }
            }
        }
    }
}
