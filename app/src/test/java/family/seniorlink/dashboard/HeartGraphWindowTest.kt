package family.seniorlink.dashboard

import org.junit.Assert.*
import org.junit.Test

class HeartGraphWindowTest {
    private val bounds = HeartGraphWindow(1_000_000, 4_600_000)

    @Test fun zoomKeepsTimeUnderFingersAndPanMovesThroughHistory() {
        val zoomed = bounds.transform(bounds, 2f, 0.25f, 0f)
        assertEquals(1_900_000L, zoomed.start + zoomed.duration / 4)
        assertEquals(1_800_000L, zoomed.duration)
        val panned = zoomed.transform(bounds, 1f, 0.5f, -0.25f)
        assertEquals(zoomed.start + 450_000, panned.start)
        assertEquals(zoomed.duration, panned.duration)
    }

    @Test fun zoomAndPanStopAtBoundsAndOneMinuteLimit() {
        val close = bounds.transform(bounds, 1000f, 0.5f, 0f)
        assertEquals(60_000L, close.duration)
        assertEquals(bounds.start, close.transform(bounds, 1f, 0.5f, 100f).start)
        assertEquals(bounds.end, close.transform(bounds, 1f, 0.5f, -100f).end)
        assertEquals(bounds, close.transform(bounds, 0.001f, 0.5f, 0f))
        val short = HeartGraphWindow(0, 10_000)
        assertEquals(short, short.transform(short, 20f, 0.5f, 0f))
    }

    @Test fun selectionUsesActualSavedReadingsAndNeverInventsValueInGap() {
        val points = listOf(ChartPoint(1_000_000, 60.0, true), ChartPoint(4_600_000, 90.0, true))
        assertEquals(points.first(), bounds.nearest(points, 0.1f))
        assertEquals(points.last(), bounds.nearest(points, 0.9f))
        assertNull(HeartGraphWindow(2_000_000, 3_000_000).nearest(points, 0.5f))
        assertEquals(points.last(), bounds.nearest(points, 2f))
    }

    @Test fun timeWindowStaysStillOnRefreshAndClampsWhenDataRangeShrinks() {
        val zoomed = bounds.transform(bounds, 2f, 0.5f, 0f)
        assertEquals(zoomed, zoomed.constrainedTo(HeartGraphWindow(bounds.start + 30_000, bounds.end + 30_000)))
        val shorter = HeartGraphWindow(2_000_000, 2_100_000)
        assertEquals(shorter, zoomed.constrainedTo(shorter))
        assertEquals(bounds, bounds.transform(bounds, Float.NaN, 0.5f, 0f))
    }
}
