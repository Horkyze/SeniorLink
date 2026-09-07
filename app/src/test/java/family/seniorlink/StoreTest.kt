package family.seniorlink

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import family.seniorlink.core.*
import family.seniorlink.data.Store
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlinx.serialization.encodeToString

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

    @Test fun `location history survives a busy feed and sorts by fix time per source`() {
        sharer().use { db ->
            db.updateSettings(db.settings.copy(location = true), source)
            val latest = db.append(source, Event(0, Kind.LOCATION, now + 10,
                latitude = 48.15, longitude = 17.11, accuracy = 12f), now)
            val older = db.append(source, Event(0, Kind.LOCATION, now,
                latitude = 48.14, longitude = 17.10, accuracy = 25f), now + 1)
            repeat(110) { db.append(source, Event(0, Kind.UNLOCK, now + 20 + it), now + 2) }
            assertTrue(db.recent(now + 2).none { it.event.kind == Kind.LOCATION })
            assertEquals(listOf(latest, older), db.locations(source, now + 2).map { it.event })
            assertTrue(db.locations(brother, now + 2).isEmpty())
            db.updateSettings(db.settings.copy(location = false), source)
            assertTrue(db.locations(source, now + 2).isEmpty())
        }
    }

    @Test fun `received location history respects expiry and peer removal`() {
        inbox().use { db ->
            val location = Event(1, Kind.LOCATION, now, latitude = 0.0, longitude = 0.0, accuracy = 0f)
            db.commit(Batch(source = source, through = 1, latest = 1, earliest = 1, events = listOf(location)), now)
            assertEquals(location, db.locations(source, now).single().event)
            assertTrue(db.locations(source, now + Store.RETENTION_MS + 1).isEmpty())
            db.commit(Batch(source = source, through = 2, latest = 2, earliest = 1,
                events = listOf(location.copy(sequence = 2))), now + Store.RETENTION_MS + 2)
            db.removePeer(source)
            assertTrue(db.locations(source, now + Store.RETENTION_MS + 2).isEmpty())
        }
    }

    @Test fun `version one upgrade indexes existing locations without losing history or pairing`() {
        val name = UUID.randomUUID().toString()
        val context = RuntimeEnvironment.getApplication() as Context
        val location = Event(1, Kind.LOCATION, now, latitude = 48.15, longitude = 17.11, accuracy = 12f)
        val path = context.getDatabasePath("$name.db").also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE peers(id TEXT PRIMARY KEY, name TEXT NOT NULL, cursor INTEGER NOT NULL DEFAULT 0, contact INTEGER NOT NULL DEFAULT 0, gap INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE TABLE counters(source TEXT PRIMARY KEY, sequence INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE events(source TEXT NOT NULL, sequence INTEGER NOT NULL, storedAt INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(source, sequence))")
            old.execSQL("CREATE TABLE telegram(source TEXT NOT NULL, sequence INTEGER NOT NULL, retryAt INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(source, sequence), FOREIGN KEY(source, sequence) REFERENCES events(source, sequence) ON DELETE CASCADE)")
            old.execSQL("INSERT INTO peers(id,name,cursor,contact) VALUES(?,?,?,?)", arrayOf(caregiver, "Anna", 1L, now))
            old.execSQL("INSERT INTO counters(source,sequence) VALUES(?,1)", arrayOf(source))
            old.execSQL("INSERT INTO events VALUES(?,?,?,?)", arrayOf(source, 1L, now, Wire.json.encodeToString(location)))
            old.execSQL("INSERT INTO telegram(source,sequence,retryAt) VALUES(?,1,?)", arrayOf(source, now))
            old.version = 1
        }
        context.getSharedPreferences("$name-settings", Context.MODE_PRIVATE).edit().putString("settings",
            Wire.json.encodeToString(Settings(role = Role.SHARER, location = true))).commit()
        store(name).use { upgraded ->
            assertEquals(location, upgraded.locations(source, now).single().event)
            assertEquals(location, upgraded.recent(now).single().event)
            assertEquals("Anna", upgraded.peers().single().name)
            assertEquals(1L, upgraded.cursor(caregiver))
            assertEquals(location, upgraded.nextTelegram(now)!!.event)
            assertEquals(2L, upgraded.append(source, location.copy(sequence = 0, occurredAt = now + 1), now + 1).sequence)
        }
        store(name).use { assertEquals(2, it.locations(source, now + 1).size) }
    }
}
