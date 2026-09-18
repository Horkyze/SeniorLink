package family.seniorlink.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import family.seniorlink.formatTime
import family.seniorlink.ui.Calm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal enum class ChartMetric(val title: String, val tag: String, val unit: String) {
    HEART_RATE("Heart-rate", "heart-graph", "bpm"),
    PHONE_BATTERY("Phone-battery", "battery-graph", "%"),
}

@Composable
internal fun ReadingGraph(
    points: List<ChartPoint>, start: Long, end: Long, accent: Color,
    height: Int = 124, labels: Boolean = true, interactive: Boolean = false,
    metric: ChartMetric = ChartMetric.HEART_RATE,
) {
    if (points.isEmpty()) return
    val bounds = HeartGraphWindow(start, maxOf(start + 1, end))
    var zoomedWindow by remember { mutableStateOf<HeartGraphWindow?>(null) }
    var selectedAt by remember { mutableStateOf<Long?>(null) }
    val window = zoomedWindow?.constrainedTo(bounds) ?: bounds
    val visible = points.filter { it.at in window.start..window.end }
    val selected = visible.firstOrNull { it.at == selectedAt }
    // Keep the vertical scale stable while moving through time.
    val low = if (metric == ChartMetric.PHONE_BATTERY) 0.0 else (floor(points.minOf { it.value } / 10) * 10 - 10).coerceAtLeast(0.0)
    val high = if (metric == ChartMetric.PHONE_BATTERY) 100.0 else maxOf(ceil(points.maxOf { it.value } / 10) * 10, low + 20)
    val grid = Calm.Line
    val exactTime = remember { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault()) }
    val selectionText = selected?.let { "${it.value.roundToInt()}${if (metric == ChartMetric.PHONE_BATTERY) "%" else " bpm"} · ${exactTime.format(Instant.ofEpochMilli(it.at))}" +
        (it.detail?.let { detail -> " · $detail" } ?: "") }
    val description = "${metric.title} graph, ${points.size} readings. " +
        "${formatTime(points.first().at)} to ${formatTime(points.last().at)}. " +
        "Lowest ${points.minOf { it.value }.roundToInt()}, highest ${points.maxOf { it.value }.roundToInt()} ${metric.unit}."
    val select by rememberUpdatedState<(Float) -> Unit>({ fraction -> selectedAt = window.nearest(points, fraction)?.at })
    val transform by rememberUpdatedState<(Float, Float, Float) -> Unit>({ zoom, anchor, pan ->
        val next = (zoomedWindow?.constrainedTo(bounds) ?: bounds).transform(bounds, zoom, anchor, pan)
        zoomedWindow = next.takeUnless { it == bounds }
        selectedAt = null
    })
    val gestures = if (!interactive) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val inset = 4.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            fun fraction(x: Float) = ((x - inset) / width).coerceIn(0f, 1f)
            select(fraction(down.position.x))
            var scrubbing = false
            var transforming = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    transforming = true
                    transform(event.calculateZoom(), fraction(event.calculateCentroid(useCurrent = false).x), event.calculatePan().x / width)
                    event.changes.forEach { it.consume() }
                } else if (!transforming) {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - down.position
                    if (!scrubbing && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                    if (abs(delta.x) > viewConfiguration.touchSlop) scrubbing = true
                    if (scrubbing || !change.pressed) {
                        select(fraction(change.position.x))
                        change.consume()
                    }
                } else {
                    // Lifting one finger after a pinch must not turn into a scrub.
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    if (interactive) {
        Text(selectionText ?: if (visible.isEmpty()) "No readings in this view" else "Touch the chart to inspect a reading",
            Modifier.testTag("${metric.tag}-selection"), style = MaterialTheme.typography.bodySmall)
    }
    val minimumHeight = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() * 3 } + 12.dp
    Row(Modifier.fillMaxWidth().height(maxOf(height.dp, minimumHeight)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(high.toInt().toString(), style = MaterialTheme.typography.labelSmall)
            Text(((high + low) / 2).toInt().toString(), style = MaterialTheme.typography.labelSmall)
            Text(low.toInt().toString(), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp).testTag(metric.tag).then(gestures).semantics {
            contentDescription = description
            if (interactive) {
                stateDescription = "Visible time: ${exactTime.format(Instant.ofEpochMilli(window.start))} to ${exactTime.format(Instant.ofEpochMilli(window.end))}" +
                    (selectionText?.let { ". Selected: $it" } ?: "")
                customActions = listOf(
                    CustomAccessibilityAction("Zoom in") { transform(2f, 0.5f, 0f); true },
                    CustomAccessibilityAction("Zoom out") { transform(0.5f, 0.5f, 0f); true },
                    CustomAccessibilityAction("Earlier time") { transform(1f, 0.5f, 0.5f); true },
                    CustomAccessibilityAction("Later time") { transform(1f, 0.5f, -0.5f); true },
                    CustomAccessibilityAction("Previous reading") {
                        (visible.lastOrNull { it.at < (selectedAt ?: Long.MAX_VALUE) })?.let { selectedAt = it.at } != null
                    },
                    CustomAccessibilityAction("Next reading") {
                        (visible.firstOrNull { it.at > (selectedAt ?: Long.MIN_VALUE) })?.let { selectedAt = it.at } != null
                    },
                )
            }
        }) {
            val inset = 4.dp.toPx()
            fun position(p: ChartPoint) = Offset(
                inset + ((p.at - window.start).toDouble() / window.duration * (size.width - inset * 2)).toFloat(),
                inset + ((high - p.value) / (high - low) * (size.height - inset * 2)).toFloat(),
            )
            listOf(inset, size.height / 2, size.height - inset).forEach { y ->
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            clipRect(left = inset, right = size.width - inset) {
                // Include adjacent off-screen points so lines reach the viewport edge;
                // each uninterrupted run still preserves missing-data gaps.
                val runs = mutableListOf<MutableList<Offset>>()
                points.forEach { point ->
                    if (runs.isEmpty() || point.breakBefore) runs += mutableListOf<Offset>()
                    runs.last() += position(point)
                }
                runs.filter { it.size > 1 }.forEach { run ->
                    val area = Path().apply {
                        moveTo(run.first().x, size.height - inset)
                        run.forEach { lineTo(it.x, it.y) }
                        lineTo(run.last().x, size.height - inset)
                        close()
                    }
                    drawPath(area, accent.copy(alpha = 0.07f))
                }
                points.forEachIndexed { index, point ->
                    val at = position(point)
                    if (index > 0 && !point.breakBefore) drawLine(accent, position(points[index - 1]), at, 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(accent, if (visible.size < 60) 2.5.dp.toPx() else 1.5.dp.toPx(), at)
                }
                selected?.let {
                    val at = position(it)
                    drawLine(accent, Offset(at.x, 0f), Offset(at.x, size.height), 1.5.dp.toPx(), StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 5.dp.toPx())))
                    drawCircle(Color.White, 6.dp.toPx(), at)
                    drawCircle(accent, 4.dp.toPx(), at)
                }
            }
        }
    }
    if (labels) {
        val fullDay = window.duration >= 86_400_000
        val formatter = remember(fullDay) {
            DateTimeFormatter.ofPattern(if (fullDay) "d MMM\nHH:mm" else "HH:mm").withZone(ZoneId.systemDefault())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatter.format(Instant.ofEpochMilli(window.start)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("Time on phone", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text(formatter.format(Instant.ofEpochMilli(window.end)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
    }
    if (interactive) {
        Text("Pinch to zoom · Move with two fingers · Drag to inspect", style = MaterialTheme.typography.bodySmall, color = Calm.Muted)
        TextButton(onClick = { zoomedWindow = null; selectedAt = null }, enabled = zoomedWindow != null || selectedAt != null) {
            Text("Reset chart")
        }
    }
}
