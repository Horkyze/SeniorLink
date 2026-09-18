package family.seniorlink.dashboard

import family.seniorlink.ScreenState
import family.seniorlink.core.Role
import family.seniorlink.core.WearableMetric
import family.seniorlink.data.StoredEvent
import family.seniorlink.wearable.LiveWearableValue
import family.seniorlink.wearable.WearableState

internal data class ChartPoint(val at: Long, val value: Double, val breakBefore: Boolean, val detail: String? = null)
internal data class WearableSnapshot(
    val name: String,
    val events: List<StoredEvent>,
    val values: List<LiveWearableValue>,
    val points: List<ChartPoint>,
)

/** Scope every value and graph to an approved phone and its current selected device. */
internal fun wearableSnapshot(state: ScreenState, live: WearableState, source: String?): WearableSnapshot {
    val sharer = state.settings.role == Role.SHARER
    val allowed = if (sharer) source == state.publicId else state.peers.any { it.id == source }
    val events = state.wearables.filter { allowed && it.source == source }
        .sortedWith(compareByDescending<StoredEvent> { it.event.occurredAt }.thenByDescending { it.event.sequence })
    val deviceId = if (sharer) state.settings.wearableId else events.firstOrNull()?.event?.wearable?.deviceId
    val deviceEvents = events.filter { it.event.wearable?.deviceId == deviceId }
    val metrics = deviceEvents.flatMap { it.event.wearable?.metrics.orEmpty() }
    val latest = metrics.groupBy { it.metric }.mapValues { (_, values) -> values.maxBy { it.lastAt } }
    val saved = latest.values.map { LiveWearableValue(it.metric, it.latest, it.lastAt) }
    // A reconnect may supply heart rate before battery. Keep other saved metrics
    // with their original timestamps instead of replacing the entire snapshot.
    val candidates = if (sharer && allowed && state.settings.wearable)
        (saved + live.latest).groupBy { it.metric }.values.map { readings -> readings.maxBy { it.receivedAt } }
        else saved
    val lostContactAt = candidates.firstOrNull { it.metric == WearableMetric.CONTACT && it.value == 0.0 }?.receivedAt
    val values = candidates.filterNot { lostContactAt != null && it.receivedAt <= lostContactAt &&
        it.metric in setOf(WearableMetric.HEART_RATE, WearableMetric.RR_INTERVAL) }
    // A summary does not retain the exact contact transition; break across its whole interval.
    val contactLosses = metrics.filter { it.metric == WearableMetric.CONTACT && it.minimum == 0.0 }
    // Include the same actual latest reading shown in the headline, even before
    // its two-minute summary is persisted. Caregivers still use saved data only.
    val pulse = values.firstOrNull { it.metric == WearableMetric.HEART_RATE }
    val pulses = (metrics.filter { it.metric == WearableMetric.HEART_RATE }.map { it.lastAt to it.latest } +
        listOfNotNull(pulse?.let { it.receivedAt to it.value }))
        .distinctBy { it.first }.sortedBy { it.first }
    val points = pulses.mapIndexed { index, (at, bpm) ->
        val previous = pulses.getOrNull(index - 1)?.first
        ChartPoint(at, bpm, previous == null || at - previous > 300_000 ||
            contactLosses.any { it.lastAt >= previous && it.firstAt <= at } ||
            (lostContactAt != null && lostContactAt in previous..at))
    }
    return WearableSnapshot(
        if (sharer) state.settings.wearableName.ifBlank { "Smartwatch" }
        else deviceEvents.firstOrNull()?.event?.wearable?.deviceName ?: "Smartwatch",
        deviceEvents, values, points,
    )
}

internal fun phoneBatteryEvents(state: ScreenState, source: String?): List<StoredEvent> {
    val allowed = if (state.settings.role == Role.SHARER) source == state.publicId else state.peers.any { it.id == source }
    return state.phoneBatteries.filter { allowed && it.source == source && it.event.phoneBattery != null }
        .sortedWith(compareBy<StoredEvent> { it.event.occurredAt }.thenBy { it.event.sequence })
        .groupBy { it.event.occurredAt }.values.map { it.last() }
}

internal fun chargingLabel(charging: Boolean?): String = when (charging) {
    true -> "Charging"; false -> "Not charging"; null -> "Charging state unavailable"
}

internal fun batteryPoints(events: List<StoredEvent>): List<ChartPoint> = events.mapIndexed { index, stored ->
    val event = stored.event
    val battery = requireNotNull(event.phoneBattery)
    ChartPoint(event.occurredAt, battery.percent.toDouble(),
        index == 0 || event.occurredAt - events[index - 1].event.occurredAt > 600_000,
        chargingLabel(battery.charging))
}
