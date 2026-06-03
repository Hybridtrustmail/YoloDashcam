package com.dashcam

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

class FrameMotionAnalyzer(
    private val onMotion: () -> Unit
) : ImageAnalysis.Analyzer {

    private val cols = 20
    private val rows = 20
    private var previous: IntArray? = null

    var cellDelta = 22
    var changedFraction = 0.08f

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height

            val current = IntArray(cols * rows)
            for (r in 0 until rows) {
                val py = r * h / rows
                for (c in 0 until cols) {
                    val px = c * w / cols
                    val idx = py * rowStride + px * pixelStride
                    current[r * cols + c] =
                        if (idx in 0 until buffer.limit()) buffer.get(idx).toInt() and 0xFF else 0
                }
            }

            previous?.let { prev ->
                var changed = 0
                for (i in current.indices) {
                    if (abs(current[i] - prev[i]) > cellDelta) changed++
                }
                if (changed.toFloat() / current.size > changedFraction) {
                    onMotion()
                }
            }
            previous = current
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }
}
