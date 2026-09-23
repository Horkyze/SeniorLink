package family.seniorlink

import android.app.Application
import family.seniorlink.core.*
import family.seniorlink.core.mailbox.*
import family.seniorlink.data.Store
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[26,35])
class MailboxStoreTest {
    private val source="a".repeat(64);private val caregiver="b".repeat(64)
    private val service=MailboxService("https://mailbox.example","c".repeat(64))
    private val now=System.currentTimeMillis()
    private fun db(name:String=UUID.randomUUID().toString())=Store(RuntimeEnvironment.getApplication(),name)
    // Store tests use synthetic signed containers. Signature/encryption checks are tested in core/Worker.
    private fun signed(value:ByteArray)=SignedMailbox(MailboxWire.b64(value),"0".repeat(128))
    private fun grant(grantId:String="d".repeat(64))=signed(MailboxWire.encode(MailboxGrant(service=service.id,incarnation="e".repeat(64),source=source,recipient=caregiver,grantId=grantId,key=signed(byteArrayOf()),created=now)))
    private fun source(db:Store):Store=db.apply {
        updateSettings(Settings(role=Role.SHARER,unlock=true),source);addPeer(caregiver,"Synthetic caregiver",source)
        mailbox.sharing(true);mailbox.configure(service);mailbox.enable(caregiver,true)
        mailbox.saveInfo(MailboxInfo(service=service.id,source=source,incarnation="e".repeat(64)))
        mailbox.saveGrant(caregiver,grant(),signed(byteArrayOf()))
    }
    private fun receiver(db:Store):Store=db.apply {
        updateSettings(Settings(role=Role.CAREGIVER),caregiver);addPeer(source,"Synthetic source",caregiver)
        mailbox.configure(service);mailbox.enable(source,true);mailbox.saveGrant(source,grant(),signed(byteArrayOf()))
    }
    @Test fun `mailbox event 102 cannot cause direct sync to skip event 101 and receipts survive restart`() {
        val name=UUID.randomUUID().toString()
        receiver(db(name)).withStore { d ->
            assertFalse(d.mailbox.summary().deletionPending)
            d.commit(Batch(source=source,through=100,latest=100,earliest=1,events=emptyList()),now)
            val event=Event(102,Kind.CHECK_IN,now)
            val h=MailboxHeader(service=service.id,source=source,recipient=caregiver,grantId="d".repeat(64),generation=1,keyId="f".repeat(64),messageId="1".repeat(64),expires=now+MailboxWire.RETENTION)
            d.mailbox.commitReceived(h,MailboxContent(event,now),"2".repeat(64),signed(byteArrayOf()),now)
            assertEquals(100L,d.cursor(source));assertEquals(1,d.mailbox.pendingReceipts(source).size)
            assertFails { d.mailbox.commitReceived(h.copy(messageId="4".repeat(64)),MailboxContent(event.copy(kind=Kind.UNLOCK),now),"5".repeat(64),signed(byteArrayOf()),now) }
            assertEquals("A conflicting event must never get a receipt",1,d.mailbox.pendingReceipts(source).size)
            d.commit(Batch(source=source,through=102,latest=102,earliest=1,events=listOf(Event(101,Kind.CHECK_IN,now),event)),now)
            assertEquals(102L,d.cursor(source))
            assertFails { d.commit(Batch(source=source,through=102,latest=102,earliest=1,events=listOf(event.copy(kind=Kind.UNLOCK))),now) }
        }
        db(name).withStore { d -> assertEquals(1,d.mailbox.pendingReceipts(source).size);assertEquals(102L,d.cursor(source)) }
    }
    @Test fun `event and pending upload are durable and feature withdrawal cancels ciphertext`() {
        val name=UUID.randomUUID().toString()
        source(db(name)).withStore { d ->
            assertTrue("Configuring while active must preserve active gate",d.mailbox.sharingAllowed())
            d.append(source,Event(0,Kind.UNLOCK,now),now)
            assertEquals(1,d.mailbox.outgoing().size)
        }
        db(name).withStore { d ->
            assertEquals(1,d.mailbox.outgoing().size)
            val generation=d.mailbox.generation()
            d.updateSettings(d.settings.copy(unlock=false),source)
            assertTrue(d.mailbox.outgoing().isEmpty());assertTrue(d.mailbox.generation()>generation);assertTrue(d.mailbox.policyDirty())
        }
    }
    @Test fun `stale upload cannot mark replacement grant as delivered and revocation survives peer removal`() {
        source(db()).withStore { d ->
            d.append(source,Event(0,Kind.CHECK_IN,now),now)
            val old=d.mailbox.outgoing().single()
            d.mailbox.enable(caregiver,false);d.mailbox.enable(caregiver,true)
            d.mailbox.saveGrant(caregiver,grant("3".repeat(64)),signed(byteArrayOf()))
            d.mailbox.stored(old)
            assertEquals("3".repeat(64),d.mailbox.outgoing().single().grantId)
            d.removePeer(caregiver)
            assertEquals(2,d.mailbox.cleanups().size);assertTrue(d.mailbox.outgoing().isEmpty())
        }
    }
    @Test fun `pause keeps durable purge and stale policy response cannot undo it`() {
        source(db()).withStore { d ->
            val policy=d.mailbox.nextPolicy(now)!!
            d.mailbox.sharing(false);d.mailbox.policySent(policy)
            assertTrue(d.mailbox.policyDirty());assertFalse(d.mailbox.sharingAllowed())
            assertTrue(d.mailbox.summary().deletionPending)
            val disabled=d.mailbox.nextPolicy(now)!!;assertFalse(disabled.enabled)
            d.mailbox.policySent(disabled);assertFalse(d.mailbox.policyDirty())
        }
    }
    @Test fun `v2 migration preserves approved phones and event history`() {
        val name=UUID.randomUUID().toString()
        db(name).withStore { d ->
            d.updateSettings(Settings(role=Role.SHARER),source);d.addPeer(caregiver,"Caregiver",source)
            d.append(source,Event(0,Kind.CHECK_IN,now),now)
            listOf("mailbox_outbox","mailbox_receipts","mailbox_seen","mailbox_peers","mailbox_cleanup","mailbox_meta").forEach { d.writableDatabase.execSQL("DROP TABLE $it") }
            d.writableDatabase.version=2
        }
        db(name).withStore { d -> assertTrue(d.approved(caregiver));assertEquals(1L,d.latest(source));assertFalse(d.mailbox.summary().configured) }
    }
    @Test fun `switching services preserves monotonic policies and permits explicit empty setup reset`() {
        source(db()).withStore { d ->
            val previous=d.mailbox.nextPolicy(now)!!
            d.mailbox.enable(caregiver,false)
            d.mailbox.cleanups().forEach { d.mailbox.cleaned(it.grantId) }
            d.mailbox.configure(MailboxService("https://second.example",service.publicKey))
            d.mailbox.configure(service)
            assertNull(d.mailbox.info())
            d.mailbox.saveInfo(MailboxInfo(service=service.id,source=source,incarnation="e".repeat(64)))
            val next=d.mailbox.nextPolicy(now)!!
            assertTrue(next.revision>previous.revision);assertTrue(next.generation>previous.generation)
            d.mailbox.configure(service);assertNull(d.mailbox.info())
        }
    }
    @Test fun `retention expiry is counted before cascading history removal`() {
        source(db()).withStore { d ->
            d.append(source,Event(0,Kind.CHECK_IN,now),now)
            d.batch(source,0,now+MailboxWire.RETENTION+1)
            assertEquals(1L,d.mailbox.summary().expired)
            assertTrue(d.mailbox.outgoing().isEmpty())
        }
    }
    private inline fun <T> Store.withStore(block:(Store)->T): T = try { block(this) } finally { close() }
    private fun assertFails(block:()->Unit) { try { block();fail("Expected rejection") } catch(_: IllegalArgumentException) { } }
}
