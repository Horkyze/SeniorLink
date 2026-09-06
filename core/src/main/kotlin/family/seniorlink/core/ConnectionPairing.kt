package family.seniorlink.core

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Signing proves ownership of the permanent identity; transport uses temporary keys. */
interface PairingKeys {
    val publicId: String
    fun sign(bytes: ByteArray): String
    fun verify(publicId: String, bytes: ByteArray, signature: String)
}

interface PairingPeers {
    fun contains(id: String): Boolean
    fun save(id: String, name: String)
    fun remove(id: String)
}

@Serializable
data class ConnectionInvite(
    val version: Int = 1,
    val publicId: String,
    val name: String,
    val ticket: String,
    val endpointId: String,
    val token: String,
    val commitment: String,
) {
    fun validate() {
        require(version == 1) { "Update SeniorLink on both phones and try again." }
        require(Pairing.parse(publicId) == publicId)
        ConnectionPairing.validateName(name)
        require(ticket.isNotBlank() && ticket.length <= 1000)
        require(Pairing.parse(endpointId) == endpointId)
        require(token.matches(Regex("[0-9a-f]{32}")))
        require(commitment.matches(Regex("[0-9a-f]{64}")))
    }
}

@Serializable
data class ConnectionHello(
    val version: Int = 1,
    val invitation: String,
    val publicId: String,
    val endpointId: String,
    val name: String,
    val nonce: String,
    val role: Role = Role.CAREGIVER,
)

@Serializable
data class ConnectionCall(val hello: ConnectionHello, val acknowledge: Boolean = false, val cancel: Boolean = false)

@Serializable
data class SignedConnectionCall(val call: ConnectionCall, val signature: String)

@Serializable
enum class ConnectionStatus { WAITING, APPROVED, COMPLETE, DECLINED, BUSY, EXPIRED }

@Serializable
data class ConnectionReply(
    val version: Int = 1,
    val request: String,
    val nonce: String,
    val status: ConnectionStatus,
)

@Serializable
data class SignedConnectionReply(val reply: ConnectionReply, val signature: String)

object ConnectionPairing {
    val ALPN = "family.seniorlink/pair/1".toByteArray()
    const val LIFETIME_MS = 5 * 60_000L
    const val MAX_MESSAGE = 8192
    const val MAX_CODE = 2400
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFIX = "seniorlink-connect:"
    private val random = SecureRandom()

    fun randomHex(bytes: Int = 32): String = hex(ByteArray(bytes).also(random::nextBytes))
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun unhex(text: String): ByteArray {
        require(text.length == 128 && text.matches(Regex("[0-9a-f]+")))
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun inviteId(invite: ConnectionInvite): String = digest(Wire.encode(invite))
    fun requestId(hello: ConnectionHello): String = digest(Wire.encode(hello))
    fun requestBytes(call: ConnectionCall): ByteArray = "SeniorLink pairing request v1\n".toByteArray() + Wire.encode(call)
    fun replyBytes(reply: ConnectionReply): ByteArray = "SeniorLink pairing reply v1\n".toByteArray() + Wire.encode(reply)

    fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= 40 && name.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) {
            "Enter a short name, such as Anna or Grandad."
        }
    }

    fun code(invite: ConnectionInvite): String {
        invite.validate()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Wire.encode(invite))
    }

    fun parse(text: String, ownId: String): ConnectionInvite {
        val code = text.trim()
        require(!code.startsWith("seniorlink:")) {
            "That is an older QR code. Update SeniorLink on both phones, then tap Connect a caregiver for a new one."
        }
        require(code.length <= MAX_CODE && code.startsWith(PREFIX)) {
            "Scan the QR shown under Connect a caregiver on the sharing phone."
        }
        val invite = try {
            Wire.decode<ConnectionInvite>(Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)), MAX_CODE)
        } catch (_: Exception) {
            throw IllegalArgumentException("This connection code could not be read. Show a new QR and try again.")
        }
        invite.validate()
        require(invite.publicId != ownId) { "This is your own phone's code. Scan the sharing phone's QR." }
        return invite
    }

    /** 20 bits, independently computed; the secret nonce is revealed only after pinning the request. */
    fun verification(hello: ConnectionHello, nonce: String): String {
        require(nonce.matches(Regex("[0-9a-f]{64}")))
        val hash = MessageDigest.getInstance("SHA-256").digest(
            "SeniorLink visual verification v1\n${requestId(hello)}\n$nonce".toByteArray(),
        )
        val value = ((hash[0].toInt() and 255) shl 12) or
            ((hash[1].toInt() and 255) shl 4) or ((hash[2].toInt() and 255) ushr 4)
        return (3 downTo 0).map { ALPHABET[(value ushr (it * 5)) and 31] }.joinToString("")
    }
}

/** One immutable request per invitation. A reconnect can never change the displayed identity/code. */
class ConnectionHost(
    val invite: ConnectionInvite,
    private val keys: PairingKeys,
    private val peers: PairingPeers,
    private val now: () -> Long,
    private val hostNonce: String,
) {
    private val expiresAt = now() + ConnectionPairing.LIFETIME_MS
    @Volatile var hello: ConnectionHello? = null
        private set
    private var challenge = ""
    @Volatile var status = ConnectionStatus.WAITING
        private set
    private var previouslyPaired = false
    private var saved = false
    init {
        invite.validate()
        require(invite.publicId == keys.publicId)
        require(hostNonce.matches(Regex("[0-9a-f]{64}")))
        require(ConnectionPairing.digest(hostNonce.toByteArray()) == invite.commitment)
    }

    @Synchronized fun verification(): String = hello?.let { ConnectionPairing.verification(it, challenge) }.orEmpty()
    @Synchronized fun expired(): Boolean = now() >= expiresAt

    @Synchronized fun respond(signed: SignedConnectionCall, remoteId: String): SignedConnectionReply {
        val call = signed.call
        val request = call.hello
        require(!call.acknowledge || !call.cancel)
        require(request.version == 1 && request.role == Role.CAREGIVER)
        require(request.invitation == ConnectionPairing.inviteId(invite))
        require(Pairing.parsePeer(request.publicId, invite.publicId) == request.publicId)
        require(request.endpointId == remoteId && Pairing.parse(remoteId) == remoteId)
        require(request.nonce.matches(Regex("[0-9a-f]{64}")))
        ConnectionPairing.validateName(request.name)
        keys.verify(request.publicId, ConnectionPairing.requestBytes(call), signed.signature)
        val result = when {
            expired() -> ConnectionStatus.EXPIRED
            hello != null && hello != request -> ConnectionStatus.BUSY
            status == ConnectionStatus.DECLINED -> ConnectionStatus.DECLINED
            else -> {
                if (hello == null) {
                    require(!call.acknowledge)
                    hello = request
                    challenge = hostNonce
                }
                if ((saved || previouslyPaired) && !peers.contains(request.publicId)) {
                    status = ConnectionStatus.DECLINED // Removal must never be undone by a retry.
                }
                if (call.cancel) {
                    if (saved && !previouslyPaired) peers.remove(request.publicId)
                    status = ConnectionStatus.DECLINED
                }
                if (call.acknowledge && status == ConnectionStatus.APPROVED) {
                    if (!saved) {
                        peers.save(request.publicId, request.name)
                        saved = true
                    }
                    status = ConnectionStatus.COMPLETE
                    status
                } else status
            }
        }
        val reply = ConnectionReply(
            request = ConnectionPairing.requestId(request),
            nonce = if (hello == request) challenge else "",
            status = result,
        )
        return SignedConnectionReply(reply, keys.sign(ConnectionPairing.replyBytes(reply)))
    }

    @Synchronized fun approve(requestId: String) {
        val request = checkNotNull(hello)
        check(ConnectionPairing.requestId(request) == requestId)
        if (status == ConnectionStatus.APPROVED || status == ConnectionStatus.COMPLETE) return
        check(!expired() && status == ConnectionStatus.WAITING)
        previouslyPaired = peers.contains(request.publicId)
        status = ConnectionStatus.APPROVED
    }

    @Synchronized fun decline() {
        if (status == ConnectionStatus.WAITING || status == ConnectionStatus.APPROVED) status = ConnectionStatus.DECLINED
    }

}

class ConnectionClient(
    val invite: ConnectionInvite,
    val hello: ConnectionHello,
    private val keys: PairingKeys,
    private val peers: PairingPeers,
) {
    private var challenge: String? = null
    private var previouslyPaired = false
    var saved = false
        private set
    @Volatile var cancelled = false
        private set
    init {
        invite.validate()
        require(hello.publicId == keys.publicId && hello.publicId != invite.publicId)
        require(hello.invitation == ConnectionPairing.inviteId(invite) && hello.role == Role.CAREGIVER)
    }
    @Synchronized fun request(): SignedConnectionCall {
        if (cancelled) return cancel()
        check(!saved || peers.contains(invite.publicId)) { "Connection removed. Start again to reconnect." }
        val call = ConnectionCall(hello, acknowledge = saved)
        return SignedConnectionCall(call, keys.sign(ConnectionPairing.requestBytes(call)))
    }
    @Synchronized fun cancel(): SignedConnectionCall {
        cancelled = true
        if (saved && !previouslyPaired) peers.remove(invite.publicId)
        saved = false
        val call = ConnectionCall(hello, cancel = true)
        return SignedConnectionCall(call, keys.sign(ConnectionPairing.requestBytes(call)))
    }
    @Synchronized fun receive(signed: SignedConnectionReply): ConnectionStatus {
        val reply = signed.reply
        require(reply.version == 1 && reply.request == ConnectionPairing.requestId(hello))
        keys.verify(invite.publicId, ConnectionPairing.replyBytes(reply), signed.signature)
        if (cancelled) return ConnectionStatus.DECLINED
        if (reply.status in listOf(ConnectionStatus.WAITING, ConnectionStatus.APPROVED, ConnectionStatus.COMPLETE)) {
            require(reply.nonce.matches(Regex("[0-9a-f]{64}")))
            require(ConnectionPairing.digest(reply.nonce.toByteArray()) == invite.commitment)
            require(challenge == null || challenge == reply.nonce)
            challenge = reply.nonce
        }
        if (reply.status == ConnectionStatus.APPROVED && !saved) {
            previouslyPaired = peers.contains(invite.publicId)
            peers.save(invite.publicId, invite.name)
            saved = true
        }
        if (reply.status == ConnectionStatus.COMPLETE) require(saved)
        return reply.status
    }
    fun verification(): String = challenge?.let { ConnectionPairing.verification(hello, it) }.orEmpty()
}
