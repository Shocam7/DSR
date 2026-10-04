// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
// Requires: implementation 'org.tensorflow:tensorflow-lite:2.14.0'
package com.dubsmash.dsr_engine.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

@RunWith(JUnit4::class)
class ModelLoaderTest {

    @Test
    fun wrongChecksumThrows() {
        val tempFile = File.createTempFile("test_wrong", ".bin")
        tempFile.deleteOnExit()
        val randomBytes = ByteArray(1024) { it.toByte() }
        tempFile.writeBytes(randomBytes)
        
        val wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000"
        
        FileInputStream(tempFile).use { inputStream ->
            val result = ModelLoader.verifyChecksum(inputStream, wrongChecksum)
            assertFalse("Expected false for wrong checksum", result)
        }
    }

    @Test
    fun correctChecksumPasses() {
        val tempFile = File.createTempFile("test_correct", ".bin")
        tempFile.deleteOnExit()
        val knownBytes = "test model data".toByteArray(Charsets.UTF_8)
        tempFile.writeBytes(knownBytes)
        
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(knownBytes)
        val hexString = StringBuilder()
        for (byte in hashBytes) {
            val hex = Integer.toHexString(0xff and byte.toInt())
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }
        val correctChecksum = hexString.toString()
        
        FileInputStream(tempFile).use { inputStream ->
            val result = ModelLoader.verifyChecksum(inputStream, correctChecksum)
            assertTrue("Expected true for correct checksum", result)
        }
    }
}
