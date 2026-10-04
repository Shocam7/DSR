// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
// Requires: implementation 'org.tensorflow:tensorflow-lite:2.14.0'
package com.dubsmash.dsr_engine.ml

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PixelExtractor {
    private const val TAG = "PixelExtractor"
    private var cachedBuffer: ByteBuffer? = null

    fun readRgba(width: Int, height: Int): ByteArray {
        if (width <= 0 || height <= 0) return ByteArray(0)
        val size = width * height * 4
        var buffer = cachedBuffer
        if (buffer == null || buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            cachedBuffer = buffer
        }
        buffer.clear()
        
        try {
            GLES20.glReadPixels(
                0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read pixels from framebuffer", e)
            return ByteArray(0)
        }
        
        val byteArray = ByteArray(width * height * 4)
        buffer.rewind()
        buffer.get(byteArray)
        return byteArray
    }
    
    fun scaleToMlInput(rgba: ByteArray, srcWidth: Int, srcHeight: Int, dstSize: Int = 256): ByteArray {
        if (rgba.isEmpty() || srcWidth <= 0 || srcHeight <= 0) return ByteArray(0)
        
        val rgb = ByteArray(dstSize * dstSize * 3)
        
        for (y in 0 until dstSize) {
            for (x in 0 until dstSize) {
                // Nearest-neighbor sampling
                val srcX = (x * srcWidth) / dstSize
                val srcY = (y * srcHeight) / dstSize
                
                val srcIndex = (srcY * srcWidth + srcX) * 4
                val dstIndex = (y * dstSize + x) * 3
                
                if (srcIndex + 2 < rgba.size && dstIndex + 2 < rgb.size) {
                    rgb[dstIndex] = rgba[srcIndex]         // R
                    rgb[dstIndex + 1] = rgba[srcIndex + 1] // G
                    rgb[dstIndex + 2] = rgba[srcIndex + 2] // B
                }
            }
        }
        
        return rgb
    }
}
