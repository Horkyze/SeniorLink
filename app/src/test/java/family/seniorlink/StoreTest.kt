package family.seniorlink

import android.app.Application
import family.seniorlink.core.*
import family.seniorlink.data.Store
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StoreTest {
    private val source = "a".repeat(64)
    private val caregiver = "b".repeat(64)
    private val brother = "c".repeat(64)
    private val now = 1_000_000L
    private fun store(name: String = UUID.randomUUID().toString()) = Store(RuntimeEnvironment.getApplication(), name)
    private fun sharer() = store().apply {
        updateSettings(Settings(role = Role.SHARER, unlock = true), source)
        addPeer(caregiver, "Caregiver", source)
        addPeer(brother, "Brother", source)
    }
    private fun inbox(name: String = UUID.randomUUID().toString()) = store(name).apply {
        if (settings.role == Role.UNSET) updateSettings(Settings(role = Role.CAREGIVER), caregiver)
        addPeer(source, "Grandfather", caregiver)
    }

    @Test fun `independent delivery receipts never block another caregiver`() {
        sharer().use { db ->
            repeat(25) { db.append(source, Event(0, Kind.UNLOCK, now + it), now) }
            val first = db.batch(source, 0, now)
            assertEquals(20, first.events.size)
            assertEquals(20L, first.through)
            db.acknowledge(caregiver, first.through, now)
            assertEquals(20L, db.cursor(caregiver))
            assertEquals(0L, db.cursor(brother))
            val last = db.batch(source, first.through, now)
            assertEquals(5, last.events.size)
            assertEquals(25L, last.through)
            assertEquals(20, db.batch(source, 0, now).events.size)
        }
    }

    @Test fun `events and cursor survive reopen and duplicates do not multiply`() {
        val name = UUID.randomUUID().toString()
        val event = Event(1, Kind.CHECK_IN, now)
        inbox(name).use { db ->
            db.commit(Batch(source = source, through = 1, latest = 1, earliest = 1, events = listOf(event)), now)
        }
        inbox(name).use { db ->
            assertEquals(1L, db.cursor(source))
            db.commit(Batch(source = source, through = 1, latest = 1, earliest = 1, events = emptyList()), now + 1)
            assertEquals(listOf(event), db.recent(now).map { it.event })
        }
    }

    @Test fun `invalid batch changes neither history nor cursor`() {
        inbox().use { db ->
            val bad = Event(2, Kind.SMS, now, sender = "Bank", body = "a".repeat(2001))
            try {
                db.commit(Batch(source = source, through = 2, latest = 2, earliest = 1,
                    events = listOf(Event(1, Kind.CHECK_IN, now), bad)), now)
                fail("Invalid payload was accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0L, db.cursor(source))
            assertTrue(db.recent(now).isEmpty())
        }
    }

    @Test fun `removing source deletes cache and rejects in flight commits`() {
        inbox().use { db ->
            val batch = Batch(source = source, through = 1, latest = 1, earliest = 1,
                events = listOf(Event(1, Kind.CHECK_IN, now)))
            db.commit(batch, now)
            db.removePeer(source)
            assertTrue(db.recent(now).isEmpty())
            assertFalse(db.approved(source))
            try { db.commit(batch, now); fail("Revoked peer accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `retention preserves sequence and reports missing history`() {
        sharer().use { db ->
            db.append(source, Event(0, Kind.UNLOCK, now), now)
            val afterExpiry = now + Store.RETENTION_MS + 1
            val expired = db.batch(source, 0, afterExpiry)
            assertTrue(expired.events.isEmpty())
            assertEquals(1L, expired.through)
            assertTrue(expired.hasGap(0))
            db.append(source, Event(0, Kind.UNLOCK, afterExpiry), afterExpiry)
            assertEquals(2L, db.latest(source))
            inbox().use { receiver ->
                receiver.commit(db.batch(source, 0, afterExpiry), afterExpiry)
                assertTrue(receiver.peers().single().historyGap)
            }
        }
    }

    @Test fun `withdrawal purges local SMS and queued telegram without resetting sequence`() {
        sharer().use { db ->
            db.updateSettings(db.settings.copy(sms = true, smsBodies = true, smsSenders = "Doctor", telegram = true), source)
            db.append(source, Event(0, Kind.SMS, now, sender = "Doctor", body = "Lunch at noon"), now)
            assertEquals(1L, db.pendingTelegram())
            db.updateSettings(db.settings.copy(sms = false), source)
            assertEquals(0L, db.pendingTelegram())
            assertTrue(db.recent(now).isEmpty())
            assertEquals(1L, db.latest(source))
        }
    }

    @Test fun `SMS storage enforces current allowlist and body consent`() {
        sharer().use { db ->
            db.updateSettings(db.settings.copy(sms = true, smsSenders = "Doctor"), source)
            val saved = db.append(source, Event(0, Kind.SMS, now, sender = "Doctor", body = "Private text"), now)
            assertNull(saved.body)
            try {
                db.append(source, Event(0, Kind.SMS, now, sender = "Other", body = "Secret"), now)
                fail("Unapproved sender accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1L, db.latest(source))
        }
    }
}
