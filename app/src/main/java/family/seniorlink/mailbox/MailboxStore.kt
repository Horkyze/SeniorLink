package family.seniorlink.mailbox

import android.database.sqlite.SQLiteDatabase
import family.seniorlink.core.*
import family.seniorlink.core.mailbox.*
import family.seniorlink.data.Store

/** Uses Store's monitor and database: event/outbox and event/receipt commits are atomic. */
class MailboxStore(private val store: Store) {
    data class PeerState(val peer: String, val enabled: Boolean, val grant: SignedMailbox?, val acceptance: SignedMailbox?, val confirmed: Boolean = false)
    data class Outgoing(val peer: String, val sequence: Long, val grantId: String, val generation: Long,
        val event: Event, val storedAt: Long, val envelope: MailboxEnvelope?)
    data class Cleanup(val grantId: String, val peer: String, val service: MailboxService, val source: String, val grant: SignedMailbox)
    data class Summary(val configured: Boolean = false, val pending: Long = 0, val stored: Long = 0, val received: Long = 0,
        val deletionPending: Boolean = false, val expired: Long = 0)
    private val db get() = store.writableDatabase
    private fun <T> locked(block: () -> T): T = synchronized(store) { block() }
    private fun <T> tx(block: () -> T): T = locked { db.beginTransaction(); try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() } }
    private fun meta(key: String) = db.rawQuery("SELECT value FROM mailbox_meta WHERE key=?", arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else null }
    private fun set(key: String, value: String) { db.execSQL("INSERT OR REPLACE INTO mailbox_meta VALUES(?,?)", arrayOf(key, value)) }
    fun service(): MailboxService? = locked { meta("service")?.let { MailboxWire.decode<MailboxService>(it.toByteArray()) } }
    fun configure(service: MailboxService) = tx {
        service.validate()
        require(this.service() == null || this.service() == service || peers().none { it.enabled || it.grant != null } && cleanups().isEmpty()) {
            "Turn off queued delivery and wait for mailbox deletion before changing servers."
        }
        if (this.service() != service || peers().none { it.enabled || it.grant != null } && cleanups().isEmpty()) {
            // Counters stay monotonic even when returning to a previously used service.
            db.delete("mailbox_meta", "key=?", arrayOf("info"))
            set("service", MailboxWire.encode(service).toString(Charsets.UTF_8))
            invalidate(db)
        }
    }
    fun info(): MailboxInfo? = locked { meta("info")?.let { MailboxWire.decode<MailboxInfo>(it.toByteArray()) } }
    fun saveInfo(info: MailboxInfo) = tx {
        require(info.version == 1 && info.service == service()?.id && MailboxWire.isId(info.incarnation))
        check(this.info() == null || this.info() == info) { "Mailbox storage changed. Turn off queued delivery and set up again." }
        set("info", MailboxWire.encode(info).toString(Charsets.UTF_8))
    }
    fun peers(): List<PeerState> = locked { db.rawQuery("SELECT peer,enabled,grantjson,acceptance,confirmed FROM mailbox_peers", null).use { c ->
        buildList { while(c.moveToNext()) add(PeerState(c.getString(0), c.getInt(1) == 1,
            c.getString(2)?.let { MailboxWire.decode<SignedMailbox>(it.toByteArray()) }, c.getString(3)?.let { MailboxWire.decode<SignedMailbox>(it.toByteArray()) }, c.getInt(4)==1)) }
    } }
    fun peer(peer: String) = peers().firstOrNull { it.peer == peer }
    fun enabled(peer: String) = locked { store.approved(peer) && peer(peer)?.enabled == true }
    fun enable(peer: String, value: Boolean) = tx {
        require(store.approved(peer) && service() != null)
        if (!value) closePeer(db, peer)
        db.execSQL("INSERT OR IGNORE INTO mailbox_peers(peer,enabled) VALUES(?,0)", arrayOf(peer))
        db.execSQL("UPDATE mailbox_peers SET enabled=? WHERE peer=?", arrayOf<Any>(if(value) 1 else 0, peer))
    }
    fun saveGrant(peer: String, grant: SignedMailbox, acceptance: SignedMailbox? = null) = tx {
        check(enabled(peer)); val existing = peer(peer)
        check(existing?.grant == null || existing.grant == grant) { "A mailbox setup is already pending for this phone." }
        db.execSQL("UPDATE mailbox_peers SET grantjson=?,acceptance=COALESCE(?,acceptance) WHERE peer=?",
            arrayOf(MailboxWire.encode(grant).toString(Charsets.UTF_8), acceptance?.let { MailboxWire.encode(it).toString(Charsets.UTF_8) },peer))
        if (acceptance != null && store.settings.role == Role.SHARER) seed()
    }
    fun confirmed(peer: String, grant: SignedMailbox) = locked { db.execSQL("UPDATE mailbox_peers SET confirmed=1 WHERE peer=? AND grantjson=?",arrayOf(peer,MailboxWire.encode(grant).toString(Charsets.UTF_8))) }
    fun cleanups(): List<Cleanup> = locked { db.rawQuery("SELECT id,peer,service,source,grantjson FROM mailbox_cleanup", null).use { c ->
        buildList { while(c.moveToNext()) add(Cleanup(c.getString(0),c.getString(1),MailboxWire.decode(c.getString(2).toByteArray()),c.getString(3),MailboxWire.decode(c.getString(4).toByteArray()))) }
    } }
    fun cleaned(id: String) = locked { db.delete("mailbox_cleanup","id=?",arrayOf(id)) }
    fun closePeer(db: SQLiteDatabase, peer: String) {
        val value = peer(peer); val service = service()
        if (value?.grant != null && service != null) {
            val grant = MailboxWire.decode<MailboxGrant>(MailboxWire.unb64(value.grant.payload))
            db.execSQL("INSERT OR IGNORE INTO mailbox_cleanup VALUES(?,?,?,?,?)",arrayOf(grant.grantId,peer,MailboxWire.encode(service).toString(Charsets.UTF_8),grant.source,MailboxWire.encode(value.grant).toString(Charsets.UTF_8)))
        }
        db.delete("mailbox_outbox","peer=?",arrayOf(peer)); db.delete("mailbox_receipts","source=?",arrayOf(peer)); db.delete("mailbox_seen","source=?",arrayOf(peer))
        db.delete("mailbox_peers","peer=?",arrayOf(peer))
    }
    fun generation() = locked { meta("generation")?.toLong() ?: 1L }
    fun policyDirty() = locked { meta("dirty") == "1" }
    fun sharingAllowed() = locked { meta("active") == "1" }
    /** Caller closes the collection gate first. Safe even before service provisioning. */
    fun sharing(active: Boolean) = tx {
        if (sharingAllowed() != active) { set("active", if(active) "1" else "0"); invalidate(db) }
    }
    fun invalidate(db: SQLiteDatabase) {
        set("generation", (generation() + 1).toString()); set("revision", ((meta("revision")?.toLong() ?: 0) + 1).toString())
        set("dirty", "1"); set("until", "0"); db.delete("mailbox_outbox",null,null)
        if (sharingAllowed()) seed()
    }
    fun nextPolicy(now: Long): MailboxPolicy? = tx {
        val info = info() ?: return@tx null
        if (!policyDirty() && (!sharingAllowed() || (meta("until")?.toLong() ?: 0) > now + 23 * 60 * 60_000L)) return@tx null
        val revision = (meta("revision")?.toLong() ?: 0) + 1
        set("revision", revision.toString()); set("dirty", "1")
        MailboxPolicy(service=info.service,incarnation=info.incarnation,source=info.source,revision=revision,
            generation=generation(), enabled=sharingAllowed(),until=if(sharingAllowed()) now+MailboxWire.LEASE else 0)
    }
    fun policySent(policy: MailboxPolicy) = tx {
        if (meta("revision")?.toLong() == policy.revision && generation() == policy.generation && sharingAllowed() == policy.enabled) {
            set("dirty","0"); set("until",policy.until.toString())
        }
    }
    fun seed() {
        if (!sharingAllowed()) return
        val now = System.currentTimeMillis()
        db.rawQuery("SELECT source,sequence FROM events WHERE storedAt>?",arrayOf((now-MailboxWire.RETENTION).toString())).use { c ->
            while(c.moveToNext()) enqueue(db,c.getString(0),c.getLong(1))
        }
    }
    fun enqueue(db: SQLiteDatabase, source: String, sequence: Long) {
        if (store.settings.role != Role.SHARER || !sharingAllowed()) return
        peers().filter { it.enabled && it.grant != null && it.acceptance != null && store.approved(it.peer) }.forEach {
            val grant=MailboxWire.decode<MailboxGrant>(MailboxWire.unb64(it.grant!!.payload))
            db.execSQL("INSERT OR IGNORE INTO mailbox_outbox(peer,source,sequence,grantid,generation) VALUES(?,?,?,?,?)",arrayOf<Any>(it.peer,source,sequence,grant.grantId,generation()))
        }
    }
    fun outgoing(peer: String? = null): List<Outgoing> = locked {
        db.rawQuery("SELECT o.peer,o.sequence,o.grantid,o.generation,e.json,e.storedAt,o.envelope FROM mailbox_outbox o JOIN events e ON e.source=o.source AND e.sequence=o.sequence WHERE o.state=0 AND e.storedAt>? " + (if (peer == null) "" else " AND o.peer=?") + " ORDER BY o.sequence LIMIT 20",
            (listOf((System.currentTimeMillis()-MailboxWire.RETENTION).toString()) + listOfNotNull(peer)).toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(Outgoing(c.getString(0),c.getLong(1),c.getString(2),c.getLong(3),Wire.json.decodeFromString(c.getString(4)),c.getLong(5), c.getString(6)?.let { MailboxWire.decode<MailboxEnvelope>(it.toByteArray()) })) } }
    }
    fun saveEnvelope(item: Outgoing, envelope: MailboxEnvelope) = locked {
        check(sharingAllowed() && enabled(item.peer) && generation()==item.generation)
        db.execSQL("UPDATE mailbox_outbox SET envelope=? WHERE peer=? AND sequence=? AND generation=? AND grantid=? AND state=0",arrayOf<Any>(MailboxWire.encode(envelope).toString(Charsets.UTF_8),item.peer,item.sequence,item.generation,item.grantId))
    }
    fun stored(item: Outgoing) = locked { db.execSQL("UPDATE mailbox_outbox SET state=1 WHERE peer=? AND sequence=? AND generation=? AND grantid=?",arrayOf<Any>(item.peer,item.sequence,item.generation,item.grantId)) }
    fun delivered(receipt: MailboxReceipt) = locked {
        db.rawQuery("SELECT sequence,envelope FROM mailbox_outbox WHERE peer=? AND grantid=? AND envelope IS NOT NULL",arrayOf(receipt.recipient,receipt.grantId)).use { c ->
            while(c.moveToNext()) { val e=MailboxWire.decode<MailboxEnvelope>(c.getString(1).toByteArray()); if(MailboxWire.envelopeHash(e)==receipt.envelopeHash) db.execSQL("UPDATE mailbox_outbox SET state=2 WHERE peer=? AND sequence=?",arrayOf<Any>(receipt.recipient,c.getLong(0))) }
        }
    }
    fun commitReceived(header: MailboxHeader, content: MailboxContent, envelopeHash: String, signedReceipt: SignedMailbox, now: Long) = tx {
        check(store.settings.role==Role.CAREGIVER && enabled(header.source))
        val grant=peer(header.source)?.grant?.let { MailboxWire.decode<MailboxGrant>(MailboxWire.unb64(it.payload)) }
        check(grant?.grantId==header.grantId)
        require(header.expires>now)
        val previous=db.rawQuery("SELECT hash FROM mailbox_receipts WHERE id=?",arrayOf(header.messageId)).use { if(it.moveToFirst())it.getString(0) else null }
        if(previous!=null) { require(previous==envelopeHash); return@tx }
        val eventHash=MailboxWire.hash(MailboxWire.encode(content.event))
        val seen=db.rawQuery("SELECT hash FROM mailbox_seen WHERE source=? AND sequence=?",arrayOf(header.source,content.event.sequence.toString())).use { if(it.moveToFirst())it.getString(0) else null }
        if(seen!=null) require(seen==eventHash) else {
            store.insertMailboxEvent(db,header.source,content.event,now)
            db.execSQL("INSERT INTO mailbox_seen VALUES(?,?,?,?)",arrayOf<Any>(header.source,content.event.sequence,eventHash,header.expires))
        }
        db.execSQL("INSERT INTO mailbox_receipts VALUES(?,?,?,?,?,0,?)",arrayOf<Any>(header.messageId,header.source,header.grantId,envelopeHash,MailboxWire.encode(signedReceipt).toString(Charsets.UTF_8),header.expires))
        // The direct peer cursor and last direct contact deliberately remain unchanged.
    }
    fun pendingReceipts(source: String): List<Pair<String,SignedMailbox>> = locked {
        db.rawQuery("SELECT id,json FROM mailbox_receipts WHERE source=? AND acked=0 LIMIT 20",arrayOf(source)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to MailboxWire.decode<SignedMailbox>(c.getString(1).toByteArray())) } }
    }
    fun acked(ids: List<String>) = tx { ids.forEach { db.execSQL("UPDATE mailbox_receipts SET acked=1 WHERE id=?",arrayOf(it)) } }
    fun beforeHistoryPrune(source: String, now: Long) = locked {
        val lost=db.rawQuery("SELECT COUNT(*) FROM mailbox_outbox o JOIN events e ON e.source=o.source AND e.sequence=o.sequence WHERE o.source=? AND o.state<>2 AND (e.storedAt<? OR e.sequence NOT IN (SELECT sequence FROM events WHERE source=? ORDER BY sequence DESC LIMIT 10000))",arrayOf(source,(now-MailboxWire.RETENTION).toString(),source)).use { it.moveToFirst();it.getLong(0) }
        if(lost>0) set("expired",((meta("expired")?.toLong() ?: 0)+lost).toString())
    }
    fun prune(now: Long) = tx {
        val expired=db.rawQuery("SELECT COUNT(*) FROM mailbox_outbox o JOIN events e ON e.source=o.source AND e.sequence=o.sequence WHERE o.state<>2 AND e.storedAt<=?",arrayOf((now-MailboxWire.RETENTION).toString())).use { it.moveToFirst();it.getLong(0) }
        if(expired>0) set("expired",((meta("expired")?.toLong() ?: 0)+expired).toString())
        db.execSQL("DELETE FROM mailbox_outbox WHERE EXISTS(SELECT 1 FROM events e WHERE e.source=mailbox_outbox.source AND e.sequence=mailbox_outbox.sequence AND e.storedAt<=?)",arrayOf(now-MailboxWire.RETENTION))
        db.delete("mailbox_seen","expires<=?",arrayOf(now.toString()))
        db.delete("mailbox_receipts","expires<=?",arrayOf(now.toString()))
    }
    fun summary(): Summary = locked {
        fun count(state: Int)=db.rawQuery("SELECT COUNT(*) FROM mailbox_outbox WHERE state=?",arrayOf(state.toString())).use { it.moveToFirst();it.getLong(0) }
        Summary(service()!=null,count(0),count(1),count(2),service()!=null && (cleanups().isNotEmpty() || store.settings.role==Role.SHARER && info()!=null && policyDirty() && !sharingAllowed()),meta("expired")?.toLong() ?: 0)
    }
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE mailbox_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE mailbox_peers(peer TEXT PRIMARY KEY,enabled INTEGER NOT NULL,grantjson TEXT,acceptance TEXT,confirmed INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE mailbox_cleanup(id TEXT PRIMARY KEY,peer TEXT NOT NULL,service TEXT NOT NULL,source TEXT NOT NULL,grantjson TEXT NOT NULL)")
            db.execSQL("CREATE TABLE mailbox_outbox(peer TEXT NOT NULL,source TEXT NOT NULL,sequence INTEGER NOT NULL,grantid TEXT NOT NULL,generation INTEGER NOT NULL,envelope TEXT,state INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(peer,source,sequence),FOREIGN KEY(source,sequence) REFERENCES events(source,sequence) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE mailbox_receipts(id TEXT PRIMARY KEY,source TEXT NOT NULL,grantid TEXT NOT NULL,hash TEXT NOT NULL,json TEXT NOT NULL,acked INTEGER NOT NULL,expires INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE mailbox_seen(source TEXT NOT NULL,sequence INTEGER NOT NULL,hash TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(source,sequence))")
        }
    }
}
