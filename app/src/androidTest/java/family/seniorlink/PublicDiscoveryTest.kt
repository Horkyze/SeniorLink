package family.seniorlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import family.seniorlink.core.*
import family.seniorlink.data.Store
import family.seniorlink.net.IrohSync
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Requires Internet and the public iroh discovery/relay infrastructure. No personal data. */
@RunWith(AndroidJUnit4::class)
class PublicDiscoveryTest {
    @Test fun dialOnlyPublicKeyAndReceiveCheckIn() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            IrohAndroid.installAndroidContext(context.applicationContext)
            val sourceKey = SecretKey.generate().use { it.toBytes() }
            val clientKey = SecretKey.generate().use { it.toBytes() }
            val source = IrohSync.bind(sourceKey)
            val client = IrohSync.bind(clientKey)
            val sourceId = source.id().use { it.toString() }
            val clientId = client.id().use { it.toString() }
            val store = Store(context, "discovery-source-${UUID.randomUUID()}")
            val inbox = Store(context, "discovery-inbox-${UUID.randomUUID()}")
            store.updateSettings(Settings(role = Role.SHARER), sourceId)
            store.addPeer(clientId, "Test caregiver", sourceId)
            inbox.updateSettings(Settings(role = Role.CAREGIVER), clientId)
            inbox.addPeer(sourceId, "Test sharing phone", clientId)
            val now = System.currentTimeMillis()
            store.append(sourceId, Event(0, Kind.CHECK_IN, now), now)
            val server = launch { IrohSync.serveEndpoint(source, store, { true }, {}) }
            try {
                source.online()
                client.online()
                var delivered = false
                repeat(3) {
                    if (!delivered) {
                        try {
                            withTimeout(25_000) {
                                EndpointId.fromString(sourceId).use { id ->
                                    // No socket address, relay URL or endpoint ticket is supplied.
                                    EndpointAddr(id, null, emptyList()).use { address ->
                                        val connection = client.connect(address, Wire.ALPN)
                                        try {
                                            assertFalse(catchUpPage(sourceId, inbox, IrohSync.Session(connection), now))
                                            delivered = true
                                        } finally {
                                            connection.close(0, byteArrayOf())
                                            connection.close()
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            currentCoroutineContext().ensureActive()
                            delay(3000)
                        }
                    }
                }
                assertTrue("Public-key discovery or relay connection failed", delivered)
                assertEquals(Kind.CHECK_IN, inbox.recent().single().event.kind)
            } finally {
                server.cancelAndJoin()
                client.shutdown()
                client.close()
                store.close()
                inbox.close()
            }
        }
    }
}
