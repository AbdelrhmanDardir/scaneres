package com.example.docscanner.cv

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Loads the native OpenCV library once per process. */
object OpenCv {
    val isReady: Boolean by lazy { OpenCVLoader.initLocal() }

    fun require() {
        check(isReady) { "تعذر تحميل مكتبة OpenCV على هذا الجهاز" }
    }
}

/**
 * OpenCV Mats live in native memory (the garbage collector can't see them).
 * Every Mat created through a scope is released when the scope is closed.
 */
internal class MatScope : AutoCloseable {
    private val mats = ArrayList<Mat>()

    fun mat(): Mat = Mat().also { mats.add(it) }

    fun <T : Mat> track(mat: T): T {
        mats.add(mat)
        return mat
    }

    override fun close() {
        mats.forEach { it.release() }
        mats.clear()
    }
}

/** Converts a Mat (1, 3 or 4 channels, 8-bit) to an ARGB_8888 Bitmap. */
internal fun Mat.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(this, bitmap)
    return bitmap
}
