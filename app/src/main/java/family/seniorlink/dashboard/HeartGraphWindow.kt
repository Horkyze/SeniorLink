package family.seniorlink.dashboard

import kotlin.math.abs
import kotlin.math.roundToLong

/** Absolute times keep a zoomed window still when new readings arrive. */
internal data class HeartGraphWindow(val start: Long, val end: Long) {
    val duration: Long get() = end - start

    fun constrainedTo(bounds: HeartGraphWindow): HeartGraphWindow {
        val span = duration.coerceIn(1L, bounds.duration)
        val left = start.coerceIn(bounds.start, bounds.end - span)
        return HeartGraphWindow(left, left + span)
    }

    fun transform(bounds: HeartGraphWindow, zoom: Float, anchor: Float, pan: Float): HeartGraphWindow {
        if (!zoom.isFinite() || zoom <= 0 || !anchor.isFinite() || !pan.isFinite()) return this
        val span = (duration / zoom.toDouble()).roundToLong().coerceIn(minOf(60_000L, bounds.duration), bounds.duration)
        val left = (start + duration * anchor.toDouble() - span * (anchor + pan).toDouble()).roundToLong()
        return HeartGraphWindow(left, left + span).constrainedTo(bounds)
    }

    fun nearest(points: List<ChartPoint>, fraction: Float): ChartPoint? {
        val time = start + duration * fraction.coerceIn(0f, 1f).toDouble()
        return points.filter { it.at in start..end }.minByOrNull { abs(it.at - time) }
    }
}
