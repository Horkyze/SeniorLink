package family.seniorlink

import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import family.seniorlink.core.*
import family.seniorlink.monitor.MonitorService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Explicitly selected isolated fixture; every reading and paired phone is synthetic. */
@RunWith(AndroidJUnit4::class)
class DailySummaryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dailyGroupsOpenPreviewFilteredHistoryAndKeepPhonesAndDaysSeparate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seniorlink.dashboardFixture") == "true")
        val app = compose.activity.application as SeniorApp
        val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(15_000) { model.screen.value.ready }
        assumeTrue(app.store.settings.role in listOf(Role.UNSET, Role.CAREGIVER) && app.store.peers().isEmpty())
        compose.runOnUiThread { MonitorService.pause(app) }
        if (app.store.settings.role == Role.UNSET) app.store.updateSettings(Settings(role = Role.CAREGIVER), app.publicId)
        val source = "e".repeat(64)
        val other = "f".repeat(64)
        val now = System.currentTimeMillis()
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = mutableListOf<Event>()
        fun add(event: Event) { events += event.copy(sequence = events.size + 1L) }
        add(Event(0, Kind.CHECK_IN, start - 60_000))
        add(Event(0, Kind.CHECK_IN, maxOf(start + 1, now - 900_000)))
        repeat(8) { index ->
            val at = maxOf(start + 1, now - (9 - index) * 120_000)
            val bpm = listOf(70.0, 73.0, 71.0, 75.0, 74.0, 76.0, 73.0, 72.0)[index]
            val metrics = listOf(
                WearableMetricSummary(WearableMetric.HEART_RATE, at, at, 1, bpm, bpm, bpm, bpm),
                WearableMetricSummary(WearableMetric.BATTERY, at, at, 1, 72.0, 72.0, 72.0, 72.0),
            )
            add(Event(0, Kind.WEARABLE, at, wearable = WearableSummary("Synthetic watch", metrics, deviceId = "synthetic-watch")))
        }
        repeat(5) { add(Event(0, Kind.LOCATION, maxOf(start + 1, now - (it + 1) * 900_000), latitude = 48.1, longitude = 17.1, accuracy = 45f)) }
        add(Event(0, Kind.PHONE_BATTERY, now - 60_000, phoneBattery = PhoneBattery(46, false)))
        repeat(120) { add(Event(0, Kind.UNLOCK, maxOf(start + 1, now - 600_000))) }
        try {
            app.store.addPeer(source, "Grandad", app.publicId)
            events.chunked(20).forEach { page -> app.store.commit(Batch(source = source, through = page.last().sequence,
                latest = events.size.toLong(), earliest = 1, events = page), now) }
            app.store.addPeer(other, "Grandma", app.publicId)
            app.store.commit(Batch(source = other, through = 3, latest = 3, earliest = 1,
                events = (1L..3L).map { Event(it, Kind.CHECK_IN, now - 60_000) }), now)
            compose.waitUntil(15_000) { model.activityBrowser.state.value.day?.counts?.sumOf { it.count } == 135 }
            compose.onNodeWithText("Daily summary").assertExists()
            compose.onNodeWithText("Recent updates").assertDoesNotExist()
            compose.onNodeWithText("46%").assertExists()
            screenshot("daily-summary-overview")
            compose.onNodeWithTag("digest-CHECK_IN").performScrollTo().assert(hasText("1 recorded", substring = true))
            compose.onNodeWithTag("digest-UNLOCK").assert(hasText("120 recorded", substring = true))
            screenshot("daily-summary-groups")
            compose.onNodeWithTag("digest-WEARABLE").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.records?.size == 4 }
            compose.onNodeWithText("8 recorded updates").assertExists()
            compose.onNodeWithText("Recorded heart rate").assertExists()
            screenshot("daily-summary-sheet")
            compose.onNodeWithTag("activity-records").performScrollToNode(hasText("View all 8 updates"))
            compose.onNodeWithText("View all 8 updates").performClick()
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.records?.size == 8 }
            compose.onNodeWithText("Activity history").assertExists()
            compose.onNodeWithText("Type: Wearable ▾").performClick()
            compose.onAllNodesWithText("Phone activity", substring = false).onLast().performClick()
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.records?.size == 40 }
            compose.onNodeWithTag("activity-records").performScrollToNode(hasText("Load earlier"))
            val lastVisible = model.activityBrowser.state.value.day!!.records.last().event.sequence
            compose.onNodeWithText("Load earlier").performClick()
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.records?.size == 80 }
            compose.onNodeWithTag("activity-$lastVisible").assertIsDisplayed()
            assertEquals(80, model.activityBrowser.state.value.day!!.records.map { it.event.sequence }.distinct().size)
            screenshot("daily-summary-history")
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("digest-LOCATION").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.records?.firstOrNull()?.event?.kind == Kind.LOCATION }
            val locationSequence = model.activityBrowser.state.value.day!!.records.first().event.sequence
            compose.onNodeWithTag("activity-$locationSequence").performClick()
            compose.onNodeWithTag("activity-records").performScrollToNode(hasText("Show on location map"))
            compose.onNodeWithText("Show on location map").performClick()
            compose.waitUntil(10_000) { model.screen.value.inspectedLocation?.event?.sequence == locationSequence }
            compose.onNodeWithTag("location-map").assertContentDescriptionContains("48.10000, 17.10000", substring = true)
            compose.onNodeWithTag("navigation-0").performClick()
            compose.onNodeWithTag("previous-day").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.date == LocalDate.now().minusDays(1) }
            compose.onNodeWithTag("digest-CHECK_IN").performScrollTo().assert(hasText("1 recorded", substring = true))
            compose.onNodeWithTag("digest-WEARABLE").assert(hasText("No recorded updates"))
            compose.onNodeWithText("Back to today").performScrollTo().performClick()
            compose.onNodeWithTag("summary-family").performScrollTo().performClick()
            compose.onNodeWithText("Grandma", substring = false).performClick()
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.source == other }
            compose.onNodeWithText("46%").assertDoesNotExist()
            compose.onNodeWithTag("digest-CHECK_IN").performScrollTo().assert(hasText("3 recorded", substring = true))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("summary-family").performScrollTo().assert(hasText("Grandma", substring = true))
            app.store.removePeer(other)
            compose.waitUntil(10_000) { model.activityBrowser.state.value.day?.source == source }
            compose.onNodeWithTag("summary-family").assert(hasText("Grandad", substring = true))
        } finally {
            compose.runOnUiThread { model.activityBrowser.close(); MonitorService.pause(app) }
            app.store.removePeer(source)
            app.store.removePeer(other)
        }
    }

    private fun screenshot(name: String) {
        if (Build.VERSION.SDK_INT < 29) return
        compose.waitForIdle()
        // Render this synthetic fixture's own view tree; production window privacy stays on.
        compose.runOnUiThread {
            val target = WindowInspector.getGlobalWindowViews().last()
            val params = target.layoutParams as WindowManager.LayoutParams
            assertTrue("Dashboard and detail windows must protect screenshots",
                params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            target.draw(android.graphics.Canvas(bitmap))
            File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
