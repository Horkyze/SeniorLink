package family.seniorlink.dashboard

import family.seniorlink.ScreenState
import family.seniorlink.core.Role
import family.seniorlink.core.WearableMetric
import family.seniorlink.data.StoredEvent
import family.seniorlink.wearable.LiveWearableValue
import family.seniorlink.wearable.WearableState

internal data class HeartPoint(val at: Long, val bpm: Double, val breakBefore: Boolean)
internal data class WearableSnapshot(
    val name: String,
    val events: List<StoredEvent>,
    val values: List<LiveWearableValue>,
    val points: List<HeartPoint>,
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
    val candidates = if (sharer && allowed && state.settings.wearable && live.latest.isNotEmpty()) live.latest
        else latest.values.map { LiveWearableValue(it.metric, it.latest, it.lastAt) }
    val lostContactAt = candidates.firstOrNull { it.metric == WearableMetric.CONTACT && it.value == 0.0 }?.receivedAt
    val values = candidates.filterNot { lostContactAt != null && it.receivedAt <= lostContactAt &&
        it.metric in setOf(WearableMetric.HEART_RATE, WearableMetric.RR_INTERVAL) }
    // A summary does not retain the exact contact transition; break across its whole interval.
    val contactLosses = metrics.filter { it.metric == WearableMetric.CONTACT && it.minimum == 0.0 }
    val pulses = metrics.filter { it.metric == WearableMetric.HEART_RATE }.map { it.lastAt to it.latest }
        .distinctBy { it.first }.sortedBy { it.first }
    val points = pulses.mapIndexed { index, (at, bpm) ->
        val previous = pulses.getOrNull(index - 1)?.first
        HeartPoint(at, bpm, previous == null || at - previous > 300_000 ||
            contactLosses.any { it.lastAt >= previous && it.firstAt <= at })
    }
    return WearableSnapshot(
        if (sharer) state.settings.wearableName.ifBlank { "Smartwatch" }
        else deviceEvents.firstOrNull()?.event?.wearable?.deviceName ?: "Smartwatch",
        deviceEvents, values, points,
    )
}
