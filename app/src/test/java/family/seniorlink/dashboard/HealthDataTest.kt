package family.seniorlink.dashboard

import family.seniorlink.ScreenState
import family.seniorlink.core.*
import family.seniorlink.data.StoredEvent
import family.seniorlink.wearable.*
import org.junit.Assert.*
import org.junit.Test

class HealthDataTest {
    private val source = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun event(seq: Long, at: Long, value: Double, metric: WearableMetric = WearableMetric.HEART_RATE,
                      device: String = "watch", phone: String = source) = StoredEvent(phone, Event(seq, Kind.WEARABLE, at,
        wearable = WearableSummary("Watch", listOf(WearableMetricSummary(metric, at, at, 1, value, value, value, value)), deviceId = device)))
    private fun state(vararg events: StoredEvent) = ScreenState(settings = Settings(role = Role.CAREGIVER),
        peers = listOf(Peer(source, "Grandad"), Peer(other, "Grandma")), wearables = events.toList())

    @Test fun graphSortsActualTimestampsAndBreaksAtMissingDataAndContactLoss() {
        val state = state(event(5, 900_000, 85.0), event(1, 100_000, 70.0), event(2, 220_000, 75.0),
            event(3, 230_000, 0.0, WearableMetric.CONTACT), event(4, 340_000, 80.0))
        val snapshot = wearableSnapshot(state, WearableState(), source)
        assertEquals(listOf(100_000L, 220_000L, 340_000L, 900_000L), snapshot.points.map { it.at })
        assertEquals(listOf(true, false, true, true), snapshot.points.map { it.breakBefore })
    }

    @Test fun summarizedContactLossBreaksEveryOverlappingLine() {
        val contact = event(3, 500_000, 1.0, WearableMetric.CONTACT)
        val summary = contact.event.wearable!!
        val changed = contact.copy(event = contact.event.copy(wearable = summary.copy(metrics = listOf(
            WearableMetricSummary(WearableMetric.CONTACT, 200_000, 500_000, 2, 0.0, 1.0, 0.5, 1.0),
        ))))
        val snapshot = wearableSnapshot(state(event(1, 100_000, 70.0), event(2, 300_000, 72.0), changed), WearableState(), source)
        assertTrue(snapshot.points.last().breakBefore)
    }

    @Test fun replacementWatchAndOtherFamilyPhonesNeverContributeOldPulseOrBattery() {
        val state = state(event(1, 100_000, 70.0), event(2, 200_000, 90.0, WearableMetric.BATTERY, device = "replacement"),
            event(3, 300_000, 82.0, phone = other))
        val snapshot = wearableSnapshot(state, WearableState(), source)
        assertTrue(snapshot.points.isEmpty())
        assertEquals(listOf(WearableMetric.BATTERY), snapshot.values.map { it.metric })
        assertTrue(wearableSnapshot(state.copy(peers = emptyList()), WearableState(), source).values.isEmpty())
    }

    @Test fun contactLossRemovesCurrentPulseButKeepsTimestampedHistory() {
        val snapshot = wearableSnapshot(state(event(1, 100_000, 70.0), event(2, 120_000, 0.0, WearableMetric.CONTACT)), WearableState(), source)
        assertFalse(snapshot.values.any { it.metric == WearableMetric.HEART_RATE })
        assertEquals(70.0, snapshot.points.single().bpm, 0.0)
    }

    @Test fun caregiverNeverUsesLocalLiveReadings() {
        val live = WearableState(latest = listOf(LiveWearableValue(WearableMetric.BATTERY, 99.0, 100_000)))
        assertTrue(wearableSnapshot(state(), live, source).values.isEmpty())
    }
}
