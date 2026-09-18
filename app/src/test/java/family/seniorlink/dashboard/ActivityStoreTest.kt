package family.seniorlink.dashboard

import android.app.Application
import family.seniorlink.core.*
import family.seniorlink.data.Store
import family.seniorlink.data.dayBounds
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ActivityStoreTest {
    private val source = "a".repeat(64)
    private val other = "b".repeat(64)
    private val zone = ZoneId.of("Europe/Bratislava")
    private val date = LocalDate.of(2026, 9, 13)
    private val start = dayBounds(date, zone).first
    private val now = start + 12 * 3_600_000
    private fun store() = Store(RuntimeEnvironment.getApplication(), UUID.randomUUID().toString()).apply {
        updateSettings(Settings(role = Role.SHARER, unlock = true, phoneBattery = true), source)
    }

    @Test fun dayCountsAreNotLimitedByBusyFeedAndStayScopedToSourceAndRecordedDay() {
        store().use { store ->
            store.append(source, Event(0, Kind.CHECK_IN, start + 1), now)
            repeat(145) { store.append(source, Event(0, Kind.UNLOCK, start + 1000 + it), now) }
            store.append(source, Event(0, Kind.CHECK_IN, start - 1), now) // late delivery, previous recorded day
            store.append(other, Event(0, Kind.CHECK_IN, start + 2), now)
            assertEquals(100, store.recent(now).size)
            val day = store.activityDay(source, date, now = now, zone = zone)
            assertEquals(146, day.counts.sumOf { it.count })
            assertEquals(1, day.counts.single { it.kind == Kind.CHECK_IN }.count)
            assertEquals(145, day.counts.single { it.kind == Kind.UNLOCK }.count)
            assertEquals(start + 1144, day.counts.single { it.kind == Kind.UNLOCK }.lastAt)
            assertTrue(day.records.isEmpty())
            assertEquals(date.minusDays(1), day.firstDate)
            assertEquals(1, store.activityDay(source, date.minusDays(1), now = now, zone = zone).counts.sumOf { it.count })
            assertEquals(1, store.activityDay(other, date, now = now, zone = zone).counts.sumOf { it.count })
        }
    }

    @Test fun filteredPagingHasStableTieOrderAndDoesNotDropEarlierRecords() {
        store().use { store ->
            repeat(45) { store.append(source, Event(0, Kind.UNLOCK, start + 1000), now) }
            store.append(source, Event(0, Kind.PHONE_BATTERY, start + 2000, phoneBattery = PhoneBattery(46)), now)
            val first = store.activityDay(source, date, Kind.UNLOCK, 40, now, zone)
            assertTrue(first.hasMore)
            assertEquals((45L downTo 6L).toList(), first.records.map { it.event.sequence })
            val full = store.activityDay(source, date, Kind.UNLOCK, 80, now, zone)
            assertFalse(full.hasMore)
            assertEquals((45L downTo 1L).toList(), full.records.map { it.event.sequence })
            val battery = store.activityDay(source, date, Kind.PHONE_BATTERY, 4, now, zone)
            assertEquals(46, battery.records.single().event.phoneBattery?.percent)
            assertEquals(46, battery.counts.sumOf { it.count }) // summary independent of detail filter
        }
    }

    @Test fun expiredAndWithdrawnRecordsDisappearWithoutChangingDeliveryCursors() {
        store().use { store ->
            store.append(source, Event(0, Kind.PHONE_BATTERY, start + 1000, phoneBattery = PhoneBattery(0)), now)
            assertEquals(1, store.activityDay(source, date, now = now, zone = zone).counts.sumOf { it.count })
            assertTrue(store.activityDay(source, date, now = now + Store.RETENTION_MS + 1, zone = zone).counts.isEmpty())
            store.updateSettings(store.settings.copy(phoneBattery = false), source)
            assertTrue(store.activityDay(source, date, now = now, zone = zone).counts.isEmpty())
            assertEquals(1L, store.latest(source))
        }
    }

    @Test fun localDaysIncludeDaylightSavingHoursAndExcludeTheNextMidnight() {
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 29) to 23, LocalDate.of(2026, 10, 25) to 25)) {
            val (from, until) = dayBounds(date, zone)
            assertEquals(hours * 3_600_000L, until - from)
            store().use { store ->
                listOf(from, until - 1, until).forEach { store.append(source, Event(0, Kind.CHECK_IN, it), until) }
                assertEquals(2, store.activityDay(source, date, now = until, zone = zone).counts.sumOf { it.count })
                assertEquals(1, store.activityDay(source, date.plusDays(1), now = until, zone = zone).counts.sumOf { it.count })
            }
        }
    }

    @Test fun revokingACaregiverSourceRemovesItsSummaryAndPreview() {
        val id = UUID.randomUUID().toString()
        Store(RuntimeEnvironment.getApplication(), id).use { store ->
            store.updateSettings(Settings(role = Role.CAREGIVER), other)
            store.addPeer(source, "Synthetic family", other)
            store.commit(Batch(source = source, through = 1, latest = 1, earliest = 1,
                events = listOf(Event(1, Kind.LOCATION, now, latitude = 48.1, longitude = 17.1, accuracy = 45f))), now)
            assertEquals(1, store.activityDay(source, date, limit = 4, now = now, zone = zone).records.size)
            assertEquals(1L, store.location(source, 1, now)?.event?.sequence)
            assertNull(store.location(source, 1, now + Store.RETENTION_MS + 1))
            store.removePeer(source)
            store.addPeer(source, "Reapproved synthetic family", other)
            val removed = store.activityDay(source, date, limit = 4, now = now, zone = zone)
            assertTrue(removed.counts.isEmpty())
            assertTrue(removed.records.isEmpty())
            assertNull(store.location(source, 1, now))
        }
    }

    @Test fun openingAnOlderFixDoesNotDependOnTheLatestThousandLocations() {
        store().use { store ->
            store.updateSettings(store.settings.copy(location = true), source)
            repeat(1005) { index -> store.append(source, Event(0, Kind.LOCATION, start + index + 1,
                latitude = 48.1, longitude = 17.1, accuracy = 45f), now) }
            assertEquals(1000, store.locations(source, now).size)
            assertTrue(store.locations(source, now).none { it.event.sequence == 1L })
            assertEquals(1L, store.location(source, 1, now)?.event?.sequence)
            store.updateSettings(store.settings.copy(location = false), source)
            assertNull(store.location(source, 1, now))
        }
    }
    @Test fun batteryChartsKeepFullDayBeyondPreviewAndExcludeOtherDaysSourcesAndExpiredData() {
        store().use { store ->
            repeat(12) { index -> store.append(source, Event(0, Kind.PHONE_BATTERY, start + index * 300_000,
                phoneBattery = PhoneBattery(80 - index, index < 3)), now) }
            store.append(source, Event(0, Kind.PHONE_BATTERY, start - 1, phoneBattery = PhoneBattery(90)), now)
            store.append(other, Event(0, Kind.PHONE_BATTERY, start + 1, phoneBattery = PhoneBattery(40)), now)
            repeat(120) { store.append(source, Event(0, Kind.UNLOCK, start + it), now) }
            assertEquals(13, store.phoneBatteries(source, now).size)
            val day = store.activityDay(source, date, Kind.PHONE_BATTERY, 4, now, zone)
            assertEquals(4, day.records.size)
            assertEquals(12, day.batteryRecords.size)
            assertEquals((69..80).toList(), day.batteryRecords.map { it.event.phoneBattery!!.percent })
            assertTrue(store.phoneBatteries(source, now + Store.RETENTION_MS + 1).isEmpty())
            assertTrue(store.activityDay(source, date, Kind.PHONE_BATTERY, 4, now + Store.RETENTION_MS + 1, zone).batteryRecords.isEmpty())
            store.updateSettings(store.settings.copy(phoneBattery = false), source)
            assertTrue(store.phoneBatteries(source, now).isEmpty())
            assertTrue(store.activityDay(source, date, Kind.PHONE_BATTERY, 4, now, zone).batteryRecords.isEmpty())
        }
    }

}
