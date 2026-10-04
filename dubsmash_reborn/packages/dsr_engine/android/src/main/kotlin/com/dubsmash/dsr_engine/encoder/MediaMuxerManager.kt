// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// MediaMuxerManager — thread-safe coordinator for muxing H.264 video and AAC audio tracks into MP4.

package com.dubsmash.dsr_engine.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

class MediaMuxerManager(
    outputPath: String,
    private val expectedTracks: Int = 2,
) {
    companion object {
        private const val TAG = "DsrMediaMuxer"
    }

    private class PendingSample(
        val trackIndex: Int,
        val data: ByteArray,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()

    @Volatile
    private var isStarted = false
    private var numTracksAdded = 0
    private val pendingSamples = mutableListOf<PendingSample>()

    var videoTrackIndex = -1
        private set
    var audioTrackIndex = -1
        private set

    private var videoEos = false
    private var audioEos = false

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        if (isStarted) {
            Log.w(TAG, "Attempted to add video track after muxer started")
            return videoTrackIndex
        }
        videoTrackIndex = muxer.addTrack(format)
        numTracksAdded++
        Log.i(TAG, "Video track added (index $videoTrackIndex). Total tracks: $numTracksAdded / $expectedTracks")
        checkStartMuxer()
        return videoTrackIndex
    }

    fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
        if (isStarted) {
            Log.w(TAG, "Attempted to add audio track after muxer started")
            return audioTrackIndex
        }
        audioTrackIndex = muxer.addTrack(format)
        numTracksAdded++
        Log.i(TAG, "Audio track added (index $audioTrackIndex). Total tracks: $numTracksAdded / $expectedTracks")
        checkStartMuxer()
        return audioTrackIndex
    }

    private fun checkStartMuxer() {
        if (!isStarted && numTracksAdded >= expectedTracks) {
            muxer.start()
            isStarted = true
            Log.i(TAG, "MediaMuxer started with $numTracksAdded tracks. Flushing ${pendingSamples.size} pending samples.")
            for (sample in pendingSamples) {
                val buf = ByteBuffer.wrap(sample.data)
                val info = MediaCodec.BufferInfo().apply {
                    set(sample.offset, sample.size, sample.presentationTimeUs, sample.flags)
                }
                try {
                    muxer.writeSampleData(sample.trackIndex, buf, info)
                } catch (e: Exception) {
                    Log.e(TAG, "Error flushing pending sample for track ${sample.trackIndex}", e)
                }
            }
            pendingSamples.clear()
        }
    }

    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // Codec config data already handled by addTrack
                return
            }
            if (bufferInfo.size == 0) {
                return
            }

            if (!isStarted) {
                // Buffer received before muxer started (waiting for other track's format).
                // Queue it so initial key frames and audio samples are NEVER lost!
                val bytes = ByteArray(bufferInfo.size)
                val dup = byteBuf.duplicate()
                dup.position(bufferInfo.offset)
                dup.limit(bufferInfo.offset + bufferInfo.size)
                dup.get(bytes)
                pendingSamples.add(
                    PendingSample(
                        trackIndex = trackIndex,
                        data = bytes,
                        offset = 0,
                        size = bufferInfo.size,
                        presentationTimeUs = bufferInfo.presentationTimeUs,
                        flags = bufferInfo.flags,
                    )
                )
                Log.i(TAG, "Buffered pending sample for track $trackIndex: size=${bufferInfo.size}, pts=${bufferInfo.presentationTimeUs}us, flags=${bufferInfo.flags}")
                return
            }

            try {
                muxer.writeSampleData(trackIndex, byteBuf, bufferInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing sample data to track $trackIndex", e)
            }
        }
    }

    fun signalEos(trackIndex: Int) {
        synchronized(lock) {
            if (trackIndex == videoTrackIndex) videoEos = true
            if (trackIndex == audioTrackIndex) audioEos = true
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                if (isStarted) {
                    muxer.stop()
                    isStarted = false
                    Log.i(TAG, "MediaMuxer stopped cleanly")
                }
                muxer.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping / releasing MediaMuxer", e)
            } finally {
                pendingSamples.clear()
            }
        }
    }
}
