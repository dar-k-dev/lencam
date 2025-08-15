package com.advancedcamera.ml

import android.content.Context

sealed class Mode { object Auto: Mode(); object HDR: Mode(); object Night: Mode(); object SR: Mode(); object Portrait: Mode() }

data class MlConfig(val useGpu: Boolean = true)

class MlPipelines(private val context: Context, private val config: MlConfig = MlConfig()) {
    fun denoise(input: ByteArray): ByteArray = input // stub no-op
    fun depthEstimate(input: ByteArray): ByteArray = ByteArray(0) // stub
    fun segmentSubject(input: ByteArray): ByteArray = ByteArray(0) // stub
    fun superResolve(input: ByteArray): ByteArray = input // stub
}
