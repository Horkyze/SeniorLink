package family.seniorlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import family.seniorlink.core.*
import family.seniorlink.data.Secrets
import family.seniorlink.data.Store
import family.seniorlink.net.IrohSync
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real native QUIC connections, real Android SQLite, and real Keystore; synthetic data only. */
@RunWith(AndroidJUnit4::class)
class IrohDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeTransportCatchesUpTwoCaregiversAndRejectsUnapprovedPhones() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            IrohAndroid.installAndroidContext(context.applicationContext)
            val keys = List(4) { SecretKey.generate().use { it.toBytes() } }
            val ids = keys.map { bytes -> SecretKey.fromBytes(bytes).use { it.public().use { id -> id.toString() } } }
            val source = Store(context, "test-source-${UUID.randomUUID()}")
            source.updateSettings(Settings(role = Role.SHARER, unlock = true, wearable = true,
                wearableAddress = "AA:BB:CC:DD:EE:FF", wearableName = "Test Fit3"), ids[0])
            source.addPeer(ids[1], "Caregiver A", ids[0])
            source.addPeer(ids[2], "Caregiver B", ids[0])
            val endpoint = Endpoint.bind(EndpointOptions(
                secretKey = keys[0], bindAddr = "127.0.0.1:0", alpns = listOf(Wire.ALPN),
            ))
            val ready = CompletableDeferred<Unit>()
            val server = launch { IrohSync.serveEndpoint(endpoint, source, { true }) { ready.complete(Unit) } }
            ready.await()
            val addr = EndpointAddr(endpoint.id(), null, endpoint.boundSockets())
            val clients = keys.drop(1).map { IrohSync.bind(it) }
            val inboxNames = List(2) { "test-inbox-${UUID.randomUUID()}" }
            val inboxes = inboxNames.mapIndexed { i, name ->
                Store(context, name).apply {
                    updateSettings(Settings(role = Role.CAREGIVER), ids[i + 1])
                    addPeer(ids[0], "Grandfather", ids[i + 1])
                }
            }
            suspend fun sync(index: Int, inbox: Store = inboxes[index]): Boolean {
                val connection = clients[index].connect(addr, Wire.ALPN)
                try {
                    assertEquals(ids[0], connection.remoteId().use { it.toString() })
                    return catchUpPage(ids[0], inbox, IrohSync.Session(connection), System.currentTimeMillis())
                } finally {
                    connection.close(0, byteArrayOf())
                    connection.close()
                }
            }
            try {
                val now = System.currentTimeMillis()
                repeat(24) { source.append(ids[0], Event(0, Kind.UNLOCK, now + it), now) }
                val wearable = source.append(ids[0], Event(0, Kind.WEARABLE, now,
                    wearable = WearableSummary("Test Fit3", listOf(
                        WearableMetricSummary(WearableMetric.HEART_RATE, now, now, 1, 72.0, 72.0, 72.0, 72.0)))), now)
                assertTrue(sync(0))
                assertFalse(sync(0))
                assertEquals(25L, inboxes[0].cursor(ids[0]))
                assertEquals(0L, inboxes[1].cursor(ids[0]))
                // The second caregiver catches up independently after being offline.
                assertTrue(sync(1))
                assertFalse(sync(1))
                assertEquals(25, inboxes[1].recent().size)
                assertEquals(wearable, inboxes[0].wearables(ids[0]).single().event)
                assertEquals(wearable, inboxes[1].wearables(ids[0]).single().event)
                inboxes[0].close()
                Store(context, inboxNames[0]).use { reopened ->
                    assertEquals(25L, reopened.cursor(ids[0]))
                    assertFalse(sync(0, reopened))
                    assertEquals(25, reopened.recent().size)
                }
                // A valid cryptographic identity is NOT sufficient authorization.
                var denied = false
                try {
                    withTimeout(10_000) {
                        clients[2].connect(addr, Wire.ALPN).use { connection ->
                            IrohSync.Session(connection).pull(0)
                        }
                    }
                } catch (_: Exception) { denied = true }
                assertTrue("Unapproved endpoint received private history", denied)
                source.removePeer(ids[2])
                denied = false
                try { withTimeout(10_000) { sync(1) } } catch (_: Exception) { denied = true }
                assertTrue("Revoked endpoint retained access", denied)
            } finally {
                clients.forEach { it.shutdown(); it.close() }
                server.cancelAndJoin()
                addr.close()
                source.close()
                inboxes.forEach { it.close() }
            }
        }
    }

    @Test fun secretsSurviveReopenAndAreNotStoredAsPlaintext() {
        val name = "test-${UUID.randomUUID()}"
        val bytes = ByteArray(32) { (it + 1).toByte() }
        Secrets(context).write(name, bytes)
        assertArrayEquals(bytes, Secrets(context).read(name))
        val stored = java.io.File(context.noBackupFilesDir, "secrets/$name").readBytes()
        assertFalse(stored.contentEquals(bytes))
        assertTrue(stored.size > bytes.size)
    }
}
