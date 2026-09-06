package family.seniorlink.pairing

import computer.iroh.*
import family.seniorlink.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

class IrohPairingKeys(private val identity: ByteArray) : PairingKeys {
    override val publicId = SecretKey.fromBytes(identity).use { key -> key.public().use { it.toString() } }
    override fun sign(bytes: ByteArray): String = SecretKey.fromBytes(identity).use { key ->
        key.sign(bytes).use { ConnectionPairing.hex(it.toBytes()) }
    }
    override fun verify(publicId: String, bytes: ByteArray, signature: String) {
        EndpointId.fromString(publicId).use { key ->
            Signature.fromBytes(ConnectionPairing.unhex(signature)).use { key.verify(bytes, it) }
        }
    }
}

/** Temporary endpoints never publish or replace the permanent monitoring identity. */
object IrohPairing {
    suspend fun bind(): Endpoint = Endpoint.bind(EndpointOptions(
        preset = presetN0(), alpns = listOf(ConnectionPairing.ALPN),
    ))

    fun invitation(endpoint: Endpoint, keys: PairingKeys, name: String, nonce: String): ConnectionInvite =
        ConnectionInvite(
            publicId = keys.publicId, name = name,
            endpointId = endpoint.id().use { it.toString() },
            ticket = endpoint.addr().use { addr -> EndpointTicket.fromAddr(addr).use { it.toString() } },
            token = ConnectionPairing.randomHex(16),
            commitment = ConnectionPairing.digest(nonce.toByteArray()),
        )

    suspend fun serve(endpoint: Endpoint, host: ConnectionHost, changed: () -> Unit): Unit = supervisorScope {
        val slots = Semaphore(4)
        while (isActive && !host.expired()) {
            val incoming = endpoint.acceptNext() ?: break
            if (!slots.tryAcquire()) {
                incoming.refuse()
                incoming.close()
                continue
            }
            launch {
                var connection: Connection? = null
                try {
                    withTimeout(10_000) {
                        connection = incoming.use { it.accept().use { accepting -> accepting.connect() } }
                        val active = checkNotNull(connection)
                        val remoteId = active.remoteId().use { it.toString() }
                        active.acceptBi().use { stream ->
                            val request = stream.recv().use {
                                Wire.decode<SignedConnectionCall>(it.readToEnd(ConnectionPairing.MAX_MESSAGE.toUInt()), ConnectionPairing.MAX_MESSAGE)
                            }
                            val reply = host.respond(request, remoteId)
                            changed()
                            stream.send().use {
                                it.writeAll(Wire.encode(reply))
                                it.finish()
                                it.stopped()
                            }
                        }
                    }
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    // Untrusted/unfinished requests do not change the invitation or disclose data.
                } finally {
                    connection?.let {
                        runCatching { it.close(0, byteArrayOf()) }
                        it.close()
                    }
                    slots.release()
                }
            }
        }
    }

    suspend fun exchange(endpoint: Endpoint, invite: ConnectionInvite, request: SignedConnectionCall): SignedConnectionReply =
        withTimeout(10_000) {
            EndpointTicket.fromString(invite.ticket).use { ticket ->
                ticket.endpointAddr().use { address ->
                    val connection = endpoint.connect(address, ConnectionPairing.ALPN)
                    try {
                        require(connection.remoteId().use { it.toString() } == invite.endpointId)
                        val reply = connection.openBi().use { stream ->
                            stream.send().use { it.writeAll(Wire.encode(request)); it.finish() }
                            stream.recv().use {
                                Wire.decode<SignedConnectionReply>(it.readToEnd(ConnectionPairing.MAX_MESSAGE.toUInt()), ConnectionPairing.MAX_MESSAGE)
                            }
                        }
                        // Let QUIC acknowledge the response before closing locally. Otherwise
                        // repeated polls can leave the server waiting for delivery in every slot.
                        withTimeoutOrNull(2000) { connection.closed() }
                        reply
                    } finally {
                        connection.close(0, byteArrayOf())
                        connection.close()
                    }
                }
            }
        }

    suspend fun close(endpoint: Endpoint) = withContext(NonCancellable) {
        try { endpoint.shutdown() } finally { endpoint.close() }
    }
}
