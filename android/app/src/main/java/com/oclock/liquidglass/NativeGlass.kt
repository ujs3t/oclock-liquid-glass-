package com.oclock.liquidglass

import android.graphics.Bitmap

object NativeGlass {
    val available: Boolean

    init {
        available = try {
            System.loadLibrary("oclock_glass")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @JvmStatic
    external fun setLatestVersion(version: Long)

    @JvmStatic
    external fun compute(
        packet: ByteArray,
        offset: Int,
        w: Int,
        h: Int,
        values: DoubleArray,
        version: Long
    ): Array<ByteArray>?

    /**
     * Ultra-fast direct Bitmap lock C++ field compute:
     * Reads [alphaBmp] (ARGB_8888 opaque R-channel mask), stores smooth SDF normal (G, B), and writes
     * (R=dispX, G=dispY, B=(depth+4)*7, A=255) directly into [outFieldBmp] without PNG encode/decode.
     */
    @JvmStatic
    external fun computeClockField(
        alphaBmp: Bitmap,
        outFieldBmp: Bitmap,
        ss: Double,
        ior: Double,
        thickness: Double,
        bezel: Double,
        surfaceKind: Int,
        version: Long
    ): Double

    /**
     * API 26-32 fallback: direct native C++ refraction + gradient tint + inner rim into [outBmp].
     */
    @JvmStatic
    external fun renderClockCpuFallback(
        alphaBmp: Bitmap,
        fieldBmp: Bitmap,
        wallBmp: Bitmap,
        outBmp: Bitmap,
        dstX: Float,
        dstY: Float,
        dstW: Float,
        dstH: Float,
        wallLeft: Float,
        wallTop: Float,
        wallScaleX: Float,
        wallScaleY: Float,
        maxAbsPx: Float,
        ss: Float,
        topColor: Int,
        botColor: Int,
        shadowOp: Float,
        shadowTopColor: Int,
        innerColor: Int,
        innerThick: Float,
        innerOp: Float,
        innerAngleDeg: Float
    ): Boolean

    /**
     * API 26-32 fallback: direct native C++ card Snell refraction + rim highlight into [outBmp].
     */
    @JvmStatic
    external fun renderCardCpuFallback(
        wallBmp: Bitmap,
        outBmp: Bitmap,
        cardX: Float,
        cardY: Float,
        cardW: Float,
        cardH: Float,
        radiusPx: Float,
        wallLeft: Float,
        wallTop: Float,
        wallScaleX: Float,
        wallScaleY: Float,
        bezelPx: Float,
        thickPx: Float,
        ior: Float,
        glassBgColor: Int,
        shadowInt: Float
    ): Boolean
}
