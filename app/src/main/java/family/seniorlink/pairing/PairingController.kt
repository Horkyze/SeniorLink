package family.seniorlink.pairing

import android.os.SystemClock
import family.seniorlink.core.*
import family.seniorlink.data.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PairingStep { IDLE, PREPARING, QR, VERIFY, SAVING, CONNECTED, DECLINED, EXPIRED, ERROR }

data class PairingState(
    val step: PairingStep = PairingStep.IDLE,
    val hosting: Boolean = false,
    val qr: String = "",
    val verification: String = "",
    val peerName: String = "",
    val requestId: String = "",
    val detail: String = "",
)

/** Survives rotation; invitations end after five minutes or when this ViewModel is destroyed. */
class PairingController(
    private val scope: CoroutineScope,
    private val store: Store,
    private val identity: () -> ByteArray,
) {
    private val mutable = MutableStateFlow(PairingState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var host: ConnectionHost? = null
    @Volatile private var client: ConnectionClient? = null
    @Volatile private var dismissed = false

    private fun peers(ownId: String) = object : PairingPeers {
        override fun contains(id: String) = store.approved(id)
        override fun save(id: String, name: String) = store.addPeer(id, name, ownId)
        override fun remove(id: String) = store.removePeer(id)
    }

    fun showQr(name: String) {
        require(store.settings.role == Role.SHARER)
        ConnectionPairing.validateName(name.trim())
        start(hosting = true) { attempt ->
            val keys = IrohPairingKeys(identity())
            val endpoint = IrohPairing.bind()
            try {
                check(withTimeoutOrNull(30_000) { endpoint.online(); true } == true) {
                    "Couldn't get online. Check the internet connection, then tap Connect a caregiver again."
                }
                val nonce = ConnectionPairing.randomHex()
                val invite = IrohPairing.invitation(endpoint, keys, name.trim(), nonce)
                val session = ConnectionHost(invite, keys, peers(keys.publicId), SystemClock::elapsedRealtime, nonce)
                host = session
                update(attempt, PairingState(PairingStep.QR, hosting = true, qr = ConnectionPairing.code(invite)))
                IrohPairing.serve(endpoint, session) { publishHost(attempt, session) }
            } finally {
                IrohPairing.close(endpoint)
            }
        }
    }

    fun connect(code: String, name: String, ownId: String) {
        require(store.settings.role == Role.CAREGIVER)
        ConnectionPairing.validateName(name.trim())
        val invite = ConnectionPairing.parse(code, ownId)
        require(store.approved(invite.publicId) || store.peers().size < 8) {
            "Eight phones are already connected. Remove an unused phone from Phones, then try again."
        }
        // Validate the ticket before replacing a valid in-progress attempt.
        computer.iroh.EndpointTicket.fromString(invite.ticket).use { }
        start(hosting = false) { attempt ->
            val keys = IrohPairingKeys(identity())
            val endpoint = IrohPairing.bind()
            try {
                val hello = ConnectionHello(
                    invitation = ConnectionPairing.inviteId(invite), publicId = keys.publicId,
                    endpointId = endpoint.id().use { it.toString() }, name = name.trim(), nonce = ConnectionPairing.randomHex(),
                )
                val session = ConnectionClient(invite, hello, keys, peers(keys.publicId))
                client = session
                var failures = 0
                while (currentCoroutineContext().isActive) {
                    val reply = try {
                        IrohPairing.exchange(endpoint, invite, session.request())
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        failures++
                        if (failures >= 3) throw IllegalStateException(
                            if (session.saved) "Approval was saved. Open Phones on both devices to check the connection."
                            else "We couldn't reach the other phone. Keep both apps open, check the internet connection, then show a new QR.",
                        )
                        mutable.update { if (generation == attempt) it.copy(detail = "Connection interrupted. Reconnecting… Keep both apps open.") else it }
                        delay(1500)
                        continue
                    }
                    failures = 0
                    val result = session.receive(reply)
                    if (session.cancelled && reply.reply.status != ConnectionStatus.DECLINED) {
                        delay(100)
                        continue
                    }
                    val step = when (result) {
                        ConnectionStatus.WAITING -> PairingStep.VERIFY
                        ConnectionStatus.APPROVED -> PairingStep.SAVING
                        ConnectionStatus.COMPLETE -> PairingStep.CONNECTED
                        ConnectionStatus.DECLINED, ConnectionStatus.BUSY -> PairingStep.DECLINED
                        ConnectionStatus.EXPIRED -> PairingStep.EXPIRED
                    }
                    update(attempt, PairingState(
                        step, peerName = invite.name, verification = session.verification(),
                        detail = if (result == ConnectionStatus.BUSY) "Another phone is using this QR. Ask for a new one." else "",
                    ))
                    if (step in listOf(PairingStep.CONNECTED, PairingStep.DECLINED, PairingStep.EXPIRED)) return@start
                    delay(if (session.saved) 100 else 1000)
                }
            } finally {
                IrohPairing.close(endpoint)
            }
        }
    }

    fun approve(requestId: String) {
        val attempt = generation
        val session = host ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val peer = session.hello?.publicId
                require(peer != null && (store.approved(peer) || store.peers().size < 8)) {
                    "Eight phones are already connected. Remove an unused phone from Phones, then try again."
                }
                session.approve(requestId)
                publishHost(attempt, session)
            } catch (e: IllegalArgumentException) {
                mutable.update { if (generation == attempt) it.copy(detail = e.message.orEmpty()) else it }
            } catch (_: Exception) {
                mutable.update { if (generation == attempt) it.copy(detail = "This request is no longer available. Show a new QR and try again.") else it }
            }
        }
    }

    fun decline() {
        val session = host
        val request = client
        if (session != null || request != null) {
            session?.decline()
            request?.cancel()
            mutable.update { it.copy(step = PairingStep.DECLINED) }
            // Keep serving the signed rejection so the caregiver sees it on its next poll.
        } else reset()
    }

    fun reset() {
        host?.decline()
        generation++
        job?.cancel()
        host = null
        client = null
        mutable.value = PairingState()
    }

    fun dismiss() {
        if (mutable.value.step == PairingStep.SAVING) return
        if (mutable.value.step in listOf(PairingStep.CONNECTED, PairingStep.DECLINED)) {
            dismissed = true // Keep the receipt/rejection available for a reconnect until expiry.
            mutable.value = PairingState()
        } else reset()
    }

    private fun publishHost(attempt: Long, session: ConnectionHost) {
        val hello = session.hello ?: return
        val step = when (session.status) {
            ConnectionStatus.WAITING -> PairingStep.VERIFY
            ConnectionStatus.APPROVED -> PairingStep.SAVING
            ConnectionStatus.COMPLETE -> PairingStep.CONNECTED
            ConnectionStatus.DECLINED, ConnectionStatus.BUSY -> PairingStep.DECLINED
            ConnectionStatus.EXPIRED -> PairingStep.EXPIRED
        }
        update(attempt, PairingState(
            step, hosting = true, verification = session.verification(), peerName = hello.name,
            requestId = ConnectionPairing.requestId(hello),
        ))
    }

    private fun update(attempt: Long, state: PairingState) {
        mutable.update { if (generation == attempt && !dismissed) state else it }
    }

    private fun start(hosting: Boolean, block: suspend (Long) -> Unit) {
        val previous = job
        reset()
        dismissed = false
        val attempt = generation
        mutable.value = PairingState(PairingStep.PREPARING, hosting)
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            host = null
            client = null
            try {
                withTimeout(ConnectionPairing.LIFETIME_MS) { block(attempt) }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                mutable.update {
                    if (generation != attempt || dismissed || it.step == PairingStep.CONNECTED) it
                    else it.copy(step = PairingStep.EXPIRED, detail = "This connection attempt expired. Show a new QR and try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { if (generation == attempt && !dismissed) it.copy(
                    step = PairingStep.ERROR,
                    detail = if (e is IllegalStateException) e.message.orEmpty()
                    else "Couldn't connect safely. Keep both apps open and try again with a new QR.",
                ) else it }
            }
        }
    }
}
