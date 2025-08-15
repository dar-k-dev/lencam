package com.advancedcamera.media

import android.media.MediaCodecInfo
import android.media.MediaFormat

object MediaPipelines {
    fun hevcHighBitrateFormat(width: Int, height: Int, fps: Int, bitrateMbps: Int = 80): MediaFormat {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        return fmt
    }
}
