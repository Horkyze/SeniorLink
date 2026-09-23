package family.seniorlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import family.seniorlink.core.*
import family.seniorlink.core.mailbox.*
import family.seniorlink.data.Store
import family.seniorlink.mailbox.IrohMailboxIdentity
import family.seniorlink.net.IrohSync
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android HPKE + native iroh signatures/ALPN, using isolated synthetic stores. */
@RunWith(AndroidJUnit4::class)
class MailboxDeviceTest {
    @Test fun mailboxControlAndLegacySyncShareThePermanentEndpoint() = runBlocking(Dispatchers.IO) {
        withTimeout(60_000) {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            IrohAndroid.installAndroidContext(context.applicationContext)
            val sourceSecret=SecretKey.generate().use { it.toBytes() }
            val recipientSecret=SecretKey.generate().use { it.toBytes() }
            val source=IrohMailboxIdentity(sourceSecret);val recipient=IrohMailboxIdentity(recipientSecret)
            val store=Store(context,"mailbox-native-${UUID.randomUUID()}")
            store.updateSettings(Settings(role=Role.SHARER),source.id)
            store.addPeer(recipient.id,"Synthetic caregiver",source.id)
            val now=System.currentTimeMillis()
            val event=store.append(source.id,Event(0,Kind.CHECK_IN,now),now)
            val key=MailboxCrypto.generateKey()
            val descriptor=MailboxWire.sign(recipient,"key",MailboxWire.encode(MailboxKey(owner=recipient.id,keyId=MailboxWire.hash(key.publicKey),publicKey=MailboxWire.b64(key.publicKey),created=now,expires=now+MailboxWire.RETENTION)))
            val grant=MailboxGrant(service="a".repeat(64),incarnation="b".repeat(64),source=source.id,recipient=recipient.id,grantId="c".repeat(64),key=descriptor,created=now)
            val header=MailboxHeader(service=grant.service,source=source.id,recipient=recipient.id,grantId=grant.grantId,generation=1,keyId=MailboxWire.hash(key.publicKey),messageId=MailboxWire.randomId(),expires=now+MailboxWire.RETENTION)
            val envelope=MailboxCrypto.seal(source,header,key.publicKey,MailboxContent(event,now))
            val endpoint=Endpoint.bind(EndpointOptions(secretKey=sourceSecret,bindAddr="127.0.0.1:0",alpns=listOf(Wire.ALPN,MailboxWire.CONTROL_ALPN)))
            val address=EndpointAddr(endpoint.id(),null,endpoint.boundSockets())
            val client=Endpoint.bind(EndpointOptions(secretKey=recipientSecret,bindAddr="127.0.0.1:0"))
            val enabled=java.util.concurrent.atomic.AtomicBoolean(true)
            val controls=java.util.concurrent.atomic.AtomicInteger()
            val server=launch {
                IrohSync.serveEndpoint(endpoint,store,enabled::get,control={ connection,remote,allowed ->
                    assertEquals(recipient.id,remote);assertTrue(allowed());controls.incrementAndGet()
                    connection.acceptBi().use { stream ->
                        stream.recv().use { assertArrayEquals(byteArrayOf(1),it.readToEnd(10u)) }
                        stream.send().use { it.writeAll(MailboxWire.encode(envelope));it.finish();it.stopped() }
                    }
                },status={})
            }
            try {
                client.connect(address,MailboxWire.CONTROL_ALPN).use { connection ->
                    connection.openBi().use { stream ->
                        stream.send().use { it.writeAll(byteArrayOf(1));it.finish() }
                        val received=stream.recv().use { MailboxWire.decode<MailboxEnvelope>(it.readToEnd(65536u)) }
                        assertEquals(event,MailboxCrypto.open(recipient,grant,key,received,now).second.event)
                    }
                }
                client.connect(address,Wire.ALPN).use { connection ->
                    assertEquals(event,IrohSync.Session(connection).pull(0).events.single())
                }
                enabled.set(false)
                var denied=false
                try { withTimeout(5_000) { client.connect(address,MailboxWire.CONTROL_ALPN).use { connection ->
                    connection.openBi().use { stream -> stream.send().use { it.writeAll(byteArrayOf(1));it.finish() };stream.recv().use { it.readToEnd(65536u) } }
                } } } catch(_: Exception) { denied=true }
                assertTrue(denied);assertEquals(1,controls.get())
            } finally {
                client.shutdown();client.close();server.cancelAndJoin();address.close();store.close()
            }
        }
    }
}
