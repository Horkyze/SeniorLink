package family.seniorlink.mailbox

import computer.iroh.*
import family.seniorlink.SeniorApp
import family.seniorlink.core.Role
import family.seniorlink.core.mailbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MailboxCoordinator(private val app: SeniorApp) {
    val statuses=MutableStateFlow<Map<String,String>>(emptyMap())
    val summary=MutableStateFlow(MailboxStore.Summary())
    private val local get()=app.store.mailbox
    private val identity by lazy { IrohMailboxIdentity(app.identity) }
    private val http by lazy { MailboxHttp(identity) }
    private val cleanupLock=Mutex()
    private val setupLock=Mutex()
    private fun report(peer: String, text: String) { statuses.update { it+(peer to text) }; refresh() }
    fun refresh() { summary.value=local.summary() }
    fun configure(service: MailboxService) { local.configure(service);refresh() }
    fun consent(peer: String, enabled: Boolean) { local.enable(peer,enabled);report(peer,if(enabled) "Open both phones to finish mailbox setup" else "Queued delivery disabled; pending deletion will retry") }
    private fun key(): MailboxCrypto.Key = synchronized(app.secrets) {
        val saved=app.secrets.read("mailbox-x25519")
        if(saved!=null) { require(saved.size==64);MailboxCrypto.Key(saved.copyOfRange(0,32),saved.copyOfRange(32,64)) }
        else MailboxCrypto.generateKey().also { app.secrets.write("mailbox-x25519",it.privateKey+it.publicKey) }
    }
    private fun keyDescriptor(): SignedMailbox = synchronized(app.secrets) {
        val saved=app.secrets.read("mailbox-key-descriptor")
        val old=saved?.let { MailboxWire.decode<SignedMailbox>(it) }
        val oldKey=old?.let { MailboxWire.decode<MailboxKey>(MailboxWire.unb64(it.payload)) }
        if(oldKey!=null && oldKey.expires>System.currentTimeMillis()) old
        else {
            val now=System.currentTimeMillis(); val public=key().publicKey
            MailboxWire.sign(identity,"key",MailboxWire.encode(MailboxKey(owner=identity.id,keyId=MailboxWire.hash(public),publicKey=MailboxWire.b64(public),revision=(oldKey?.revision ?: 0)+1,created=now,expires=now+365L*24*60*60*1000)))
                .also { app.secrets.write("mailbox-key-descriptor",MailboxWire.encode(it)) }
        }
    }
    private fun grant(value: SignedMailbox, source: String): MailboxGrant = MailboxWire.decode<MailboxGrant>(MailboxWire.verify(identity,source,"grant",value)).also {
        require(it.version==1 && it.source==source && MailboxWire.isId(it.grantId) && MailboxWire.isId(it.incarnation))
    }
    private fun registration(peer: MailboxStore.PeerState) = MailboxRegistration(requireNotNull(peer.grant),requireNotNull(peer.acceptance))
    /** Served only over the existing source identity's endpoint, after its approval/session checks. */
    suspend fun control(connection: Connection, remote: String, enabled: () -> Boolean) = setupLock.withLock {
        withTimeout(15_000) {
            connection.acceptBi().use { stream ->
                val request=stream.recv().use { MailboxWire.decode<MailboxControl>(it.readToEnd(16384u)) }
                val response=synchronized(app.store) {
                    check(enabled() && local.sharingAllowed() && local.enabled(remote))
                    require(request.version==1 && request.service==local.service())
                    val descriptor=MailboxWire.decode<MailboxKey>(MailboxWire.verify(identity,remote,"key",request.key))
                    val now=System.currentTimeMillis()
                    require(descriptor.version==1 && descriptor.owner==remote && MailboxWire.isId(descriptor.keyId) && descriptor.revision>0 && descriptor.expires>now && descriptor.created<=now+120000 && MailboxWire.unb64(descriptor.publicKey).size==32)
                    val info=requireNotNull(local.info()) { "Mailbox provisioning pending" }
                    val current=local.peer(remote)?.grant ?: MailboxWire.sign(identity,"grant",MailboxWire.encode(MailboxGrant(
                        service=info.service,incarnation=info.incarnation,source=identity.id,recipient=remote,grantId=MailboxWire.randomId(),key=request.key,created=now)))
                        .also { local.saveGrant(remote,it) }
                    val doc=grant(current,identity.id);require(doc.key==request.key && doc.service==request.service.id)
                    request.acceptance?.let { acceptance ->
                        val a=MailboxWire.decode<MailboxAcceptance>(MailboxWire.verify(identity,remote,"acceptance",acceptance))
                        require(a.version==1 && a.source==identity.id && a.recipient==remote && a.grantHash==MailboxWire.hash(MailboxWire.unb64(current.payload)))
                        local.saveGrant(remote,current,acceptance)
                    }
                    MailboxOffer(grant=current)
                }
                stream.send().use { it.writeAll(MailboxWire.encode(response));it.finish();it.stopped() }
            }
        };refresh()
    }
    /** Called from the caregiver's already-owned endpoint; old phones simply reject this ALPN. */
    suspend fun setup(endpoint: Endpoint, source: String) {
        if (!local.enabled(source) || local.peer(source)?.confirmed==true) return
        val service=local.service() ?: return
        try {
            withTimeout(12_000) {
                val descriptor=keyDescriptor()
                suspend fun exchange(acceptance: SignedMailbox?): MailboxOffer = EndpointId.fromString(source).use { id ->
                    EndpointAddr(id,null,emptyList()).use { addr ->
                        val connection=endpoint.connect(addr,MailboxWire.CONTROL_ALPN)
                        try {
                            require(connection.remoteId().use { it.toString() }==source)
                            connection.openBi().use { stream ->
                                stream.send().use { it.writeAll(MailboxWire.encode(MailboxControl(service=service,key=descriptor,acceptance=acceptance)));it.finish() }
                                stream.recv().use { MailboxWire.decode<MailboxOffer>(it.readToEnd(16384u)) }
                            }
                        } finally { connection.close(0,byteArrayOf());connection.close() }
                    }
                }
                val offer=exchange(null); require(offer.version==1)
                val doc=grant(offer.grant,source)
                require(doc.service==service.id && doc.recipient==identity.id && doc.key==descriptor)
                val acceptance=MailboxWire.sign(identity,"acceptance",MailboxWire.encode(MailboxAcceptance(grantHash=MailboxWire.hash(MailboxWire.unb64(offer.grant.payload)),source=source,recipient=identity.id)))
                synchronized(app.store) { check(local.enabled(source));local.saveGrant(source,offer.grant,acceptance) }
                val confirmed=exchange(acceptance);require(confirmed.grant==offer.grant)
                local.confirmed(source,offer.grant)
                report(source,"Mailbox setup complete")
            }
        } catch(e: CancellationException) { currentCoroutineContext().ensureActive();report(source,"Mailbox setup waiting for both phones") }
        catch(_: Exception) { report(source,"Mailbox setup waiting; check both phones' settings") }
    }
    suspend fun cleanup() = cleanupLock.withLock {
        local.prune(System.currentTimeMillis())
        for (item in local.cleanups()) {
            try {
                val close=MailboxWire.sign(identity,"close",MailboxWire.encode(MailboxClose(service=item.service.id,source=item.source,grantId=item.grantId,grant=item.grant)))
                http.call(item.service,item.source,"close",MailboxRequest(close=close));local.cleaned(item.grantId)
            } catch(e: MailboxHttp.Error) { if(e.code in setOf("REVOKED", "STALE_INCARNATION")) local.cleaned(item.grantId) }
        }
        if(app.store.settings.role==Role.SHARER && !local.sharingAllowed()) {
            val service=local.service()
            val policy=local.nextPolicy(System.currentTimeMillis())
            if(service!=null && policy!=null) {
                http.call(service,identity.id,"policy",MailboxRequest(policy=MailboxWire.sign(identity,"policy",MailboxWire.encode(policy))))
                local.policySent(policy)
            }
        };refresh()
    }
    suspend fun sourceLoop(enabled: () -> Boolean) {
        while(currentCoroutineContext().isActive && enabled()) {
            try {
                cleanup(); val service=local.service()
                if(service!=null && local.peers().any { it.enabled }) {
                    if(local.info()==null) {
                        val signed=requireNotNull(http.call(service,identity.id,"info").info)
                        val info=MailboxWire.decode<MailboxInfo>(MailboxWire.verify(identity,service.publicKey,"info",signed))
                        require(info.source==identity.id);local.saveInfo(info)
                    }
                    if(enabled()) sourceTick(service,enabled)
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { local.peers().filter { it.enabled }.forEach { report(it.peer,"Mailbox unavailable; saved updates will retry") } }
            refresh();delay(15_000)
        }
    }
    private fun sourceTick(service: MailboxService, enabled: () -> Boolean) {
        check(enabled() && local.sharingAllowed())
        local.nextPolicy(System.currentTimeMillis())?.let {
            if(!enabled() || !local.sharingAllowed())return
            http.call(service,identity.id,"policy",MailboxRequest(policy=MailboxWire.sign(identity,"policy",MailboxWire.encode(it))))
            local.policySent(it)
        }
        if(local.policyDirty() || !enabled())return
        val ready=mutableSetOf<String>()
        for(peer in local.peers().filter { it.enabled && it.acceptance!=null }) {
            try {
            if(!enabled() || !local.enabled(peer.peer))return
            http.call(service,identity.id,"register",MailboxRequest(registration=registration(peer)))
            val doc=grant(requireNotNull(peer.grant),identity.id)
            val receipts=http.call(service,identity.id,"receipts",MailboxRequest(grantId=doc.grantId)).receipts
            val confirmed=mutableListOf<String>()
            for(r in receipts) {
                val receipt=MailboxWire.decode<MailboxReceipt>(MailboxWire.verify(identity,peer.peer,"receipt",r))
                require(receipt.version==1 && receipt.service==service.id && receipt.source==identity.id && receipt.recipient==peer.peer && receipt.grantId==doc.grantId)
                local.delivered(receipt);confirmed+=receipt.messageId
            }
            if(confirmed.isNotEmpty()) http.call(service,identity.id,"confirm",MailboxRequest(grantId=doc.grantId,ids=confirmed))
            ready+=peer.peer
            } catch(_: Exception) { report(peer.peer,"Mailbox route unavailable; other caregivers continue") }
        }
        for(item in ready.flatMap { local.outgoing(it) }) {
            if(item.peer !in ready)continue
            try {
            if(!enabled() || !local.sharingAllowed() || local.policyDirty() || !local.enabled(item.peer) || local.generation()!=item.generation)return
            val p=local.peer(item.peer) ?: continue; val doc=grant(requireNotNull(p.grant),identity.id)
            if(doc.grantId!=item.grantId)continue
            val descriptor=MailboxWire.decode<MailboxKey>(MailboxWire.verify(identity,item.peer,"key",doc.key))
            require(descriptor.expires>System.currentTimeMillis())
            val envelope=item.envelope ?: MailboxCrypto.seal(identity,MailboxHeader(service=service.id,source=identity.id,recipient=item.peer,grantId=item.grantId,generation=item.generation,keyId=descriptor.keyId,messageId=MailboxWire.randomId(),expires=item.storedAt+MailboxWire.RETENTION),
                MailboxWire.unb64(descriptor.publicKey),MailboxContent(item.event,item.storedAt)).also { local.saveEnvelope(item,it) }
            val stored=requireNotNull(http.call(service,identity.id,"put",MailboxRequest(envelope=envelope)).stored)
            val receipt=MailboxWire.decode<MailboxReceipt>(MailboxWire.verify(identity,service.publicKey,"stored",stored))
            val h=MailboxWire.decode<MailboxHeader>(MailboxWire.unb64(envelope.header))
            require(receipt.version==1 && receipt.service==service.id && receipt.source==identity.id && receipt.recipient==item.peer && receipt.grantId==item.grantId && receipt.messageId==h.messageId && receipt.envelopeHash==MailboxWire.envelopeHash(envelope) && receipt.expires==h.expires)
            local.stored(item);report(item.peer,"Saved in encrypted mailbox; awaiting caregiver receipt")
            } catch(_: Exception) { report(item.peer,"Upload will retry; other caregivers continue") }
        }
    }
    suspend fun receiverLoop(pollMs: () -> Long) {
        while(currentCoroutineContext().isActive) {
            supervisorScope {
                local.peers().filter { it.enabled && it.acceptance!=null }.forEach { peer -> launch {
                    try { receive(peer) }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { report(peer.peer,"Mailbox unavailable; showing saved updates") }
                } }
            };refresh();delay(pollMs())
        }
    }
    private fun receive(peer: MailboxStore.PeerState) {
        val service=local.service() ?: return
        if(!local.enabled(peer.peer))return
        val doc=grant(requireNotNull(peer.grant),peer.peer)
        http.call(service,peer.peer,"register",MailboxRequest(registration=registration(peer)))
        sendReceipts(service,peer.peer,doc.grantId)
        val result=http.call(service,peer.peer,"fetch",MailboxRequest(grantId=doc.grantId))
        val receivingKey=key() // A local key/storage failure must not quarantine server messages.
        var received=0
        var rejectedCount=0
        for(envelope in result.envelopes) {
            if(!local.enabled(peer.peer))return
            val opened=try { MailboxCrypto.open(identity,doc,receivingKey,envelope,System.currentTimeMillis()) }
            catch (_: Exception) {
                // Quarantine is not a successful delivery ACK. It cannot block later valid mail.
                val rejected=MailboxWire.decode<MailboxHeader>(MailboxWire.unb64(envelope.header))
                require(MailboxWire.isId(rejected.messageId))
                http.call(service,peer.peer,"reject",MailboxRequest(grantId=doc.grantId,ids=listOf(rejected.messageId)))
                rejectedCount++
                continue
            }
            val (header,content)=opened
            val hash=MailboxWire.envelopeHash(envelope)
            val receipt=MailboxWire.sign(identity,"receipt",MailboxWire.encode(MailboxReceipt(service=service.id,source=peer.peer,recipient=identity.id,grantId=doc.grantId,messageId=header.messageId,envelopeHash=hash,expires=header.expires)))
            try { local.commitReceived(header,content,hash,receipt,System.currentTimeMillis()) }
            catch (_: IllegalArgumentException) {
                // A signed conflicting event is invalid; storage errors remain retryable.
                http.call(service,peer.peer,"reject",MailboxRequest(grantId=doc.grantId,ids=listOf(header.messageId)))
                rejectedCount++
                continue
            }
            received++
        }
        sendReceipts(service,peer.peer,doc.grantId)
        report(peer.peer,when {
            rejectedCount>0 -> "Received $received queued updates; rejected $rejectedCount invalid updates"
            received>0 -> "Received $received queued updates"
            else -> "Mailbox checked; source may be offline"
        })
    }
    private fun sendReceipts(service: MailboxService, source: String, grantId: String) {
        val receipts=local.pendingReceipts(source)
        if(receipts.isNotEmpty() && local.enabled(source)) {
            http.call(service,source,"ack",MailboxRequest(grantId=grantId,receipts=receipts.map { it.second }));local.acked(receipts.map { it.first })
        }
    }
}
