// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
// Requires: implementation 'org.tensorflow:tensorflow-lite:2.14.0'
package com.dubsmash.dsr_engine.ml

import android.content.Context
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

class ModelChecksumException(message: String) : Exception(message)

object ModelLoader {
    const val MODEL_SELFIE_SEG_GENERAL_SHA256 = "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b"
    const val MODEL_SELFIE_SEG_LANDSCAPE_SHA256 = "490e9ea734313e0de10fa0cd9e3c6133e36ea4db2b7a49bde9ef019f72796b8e"
    const val MODEL_SELFIE_SEG_SHA256 = MODEL_SELFIE_SEG_LANDSCAPE_SHA256

    val modelChecksums = mapOf(
        "selfie_segmentation.tflite" to MODEL_SELFIE_SEG_LANDSCAPE_SHA256,
        "selfie_segmenter_landscape.tflite" to MODEL_SELFIE_SEG_LANDSCAPE_SHA256
    )

    private var cachedModel: MappedByteBuffer? = null

    internal fun resolveAssetPath(context: Context, assetPath: String): String {
        return try {
            context.assets.open(assetPath).close()
            assetPath
        } catch (e: Exception) {
            val flutterPath = "flutter_assets/$assetPath"
            try {
                context.assets.open(flutterPath).close()
                flutterPath
            } catch (e2: Exception) {
                val fullFlutterPath = "flutter_assets/assets/models/$assetPath"
                try {
                    context.assets.open(fullFlutterPath).close()
                    fullFlutterPath
                } catch (e3: Exception) {
                    assetPath
                }
            }
        }
    }

    @Throws(ModelChecksumException::class, IOException::class)
    fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
        cachedModel?.let { return it }

        val resolvedPath = resolveAssetPath(context, assetPath)
        val expectedSha256 = modelChecksums[assetPath] ?: modelChecksums[resolvedPath]
        if (expectedSha256 != null) {
            if (!verifyChecksum(context, resolvedPath, expectedSha256)) {
                throw ModelChecksumException("Checksum verification failed for model: $assetPath (resolved: $resolvedPath)")
            }
        }

        val assetFileDescriptor = context.assets.openFd(resolvedPath)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        cachedModel = mappedByteBuffer
        return mappedByteBuffer
    }

    fun verifyChecksum(context: Context, assetPath: String, expectedSha256: String): Boolean {
        return try {
            val resolvedPath = resolveAssetPath(context, assetPath)
            context.assets.open(resolvedPath).use { inputStream ->
                verifyChecksum(inputStream, expectedSha256)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun verifyChecksum(inputStream: InputStream, expectedSha256: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            val hashBytes = digest.digest()
            val hexString = StringBuilder()
            for (byte in hashBytes) {
                val hex = Integer.toHexString(0xff and byte.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            hexString.toString() == expectedSha256
        } catch (e: Exception) {
            false
        }
    }
}
