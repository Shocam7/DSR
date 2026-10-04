// Copyright (c) 2026 DubSmash Reborn. All rights reserved.
//
// AudioRouteManager — detects headphone/speaker routing, configures hardware AEC,
// and monitors mid-session audio route changes (Bluetooth / wired disconnects) (M2-T3, M2-T6).

package com.dubsmash.dsr_engine.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.util.Log

class AudioRouteManager(
    private val context: Context,
    private val onRouteChanged: (isHeadphones: Boolean, disconnected: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "DsrAudioRouteManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var echoCanceler: AcousticEchoCanceler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val headphones = isHeadphonesConnected()
            Log.i(TAG, "Audio device added. Headphones connected = $headphones")
            mainHandler.post { onRouteChanged(headphones, false) }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val headphones = isHeadphonesConnected()
            Log.i(TAG, "Audio device removed. Headphones connected = $headphones")
            mainHandler.post { onRouteChanged(headphones, true) }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.w(TAG, "Audio becoming noisy: headphones unplugged or Bluetooth disconnected")
                mainHandler.post { onRouteChanged(false, true) }
            }
        }
    }

    init {
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        context.registerReceiver(becomingNoisyReceiver, filter)
    }

    /**
     * Determines whether headphones or a Bluetooth audio device are currently active.
     */
    fun isHeadphonesConnected(): Boolean {
        val manager = audioManager ?: return false
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET -> return true
            }
        }
        return false
    }

    /**
     * Configures Acoustic Echo Cancellation for mic capture.
     * In speaker mode (no headphones), hardware AEC is attached if supported.
     * When headphones are connected, AEC is disabled to preserve full voice fidelity.
     */
    fun configureEchoCancellation(audioSessionId: Int): AcousticEchoCanceler? {
        releaseEchoCanceler()

        if (isHeadphonesConnected()) {
            Log.i(TAG, "Headphones active: skipping AEC for clean voice capture")
            return null
        }

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                val aec = AcousticEchoCanceler.create(audioSessionId)
                if (aec != null) {
                    aec.enabled = true
                    echoCanceler = aec
                    Log.i(TAG, "AcousticEchoCanceler enabled for session $audioSessionId")
                    return aec
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize AcousticEchoCanceler", e)
            }
        } else {
            Log.i(TAG, "AcousticEchoCanceler not supported on this device")
        }
        return null
    }

    fun releaseEchoCanceler() {
        try {
            echoCanceler?.enabled = false
            echoCanceler?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AcousticEchoCanceler", e)
        } finally {
            echoCanceler = null
        }
    }

    fun dispose() {
        releaseEchoCanceler()
        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering audio route receivers", e)
        }
    }
}
