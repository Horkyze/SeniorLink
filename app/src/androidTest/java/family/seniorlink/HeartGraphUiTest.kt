package family.seniorlink

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import family.seniorlink.dashboard.ReadingGraph
import family.seniorlink.dashboard.ChartPoint
import family.seniorlink.ui.Calm
import family.seniorlink.ui.CalmTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class HeartGraphUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val start = 1_800_000_000_000L
    private val end = start + 3_600_000
    private val points = (0..30).map { ChartPoint(start + it * 120_000, 60.0 + it, it == 0 || it == 15) }
    private val graph get() = compose.onNodeWithTag("heart-graph")
    private fun description() = graph.fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun tapAndDragInspectRealValuesWithExactTimes() {
        show()
        graph.performTouchInput { click(Offset(width * 0.5f, height * 0.5f)) }
        val time = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(points[15].at))
        compose.onNodeWithTag("heart-graph-selection").assertTextEquals("75 bpm · $time")
        graph.performTouchInput { swipe(Offset(width * 0.5f, height * 0.5f), Offset(width * 0.9f, height * 0.5f), 500) }
        compose.onNodeWithTag("heart-graph-selection").assert(hasText("87 bpm", substring = true))
        screenshot("heart-graph-scrub")
    }

    @Test fun pinchPanAndResetChangeVisibleTimeWithoutLosingSavedData() {
        show()
        val original = description()
        graph.performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.5f))
            down(1, Offset(width * 0.6f, height * 0.5f))
            for (step in 1..12) {
                moveTo(0, Offset(width * (0.4f - step * 0.02f), height * 0.5f), delayMillis = 16)
                moveTo(1, Offset(width * (0.6f + step * 0.02f), height * 0.5f), delayMillis = 16)
            }
            up(0); up(1)
        }
        val zoomed = description()
        assertNotEquals(original, zoomed)
        assertFalse(zoomed.contains("Selected:"))
        graph.performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.4f))
            down(1, Offset(width * 0.7f, height * 0.6f))
            for (step in 1..8) {
                moveTo(0, Offset(width * (0.4f - step * 0.025f), height * 0.4f), delayMillis = 16)
                moveTo(1, Offset(width * (0.7f - step * 0.025f), height * 0.6f), delayMillis = 16)
            }
            up(0); up(1)
        }
        assertNotEquals(zoomed, description())
        graph.performTouchInput { click(center) }
        screenshot("heart-graph-zoom")
        compose.onNodeWithText("Reset chart").performClick()
        assertEquals(original, description())
        compose.onNodeWithTag("heart-graph-selection").assertTextEquals("Touch the chart to inspect a reading")
    }

    @Test fun verticalDragStillScrollsParentAndRemovedReadingsClearSelection() {
        val data = mutableStateOf(points)
        show(data)
        graph.performTouchInput { click(center) }
        compose.runOnIdle { data.value = points.filterNot { it.at == points[15].at } }
        compose.onNodeWithTag("heart-graph-selection").assertTextEquals("Touch the chart to inspect a reading")
        val before = graph.fetchSemanticsNode().boundsInRoot.top
        graph.performTouchInput { swipe(Offset(width * 0.5f, height * 0.85f), Offset(width * 0.5f, height * 0.1f), 500) }
        assertTrue(graph.fetchSemanticsNode().boundsInRoot.top < before)
    }

    private fun show(data: State<List<ChartPoint>> = mutableStateOf(points)) {
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(15_000) { model.screen.value.ready }
        compose.runOnUiThread {
            compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            compose.activity.setContent {
                CalmTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recorded heart rate")
                        ReadingGraph(data.value, start, end, Calm.Chart, height = 180, interactive = true)
                        Spacer(Modifier.height(1000.dp))
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
