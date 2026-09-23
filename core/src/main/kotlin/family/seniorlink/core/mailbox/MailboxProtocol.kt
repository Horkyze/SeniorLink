package family.seniorlink.core.mailbox

import family.seniorlink.core.Event
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable data class MailboxService(val origin: String, val publicKey: String) {
    fun validate() {
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath.isEmpty() && uri.port in listOf(-1, 443)) { "Use an HTTPS origin without a path or port." }
        require(origin == "https://${uri.host.lowercase()}" && MailboxWire.isId(publicKey)) { "Check mailbox address and public key." }
    }
    val id: String get() = MailboxWire.hash("$origin\n$publicKey".toByteArray())
}
@Serializable data class SignedMailbox(val payload: String, val signature: String)
@Serializable data class MailboxKey(val version: Int = 1, val owner: String, val keyId: String,
    val publicKey: String, val revision: Long = 1, val created: Long, val expires: Long)
@Serializable data class MailboxGrant(val version: Int = 1, val service: String, val incarnation: String,
    val source: String, val recipient: String, val grantId: String, val key: SignedMailbox, val created: Long)
@Serializable data class MailboxRegistration(val grant: SignedMailbox, val acceptance: SignedMailbox)
@Serializable data class MailboxAcceptance(val version: Int = 1, val grantHash: String, val source: String, val recipient: String)
@Serializable data class MailboxPolicy(val version: Int = 1, val service: String, val incarnation: String,
    val source: String, val revision: Long, val generation: Long, val enabled: Boolean, val until: Long)
@Serializable data class MailboxHeader(val version: Int = 1, val suite: String = "HPKE-X25519-SHA256-CHACHA20POLY1305",
    val service: String, val source: String, val recipient: String, val grantId: String, val generation: Long,
    val keyId: String, val messageId: String, val expires: Long)
@Serializable data class MailboxEnvelope(val header: String, val enc: String, val ciphertext: String, val signature: String)
@Serializable data class MailboxContent(val event: Event, val storedAt: Long)
@Serializable data class MailboxReceipt(val version: Int = 1, val service: String, val source: String,
    val recipient: String, val grantId: String, val messageId: String, val envelopeHash: String, val expires: Long)
@Serializable data class MailboxInfo(val version: Int = 1, val service: String, val source: String, val incarnation: String)
@Serializable data class MailboxClose(val version: Int = 1, val service: String, val source: String, val grantId: String, val grant: SignedMailbox)
@Serializable data class MailboxRequest(val version: Int = 1, val registration: MailboxRegistration? = null,
    val policy: SignedMailbox? = null, val envelope: MailboxEnvelope? = null, val grantId: String? = null,
    val receipts: List<SignedMailbox>? = null, val ids: List<String>? = null, val close: SignedMailbox? = null)
@Serializable data class MailboxResponse(val version: Int = 1, val info: SignedMailbox? = null,
    val stored: SignedMailbox? = null, val envelopes: List<MailboxEnvelope> = emptyList(),
    val receipts: List<SignedMailbox> = emptyList(), val generation: Long = 0, val expired: Long = 0)
@Serializable data class MailboxControl(val version: Int = 1, val service: MailboxService,
    val key: SignedMailbox, val acceptance: SignedMailbox? = null)
@Serializable data class MailboxOffer(val version: Int = 1, val grant: SignedMailbox)

interface MailboxIdentity {
    val id: String
    fun sign(bytes: ByteArray): ByteArray
    fun verify(id: String, bytes: ByteArray, signature: ByteArray)
}
object MailboxWire {
    val CONTROL_ALPN = "family.seniorlink/mailbox-control/1".toByteArray()
    const val RETENTION = 7L * 24 * 60 * 60 * 1000
    const val LEASE = 24L * 60 * 60 * 1000
    const val MAX_BODY = 512 * 1024
    const val MAX_ENVELOPE = 64 * 1024
    val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
    fun randomId(): String = hex(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun isId(value: String) = value.matches(Regex("[0-9a-f]{64}"))
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun unhex(value: String): ByteArray { require(value.matches(Regex("(?:[0-9a-f]{2})+"))); return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value).also { require(b64(it) == value) }
    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray()
    inline fun <reified T> decode(bytes: ByteArray): T {
        require(bytes.size <= MAX_BODY); uniqueKeys(bytes.toString(Charsets.UTF_8))
        return json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }
    fun domain(kind: String) = "SeniorLink mailbox $kind v1\n".toByteArray()
    fun sign(identity: MailboxIdentity, kind: String, bytes: ByteArray) = SignedMailbox(b64(bytes), hex(identity.sign(domain(kind) + bytes)))
    fun verify(identity: MailboxIdentity, signer: String, kind: String, value: SignedMailbox): ByteArray {
        require(isId(signer) && value.signature.length == 128)
        val payload = unb64(value.payload); require(payload.size <= MAX_BODY)
        identity.verify(signer, domain(kind) + payload, unhex(value.signature)); return payload
    }
    fun envelopeBytes(value: MailboxEnvelope): ByteArray = listOf(unb64(value.header), unb64(value.enc), unb64(value.ciphertext))
        .fold(byteArrayOf()) { all, part -> all + ByteBuffer.allocate(4).putInt(part.size).array() + part }
    fun envelopeHash(value: MailboxEnvelope) = hash(envelopeBytes(value) + unhex(value.signature))
    /** kotlinx.serialization otherwise accepts duplicate keys. Reject before decoding any signed JSON. */
    fun uniqueKeys(text: String) {
        data class Frame(val objectType: Boolean, val keys: MutableSet<String> = mutableSetOf(), var key: Boolean = true)
        val stack = mutableListOf<Frame>(); var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> { require(stack.size < 32); stack.add(Frame(true)) }
                '[' -> { require(stack.size < 32); stack.add(Frame(false)) }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                ':' -> if (stack.lastOrNull()?.objectType == true) stack.last().key = false
                ',' -> if (stack.lastOrNull()?.objectType == true) stack.last().key = true
                '"' -> {
                    val start = i++; var escaped = false
                    while (i < text.length) { val c = text[i]; if (c == '"' && !escaped) break; escaped = c == '\\' && !escaped; i++ }
                    require(i < text.length)
                    val frame = stack.lastOrNull()
                    if (frame?.objectType == true && frame.key) require(frame.keys.add(json.decodeFromString<String>(text.substring(start, i + 1))))
                }
            }; i++
        }; require(stack.isEmpty())
    }
    fun signatureInput(id: String, created: Long, nonce: String) =
        "(\"@method\" \"@target-uri\" \"content-type\" \"content-digest\");created=$created;expires=${created + 300};nonce=\"$nonce\";keyid=\"$id\";alg=\"ed25519\";tag=\"seniorlink-mailbox-v1\""
    fun requestBase(url: String, digest: String, input: String) =
        "\"@method\": POST\n\"@target-uri\": $url\n\"content-type\": application/json\n\"content-digest\": $digest\n\"@signature-params\": $input".toByteArray()
}
