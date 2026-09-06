package family.seniorlink.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class Role { UNSET, SHARER, CAREGIVER }

@Serializable
enum class Kind { UNLOCK, LOCATION, SMS, CHECK_IN }

@Serializable
data class Settings(
    val role: Role = Role.UNSET,
    val unlock: Boolean = false,
    val location: Boolean = false,
    val sms: Boolean = false,
    val smsBodies: Boolean = false,
    val smsSenders: String = "",
    val telegram: Boolean = false,
    val telegramChat: String = "",
) {
    fun allows(kind: Kind): Boolean = when (kind) {
        Kind.UNLOCK -> unlock
        Kind.LOCATION -> location
        Kind.SMS -> sms
        Kind.CHECK_IN -> true
    }
}

/** The stable event ID is (authenticated source public key, sequence). */
@Serializable
data class Event(
    val sequence: Long,
    val kind: Kind,
    val occurredAt: Long,
    val sender: String? = null,
    val body: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
) {
    fun validate() {
        require(sequence > 0 && occurredAt > 0)
        require((sender?.length ?: 0) <= 100 && (body?.length ?: 0) <= 2000)
        when (kind) {
            Kind.LOCATION -> {
                require(latitude != null && latitude.isFinite() && latitude in -90.0..90.0)
                require(longitude != null && longitude.isFinite() && longitude in -180.0..180.0)
                require(accuracy != null && accuracy.isFinite() && accuracy >= 0)
                require(sender == null && body == null)
            }
            Kind.SMS -> {
                require(!sender.isNullOrBlank())
                require(latitude == null && longitude == null && accuracy == null)
            }
            else -> require(
                sender == null && body == null && latitude == null && longitude == null && accuracy == null,
            )
        }
    }
}

data class Peer(
    val id: String,
    val name: String,
    val cursor: Long = 0,
    val lastContact: Long = 0,
    val historyGap: Boolean = false,
)

@Serializable
data class Pull(val version: Int = 1, val after: Long) {
    fun validate() { require(version == 1 && after >= 0) }
}

@Serializable
data class Batch(
    val version: Int = 1,
    val source: String,
    val through: Long,
    val latest: Long,
    val earliest: Long,
    val events: List<Event>,
) {
    fun validate(expectedSource: String, after: Long) {
        require(version == 1 && source == expectedSource)
        require(after >= 0 && through >= after && latest >= through)
        require(earliest >= 1 && earliest <= latest + 1)
        require(events.size <= Wire.PAGE_SIZE)
        var previous = after
        events.forEach {
            it.validate()
            require(it.sequence > previous && it.sequence <= through)
            previous = it.sequence
        }
        require(through > after || through == latest) { "Non-progressing batch" }
    }

    fun hasGap(after: Long) = earliest > after + 1
}

@Serializable
data class Ack(val version: Int = 1, val through: Long)

@Serializable
data class Receipt(val version: Int = 1, val through: Long)

object Wire {
    val ALPN = "family.seniorlink/sync/1".toByteArray()
    const val PAGE_SIZE = 20
    const val MAX_REQUEST = 1024
    const val MAX_RESPONSE = 512 * 1024
    val json = Json { encodeDefaults = true }

    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decode(bytes: ByteArray, limit: Int): T {
        require(bytes.size <= limit) { "Message too large" }
        return json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }
}

object Pairing {
    private val keyPattern = Regex("[0-9a-f]{64}")
    fun parse(text: String): String {
        val key = text.trim().removePrefix("seniorlink:").lowercase()
        require(keyPattern.matches(key)) { "Paste the full SeniorLink public pairing code." }
        return key
    }
    fun code(key: String) = "seniorlink:${parse(key)}"
}

object SmsPolicy {
    // Exact normalized match, not suffix matching: avoids leaking to lookalike senders.
    fun normalize(sender: String): String = sender.trim().lowercase()
        .replace(Regex("[\\s()\\-]"), "")

    fun permits(sender: String, allowlist: String): Boolean {
        val normalized = normalize(sender)
        return normalized.isNotBlank() && allowlist.lineSequence()
            .map(::normalize).filter { it.isNotBlank() && it != "*" }.any { it == normalized }
    }

    // Defense in depth only; the allowlist and body opt-in are the primary controls.
    fun safeBody(text: String): String {
        val sensitive = Regex(
            "(?i)\\b(otp|verification|verify|password|passcode|security code|pin|overovac|ověřovac|heslo)\\b|\\b\\d{4,8}\\b",
        )
        return if (sensitive.containsMatchIn(text)) "[Sensitive-looking message body withheld]"
        else text.take(2000)
    }
}

/** Persist the batch and cursor in one transaction BEFORE the transport sends an ACK. */
interface Inbox {
    fun cursor(source: String): Long
    fun commit(batch: Batch, receivedAt: Long)
}

interface Exchange {
    suspend fun pull(after: Long): Batch
    suspend fun acknowledge(through: Long): Receipt
}

suspend fun catchUpPage(source: String, inbox: Inbox, exchange: Exchange, now: Long): Boolean {
    val after = inbox.cursor(source)
    val batch = exchange.pull(after)
    batch.validate(source, after)
    inbox.commit(batch, now)
    val receipt = exchange.acknowledge(batch.through)
    require(receipt.version == 1 && receipt.through == batch.through)
    return batch.through < batch.latest
}
