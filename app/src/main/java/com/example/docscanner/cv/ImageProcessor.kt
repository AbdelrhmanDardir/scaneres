package com.example.docscanner.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanFilter(val label: String) {
    ORIGINAL("الأصلي"),
    MAGIC_COLOR("ألوان سحرية"),
    BLACK_WHITE("أبيض وأسود"),
}

object ImageProcessor {

    private const val MIN_SIDE = 64
    private const val MAX_OUTPUT_SIDE = 3200

    /**
     * Perspective transformation (warp): stretches the quadrilateral given by [corners]
     * into a perfect, flat rectangle.
     */
    fun warp(source: Bitmap, corners: List<Point>): Bitmap {
        OpenCv.require()
        val (topLeft, topRight, bottomRight, bottomLeft) = DocumentDetector.orderCorners(corners)

        // Output size = the longer of each pair of opposite sides, so no detail is squeezed.
        var width = max(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
        var height = max(distance(topLeft, bottomLeft), distance(topRight, bottomRight))
        val longest = max(width, height)
        if (longest > MAX_OUTPUT_SIDE) {
            val shrink = MAX_OUTPUT_SIDE / longest
            width *= shrink
            height *= shrink
        }
        val outWidth = width.roundToInt().coerceAtLeast(MIN_SIDE)
        val outHeight = height.roundToInt().coerceAtLeast(MIN_SIDE)

        return MatScope().use { scope ->
            val src = scope.mat()
            Utils.bitmapToMat(source, src)

            val from = scope.track(MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft))
            val to = scope.track(
                MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point(outWidth - 1.0, 0.0),
                    Point(outWidth - 1.0, outHeight - 1.0),
                    Point(0.0, outHeight - 1.0),
                )
            )
            val transform = scope.track(Imgproc.getPerspectiveTransform(from, to))

            val warped = scope.mat()
            Imgproc.warpPerspective(
                src, warped, transform,
                Size(outWidth.toDouble(), outHeight.toDouble()),
                Imgproc.INTER_CUBIC,
            )
            warped.toBitmap()
        }
    }

    /** Applies one of the three filters to an already flattened document. */
    fun applyFilter(flattened: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.ORIGINAL) return flattened
        OpenCv.require()
        return MatScope().use { scope ->
            val rgba = scope.mat()
            Utils.bitmapToMat(flattened, rgba)
            val result = when (filter) {
                ScanFilter.MAGIC_COLOR -> magicColor(scope, rgba)
                ScanFilter.BLACK_WHITE -> blackAndWhite(scope, rgba)
                ScanFilter.ORIGINAL -> rgba
            }
            result.toBitmap()
        }
    }

    /**
     * Magic Color: removes shadows / uneven lighting, whitens the paper,
     * boosts contrast and sharpens the text.
     */
    private fun magicColor(scope: MatScope, rgba: Mat): Mat {
        val rgb = scope.mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        val evenLight = normalizeIllumination(scope, rgb)

        // Contrast + brightness: paper becomes pure white, ink gets deeper.
        val contrasted = scope.mat()
        evenLight.convertTo(contrasted, -1, 1.18, -18.0)

        // Unsharp mask: sharpness.
        val blurred = scope.mat()
        Imgproc.GaussianBlur(contrasted, blurred, Size(0.0, 0.0), 2.0)
        val sharpened = scope.mat()
        Core.addWeighted(contrasted, 1.6, blurred, -0.6, 0.0, sharpened)
        return sharpened
    }

    /**
     * Black & White: even out the lighting, then adaptive thresholding →
     * pure white background and pure black text, like a photocopy.
     */
    private fun blackAndWhite(scope: MatScope, rgba: Mat): Mat {
        val gray = scope.mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

        val evenLight = normalizeIllumination(scope, gray)
        val smooth = scope.mat()
        Imgproc.GaussianBlur(evenLight, smooth, Size(3.0, 3.0), 0.0)

        val blockSize = oddBlockSize(min(gray.cols(), gray.rows()) / 25)
        val result = scope.mat()
        Imgproc.adaptiveThreshold(
            smooth, result, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
            blockSize, 14.0,
        )
        return result
    }

    /**
     * Estimates the lighting of the page (a "background" image without the text) and divides it out.
     * Works for 1-channel and 3-channel 8-bit images.
     */
    private fun normalizeIllumination(scope: MatScope, src: Mat): Mat {
        val factor = 0.25   // work on a quarter-size copy: much faster, and lighting is smooth anyway
        val small = scope.mat()
        Imgproc.resize(src, small, Size(), factor, factor, Imgproc.INTER_AREA)

        val kernel = scope.track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
        val dilated = scope.mat()
        Imgproc.dilate(small, dilated, kernel)            // erases dark text, keeps the paper colour
        val smoothBackground = scope.mat()
        Imgproc.medianBlur(dilated, smoothBackground, 21)

        val background = scope.mat()
        Imgproc.resize(smoothBackground, background, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val result = scope.mat()
        Core.divide(src, background, result, 255.0)
        return result
    }

    private fun oddBlockSize(value: Int): Int {
        val clamped = value.coerceIn(15, 101)
        return if (clamped % 2 == 0) clamped + 1 else clamped
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
}
