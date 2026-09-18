package family.seniorlink.data

import family.seniorlink.core.Kind
import java.time.LocalDate
import java.time.ZoneId

data class ActivityCount(val kind: Kind, val count: Int, val lastAt: Long)
data class ActivityDay(
    val source: String,
    val date: LocalDate,
    val counts: List<ActivityCount> = emptyList(),
    val records: List<StoredEvent> = emptyList(),
    val hasMore: Boolean = false,
    val firstDate: LocalDate = date,
    val lastDate: LocalDate = date,
    val wearableRecords: List<StoredEvent> = emptyList(),
    val batteryRecords: List<StoredEvent> = emptyList(),
)

/** Calendar boundaries, including 23/25-hour daylight-saving days. */
internal fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
    date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
