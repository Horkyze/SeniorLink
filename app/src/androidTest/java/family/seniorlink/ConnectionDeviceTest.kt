package family.seniorlink

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import family.seniorlink.core.*
import family.seniorlink.data.Store
import family.seniorlink.net.IrohSync
import family.seniorlink.pairing.IrohPairing
import family.seniorlink.pairing.IrohPairingKeys
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConnectionDeviceTest {
    @Test fun oneScanVerifiesPermanentIdentitiesAndEnablesEncryptedCatchUp() = runBlocking(Dispatchers.IO) {
        withTimeout(90_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            IrohAndroid.installAndroidContext(context.applicationContext)
            val secrets = List(2) { SecretKey.generate().use { it.toBytes() } }
            val keys = secrets.map(::IrohPairingKeys)
            val stores = List(2) { Store(context, "pair-test-${UUID.randomUUID()}") }
            stores[0].updateSettings(Settings(role = Role.SHARER), keys[0].publicId)
            stores[1].updateSettings(Settings(role = Role.CAREGIVER), keys[1].publicId)
            val peers = stores.mapIndexed { i, store -> object : PairingPeers {
                override fun contains(id: String) = store.approved(id)
                override fun save(id: String, name: String) = store.addPeer(id, name, keys[i].publicId)
                override fun remove(id: String) = store.removePeer(id)
            } }
            val pairEndpoints = List(2) { Endpoint.bind(EndpointOptions(
                bindAddr = "127.0.0.1:0", alpns = listOf(ConnectionPairing.ALPN),
            )) }
            val syncEndpoints = secrets.map { Endpoint.bind(EndpointOptions(
                secretKey = it, bindAddr = "127.0.0.1:0", alpns = listOf(Wire.ALPN),
            )) }
            val nonce = ConnectionPairing.randomHex()
            val invite = IrohPairing.invitation(pairEndpoints[0], keys[0], "Grandad", nonce)
            assertNotEquals(invite.publicId, invite.endpointId)
            val host = ConnectionHost(invite, keys[0], peers[0], SystemClock::elapsedRealtime, nonce)
            val scanned = ConnectionPairing.parse(ConnectionPairing.code(invite), keys[1].publicId)
            val hello = ConnectionHello(
                invitation = ConnectionPairing.inviteId(scanned), publicId = keys[1].publicId,
                endpointId = pairEndpoints[1].id().use { it.toString() }, name = "Anna", nonce = ConnectionPairing.randomHex(),
            )
            val client = ConnectionClient(scanned, hello, keys[1], peers[1])
            val pairingServer = launch { IrohPairing.serve(pairEndpoints[0], host) {} }
            val syncServer = launch { IrohSync.serveEndpoint(syncEndpoints[0], stores[0], { true }) {} }
            suspend fun exchange() = IrohPairing.exchange(pairEndpoints[1], scanned, client.request())
            suspend fun sync() = syncEndpoints[0].addr().use { addr ->
                val connection = syncEndpoints[1].connect(addr, Wire.ALPN)
                try { catchUpPage(keys[0].publicId, stores[1], IrohSync.Session(connection), System.currentTimeMillis()) }
                finally { connection.close(0, byteArrayOf()); connection.close() }
            }
            try {
                assertEquals(ConnectionStatus.WAITING, client.receive(exchange()))
                assertEquals(host.verification(), client.verification())
                assertTrue(stores.all { it.peers().isEmpty() })
                var denied = false
                try {
                    withTimeout(5000) {
                        syncEndpoints[0].addr().use { addr ->
                            syncEndpoints[1].connect(addr, Wire.ALPN).use { IrohSync.Session(it).pull(0) }
                        }
                    }
                } catch (_: Exception) { denied = true }
                assertTrue("An unapproved phone received history", denied)
                host.approve(ConnectionPairing.requestId(hello))
                exchange() // Approval reply lost before the caregiver reads it.
                assertEquals(ConnectionStatus.APPROVED, client.receive(exchange()))
                exchange() // Final receipt lost after both stores committed.
                assertEquals(ConnectionStatus.COMPLETE, client.receive(exchange()))
                assertEquals(keys[1].publicId, stores[0].peers().single().id)
                assertEquals(keys[0].publicId, stores[1].peers().single().id)
                val now = System.currentTimeMillis()
                stores[0].append(keys[0].publicId, Event(0, Kind.CHECK_IN, now), now)
                assertFalse(sync())
                assertEquals(Kind.CHECK_IN, stores[1].recent().single().event.kind)
                stores[0].removePeer(keys[1].publicId)
                assertEquals(ConnectionStatus.DECLINED, client.receive(exchange()))
                assertTrue(stores[0].peers().isEmpty())
            } finally {
                pairingServer.cancelAndJoin()
                syncServer.cancelAndJoin()
                pairEndpoints.forEach { IrohPairing.close(it) }
                IrohPairing.close(syncEndpoints[1])
                stores.forEach { it.close() }
            }
        }
    }
}
