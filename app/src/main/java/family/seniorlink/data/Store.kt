package family.seniorlink.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import family.seniorlink.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

data class StoredEvent(val source: String, val event: Event)
data class TelegramJob(val source: String, val event: Event, val attempts: Int)

/** One transaction boundary owns event insertion, deduplication and cursor advancement. */
class Store(context: Context, name: String = "seniorlink") : SQLiteOpenHelper(context, "$name.db", null, 1), Inbox {
    private val prefs = context.getSharedPreferences("$name-settings", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    @Volatile var settings: Settings = prefs.getString("settings", null)
        ?.let { Wire.json.decodeFromString<Settings>(it) } ?: Settings()
        private set

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE peers(id TEXT PRIMARY KEY, name TEXT NOT NULL, cursor INTEGER NOT NULL DEFAULT 0, contact INTEGER NOT NULL DEFAULT 0, gap INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE counters(source TEXT PRIMARY KEY, sequence INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE events(source TEXT NOT NULL, sequence INTEGER NOT NULL, storedAt INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(source, sequence))")
        db.execSQL("CREATE TABLE telegram(source TEXT NOT NULL, sequence INTEGER NOT NULL, retryAt INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(source, sequence), FOREIGN KEY(source, sequence) REFERENCES events(source, sequence) ON DELETE CASCADE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("A migration is required; never silently discard safety history.")
    }

    private fun changed() { revision.value += 1 }

    @Synchronized fun updateSettings(next: Settings, localId: String) {
        require(next.role == settings.role || settings.role == Role.UNSET)
        require(next.smsSenders.length <= 2000 && next.telegramChat.length <= 100)
        val old = settings
        // Remove retained local content when its permission is withdrawn, including queued Telegram jobs.
        transaction { db ->
            localEvents(localId).forEach { event ->
                val withdraw = !next.allows(event.kind) ||
                    (event.kind == Kind.SMS && (old.smsBodies != next.smsBodies || old.smsSenders != next.smsSenders))
                if (withdraw) db.delete("events", "source=? AND sequence=?", arrayOf(localId, event.sequence.toString()))
            }
            if (!next.telegram || old.telegramChat != next.telegramChat) db.delete("telegram", null, null)
        }
        check(prefs.edit().putString("settings", Wire.json.encodeToString(next)).commit())
        settings = next
        changed()
    }

    @Synchronized fun peers(): List<Peer> = readableDatabase.rawQuery(
        "SELECT id,name,cursor,contact,gap FROM peers ORDER BY name,id", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Peer(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) != 0))
        }
    }

    @Synchronized fun approved(id: String): Boolean = peers().any { it.id == id }

    @Synchronized fun addPeer(id: String, name: String, ownId: String) {
        require(Pairing.parse(id) == id && id != ownId)
        require(name.isNotBlank() && name.length <= 40)
        require(approved(id) || peers().size < 8) { "At most eight paired phones." }
        if (approved(id)) {
            writableDatabase.execSQL("UPDATE peers SET name=? WHERE id=?", arrayOf(name, id))
        } else {
            writableDatabase.execSQL("INSERT INTO peers(id,name) VALUES(?,?)", arrayOf(id, name))
        }
        changed()
    }

    @Synchronized fun removePeer(id: String) {
        transaction { db ->
            db.delete("peers", "id=?", arrayOf(id))
            if (settings.role == Role.CAREGIVER) db.delete("events", "source=?", arrayOf(id))
        }
        changed()
    }

    @Synchronized fun append(source: String, event: Event, now: Long): Event {
        require(settings.role == Role.SHARER && settings.allows(event.kind))
        var saved = event
        transaction { db ->
            val seq = latest(source) + 1
            saved = event.copy(sequence = seq)
            if (saved.kind == Kind.SMS) {
                require(SmsPolicy.permits(saved.sender.orEmpty(), settings.smsSenders))
                saved = saved.copy(body = if (settings.smsBodies) saved.body?.let(SmsPolicy::safeBody) else null)
            }
            saved.validate()
            db.execSQL("INSERT OR REPLACE INTO counters(source,sequence) VALUES(?,?)", arrayOf<Any>(source, seq))
            insert(db, source, saved, now)
            if (settings.telegram) {
                db.execSQL("INSERT INTO telegram(source,sequence,retryAt) VALUES(?,?,?)", arrayOf<Any>(source, seq, now))
            }
            prune(db, source, now)
        }
        changed()
        return saved
    }

    @Synchronized fun latest(source: String): Long = readableDatabase.rawQuery(
        "SELECT sequence FROM counters WHERE source=?", arrayOf(source),
    ).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    @Synchronized fun batch(source: String, after: Long, now: Long): Batch {
        require(after in 0..latest(source))
        prune(writableDatabase, source, now)
        val latest = latest(source)
        val earliest = readableDatabase.rawQuery(
            "SELECT MIN(sequence) FROM events WHERE source=?", arrayOf(source),
        ).use { it.moveToFirst(); if (it.isNull(0)) latest + 1 else it.getLong(0) }
        val scanned = readableDatabase.rawQuery(
            "SELECT json FROM events WHERE source=? AND sequence>? ORDER BY sequence LIMIT ${Wire.PAGE_SIZE}",
            arrayOf(source, after.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Wire.json.decodeFromString<Event>(c.getString(0))) } }
        return Batch(
            source = source,
            through = if (scanned.size < Wire.PAGE_SIZE) latest else scanned.last().sequence,
            latest = latest,
            earliest = earliest,
            events = scanned.filter { settings.allows(it.kind) },
        )
    }

    @Synchronized override fun cursor(source: String): Long =
        peers().firstOrNull { it.id == source }?.cursor ?: error("Phone is no longer paired")

    @Synchronized override fun commit(batch: Batch, receivedAt: Long) {
        require(settings.role == Role.CAREGIVER && approved(batch.source))
        val after = cursor(batch.source)
        batch.validate(batch.source, after)
        transaction { db ->
            batch.events.forEach { insert(db, batch.source, it, receivedAt) }
            db.execSQL(
                "UPDATE peers SET cursor=?,contact=?,gap=MAX(gap,?) WHERE id=?",
                arrayOf<Any>(batch.through, receivedAt, if (batch.hasGap(after)) 1 else 0, batch.source),
            )
            prune(db, batch.source, receivedAt)
        }
        changed()
    }

    @Synchronized fun acknowledge(peer: String, through: Long, now: Long) {
        require(approved(peer) && through >= 0)
        writableDatabase.execSQL(
            "UPDATE peers SET cursor=MAX(cursor,?),contact=? WHERE id=?", arrayOf<Any>(through, now, peer),
        )
        changed()
    }

    @Synchronized fun recent(now: Long = System.currentTimeMillis()): List<StoredEvent> {
        writableDatabase.delete("events", "storedAt<?", arrayOf((now - RETENTION_MS).toString()))
        return readableDatabase.rawQuery(
        "SELECT source,json FROM events ORDER BY storedAt DESC,sequence DESC LIMIT 100", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(StoredEvent(c.getString(0), Wire.json.decodeFromString<Event>(c.getString(1))))
            }
        }
    }

    @Synchronized fun nextTelegram(now: Long): TelegramJob? = readableDatabase.rawQuery(
        "SELECT t.source,e.json,t.attempts FROM telegram t JOIN events e ON e.source=t.source AND e.sequence=t.sequence WHERE t.retryAt<=? ORDER BY t.sequence LIMIT 1",
        arrayOf(now.toString()),
    ).use {
        if (it.moveToFirst()) TelegramJob(it.getString(0), Wire.json.decodeFromString<Event>(it.getString(1)), it.getInt(2))
        else null
    }

    @Synchronized fun finishTelegram(job: TelegramJob, retryAt: Long?) {
        if (retryAt == null) writableDatabase.delete(
            "telegram", "source=? AND sequence=?", arrayOf(job.source, job.event.sequence.toString()),
        ) else writableDatabase.execSQL(
            "UPDATE telegram SET attempts=attempts+1,retryAt=? WHERE source=? AND sequence=?",
            arrayOf<Any>(retryAt, job.source, job.event.sequence),
        )
        changed()
    }

    @Synchronized fun pendingTelegram(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM telegram", null)
        .use { it.moveToFirst(); it.getLong(0) }

    private fun localEvents(source: String): List<Event> = readableDatabase.rawQuery(
        "SELECT json FROM events WHERE source=?", arrayOf(source),
    ).use { c -> buildList { while (c.moveToNext()) add(Wire.json.decodeFromString<Event>(c.getString(0))) } }

    private fun insert(db: SQLiteDatabase, source: String, event: Event, now: Long) {
        val values = ContentValues().apply {
            put("source", source); put("sequence", event.sequence); put("storedAt", now)
            put("json", Wire.json.encodeToString(event))
        }
        db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun prune(db: SQLiteDatabase, source: String, now: Long) {
        db.delete("events", "source=? AND storedAt<?", arrayOf(source, (now - RETENTION_MS).toString()))
        db.execSQL(
            "DELETE FROM events WHERE source=? AND sequence NOT IN (SELECT sequence FROM events WHERE source=? ORDER BY sequence DESC LIMIT 10000)",
            arrayOf(source, source),
        )
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    companion object { const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 }
}
