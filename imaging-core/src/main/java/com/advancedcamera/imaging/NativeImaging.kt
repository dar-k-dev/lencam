package com.advancedcamera.imaging

object NativeImaging {
    init {
        try { System.loadLibrary("imagingcore") } catch (_: Throwable) {}
    }
    external fun version(): String
    external fun processHdr()
}
