package com.example.docscanner.cv

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * @param corners Four corners in the bitmap's own pixel coordinates, ordered TL, TR, BR, BL.
 * @param found   true when a real document outline was detected; false when we fell back to a default
 *                rectangle (the user then has to adjust the corners by hand).
 */
data class DetectionResult(val corners: List<Point>, val found: Boolean)

/** Edge detection (Canny) + contour detection to find the 4 corners of a document. */
object DocumentDetector {

    private const val DETECT_MAX_SIDE = 700.0   // detection runs on a small copy: faster and less noisy
    private const val MIN_AREA_RATIO = 0.12     // the document must cover at least 12% of the photo
    private const val MAX_AREA_RATIO = 0.97     // ... but not be the whole frame (usually a false hit)
    private const val DEFAULT_MARGIN = 0.08
    private val EPSILON_FACTORS = doubleArrayOf(0.02, 0.03, 0.045)

    private class Candidate(val points: List<Point>, val area: Double)

    fun detect(bitmap: Bitmap): DetectionResult {
        OpenCv.require()
        return MatScope().use { scope ->
            val rgba = scope.mat()
            Utils.bitmapToMat(bitmap, rgba)

            val longest = max(rgba.cols(), rgba.rows()).toDouble()
            val scale = min(1.0, DETECT_MAX_SIDE / longest)
            val small = scope.mat()
            if (scale < 1.0) {
                Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                rgba.copyTo(small)
            }

            val quad = findQuad(scope, small)
            if (quad != null) {
                val scaledUp = quad.map { Point(it.x / scale, it.y / scale) }
                DetectionResult(orderCorners(scaledUp), found = true)
            } else {
                DetectionResult(defaultCorners(bitmap.width, bitmap.height), found = false)
            }
        }
    }

    /** A rectangle inset from the photo edges: the starting point when detection fails. */
    fun defaultCorners(width: Int, height: Int): List<Point> {
        val mx = width * DEFAULT_MARGIN
        val my = height * DEFAULT_MARGIN
        return listOf(
            Point(mx, my),
            Point(width - mx, my),
            Point(width - mx, height - my),
            Point(mx, height - my),
        )
    }

    /** Orders 4 points as top-left, top-right, bottom-right, bottom-left. */
    fun orderCorners(points: List<Point>): List<Point> {
        require(points.size == 4) { "Exactly 4 corners are required" }
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.minBy { it.y - it.x }
        val bottomLeft = points.maxBy { it.y - it.x }
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun findQuad(scope: MatScope, rgba: Mat): List<Point>? {
        val gray = scope.mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val imageArea = rgba.cols().toDouble() * rgba.rows()
        val kernel = scope.track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val anchor = Point(-1.0, -1.0)

        // Otsu gives us a threshold that adapts to the photo; Canny thresholds are derived from it.
        val otsuMask = scope.mat()
        val otsu = Imgproc.threshold(gray, otsuMask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

        // Strategy 1: Canny edges, thickened so broken edges join up.
        val edges = scope.mat()
        Imgproc.Canny(gray, edges, otsu * 0.5, otsu)
        Imgproc.dilate(edges, edges, kernel, anchor, 2)
        val fromEdges = bestQuad(scope, edges, imageArea)

        // Strategy 2: bright paper on a darker background.
        val closed = scope.mat()
        Imgproc.morphologyEx(otsuMask, closed, Imgproc.MORPH_CLOSE, kernel, anchor, 3)
        val fromMask = bestQuad(scope, closed, imageArea)

        return larger(fromEdges, fromMask)?.points
    }

    private fun larger(a: Candidate?, b: Candidate?): Candidate? = when {
        a == null -> b
        b == null -> a
        else -> if (b.area > a.area) b else a
    }

    /** The biggest convex 4-sided contour in a binary image, or null. */
    private fun bestQuad(scope: MatScope, binary: Mat, imageArea: Double): Candidate? {
        val work = scope.mat()
        binary.copyTo(work)
        val hierarchy = scope.mat()
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        try {
            val ranked = contours
                .map { it to Imgproc.contourArea(it) }
                .sortedByDescending { it.second }
                .take(10)

            for ((contour, area) in ranked) {
                if (area < imageArea * MIN_AREA_RATIO) break
                if (area > imageArea * MAX_AREA_RATIO) continue

                val curve = scope.track(MatOfPoint2f(*contour.toArray()))
                val perimeter = Imgproc.arcLength(curve, true)
                for (factor in EPSILON_FACTORS) {
                    val approx = scope.track(MatOfPoint2f())
                    Imgproc.approxPolyDP(curve, approx, factor * perimeter, true)
                    val points = approx.toArray()
                    if (points.size == 4 && Imgproc.isContourConvex(scope.track(MatOfPoint(*points)))) {
                        return Candidate(points.toList(), area)
                    }
                }
            }
            return null
        } finally {
            contours.forEach { it.release() }
        }
    }
}
