package family.seniorlink.net

import computer.iroh.*
import family.seniorlink.core.*
import family.seniorlink.data.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/** All networking is owned by a service scope (sharer) or visible-activity scope (caregiver). */
object IrohSync {
    suspend fun bind(secret: ByteArray): Endpoint = Endpoint.bind(
        EndpointOptions(secretKey = secret, preset = presetN0(), alpns = listOf(Wire.ALPN)),
    )

    suspend fun serve(
        secret: ByteArray,
        store: Store,
        enabled: () -> Boolean,
        status: (String) -> Unit,
    ): Unit = serveEndpoint(bind(secret), store, enabled, status)

    /** Takes ownership of the endpoint and shuts it down when the service scope ends. */
    suspend fun serveEndpoint(
        endpoint: Endpoint,
        store: Store,
        enabled: () -> Boolean,
        status: (String) -> Unit,
    ): Unit = supervisorScope {
        val connections = ConcurrentHashMap<Connection, String>()
        val slots = Semaphore(8)
        val watcher = launch {
            store.changes.collect {
                connections.forEach { (connection, peer) ->
                    if (!enabled() || !store.approved(peer)) connection.close(1, "Access withdrawn".toByteArray())
                }
            }
        }
        try {
            status("Sharing endpoint ready; caregivers can connect")
            while (isActive) {
                val incoming = endpoint.acceptNext() ?: break
                if (!slots.tryAcquire()) {
                    incoming.refuse()
                    incoming.close()
                    continue
                }
                launch {
                    var connection: Connection? = null
                    try {
                        connection = withTimeout(15_000) {
                            incoming.use { it.accept().use { accepting -> accepting.connect() } }
                        }
                        val peer = connection.remoteId().use { it.toString() }
                        if (!enabled() || !store.approved(peer)) return@launch
                        connections[connection] = peer
                        serveConnection(connection, endpoint.id().use { it.toString() }, peer, store, enabled)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // No payloads, private keys, SMS, or peer identifiers in logs.
                    } finally {
                        connection?.let {
                            connections.remove(it)
                            runCatching { it.close(0, byteArrayOf()) }
                            it.close()
                        }
                        slots.release()
                    }
                }
            }
        } finally {
            watcher.cancel()
            withContext(NonCancellable) {
                endpoint.shutdown()
                endpoint.close()
            }
        }
    }

    private suspend fun serveConnection(
        connection: Connection,
        source: String,
        peer: String,
        store: Store,
        enabled: () -> Boolean,
    ) {
        withTimeout(30_000) {
            val batch = connection.acceptBi().use { stream ->
                val request = stream.recv().use {
                    Wire.decode<Pull>(it.readToEnd(Wire.MAX_REQUEST.toUInt()), Wire.MAX_REQUEST)
                }
                request.validate()
                check(enabled() && store.approved(peer))
                val batch = store.batch(source, request.after, System.currentTimeMillis())
                val bytes = Wire.encode(batch)
                require(bytes.size <= Wire.MAX_RESPONSE)
                stream.send().use { it.writeAll(bytes); it.finish() }
                batch
            }
            connection.acceptBi().use { stream ->
                val ack = stream.recv().use {
                    Wire.decode<Ack>(it.readToEnd(Wire.MAX_REQUEST.toUInt()), Wire.MAX_REQUEST)
                }
                require(ack.version == Wire.VERSION && ack.through == batch.through)
                check(enabled() && store.approved(peer))
                store.acknowledge(peer, ack.through, System.currentTimeMillis())
                stream.send().use {
                    it.writeAll(Wire.encode(Receipt(through = ack.through)))
                    it.finish()
                    // Wait for receipt delivery before closing the connection.
                    it.stopped()
                }
            }
        }
    }

    suspend fun receiveWhileOpen(
        secret: ByteArray,
        store: Store,
        status: (String, String) -> Unit,
    ): Unit = supervisorScope {
        val endpoint = bind(secret)
        val jobs = mutableMapOf<String, Job>()
        try {
            store.changes.collect {
                val peers = store.peers().map { it.id }.toSet()
                jobs.keys.filter { it !in peers }.forEach { jobs.remove(it)?.cancel() }
                for (peer in peers) if (jobs[peer]?.isActive != true) {
                    jobs[peer] = launch {
                        var backoff = 3_000L
                        while (isActive && store.approved(peer)) {
                            try {
                                status(peer, "Connecting…")
                                val more = withTimeout(35_000) {
                                    EndpointId.fromString(peer).use { id ->
                                        EndpointAddr(id, null, emptyList()).use { addr ->
                                            val connection = endpoint.connect(addr, Wire.ALPN)
                                            try {
                                                check(connection.remoteId().use { it.toString() } == peer)
                                                catchUpPage(peer, store, Session(connection), System.currentTimeMillis())
                                            } finally {
                                                connection.close(0, byteArrayOf())
                                                connection.close()
                                            }
                                        }
                                    }
                                }
                                status(peer, if (more) "Fetching history…" else "Up to date")
                                backoff = 3_000
                                delay(if (more) 100 else 15_000)
                            } catch (e: CancellationException) {
                                // A per-attempt timeout is recoverable; lifecycle cancellation is not.
                                currentCoroutineContext().ensureActive()
                                status(peer, "Unreachable — showing cached information")
                                delay(backoff)
                                backoff = (backoff * 2).coerceAtMost(60_000)
                            } catch (_: Exception) {
                                status(peer, "Not connected — check approval and update SeniorLink on both phones")
                                delay(backoff)
                                backoff = (backoff * 2).coerceAtMost(60_000)
                            }
                        }
                    }
                }
            }
        } finally {
            jobs.values.forEach { it.cancel() }
            withContext(NonCancellable) {
                endpoint.shutdown()
                endpoint.close()
            }
        }
    }

    class Session(private val connection: Connection) : Exchange {
        override suspend fun pull(after: Long): Batch = request(Pull(after = after), Wire.MAX_RESPONSE)
        override suspend fun acknowledge(through: Long): Receipt = request(Ack(through = through), Wire.MAX_REQUEST)

        private suspend inline fun <reified Q, reified R> request(value: Q, limit: Int): R =
            connection.openBi().use { stream ->
                stream.send().use { it.writeAll(Wire.encode(value)); it.finish() }
                stream.recv().use { Wire.decode<R>(it.readToEnd(limit.toUInt()), limit) }
            }
    }
}
