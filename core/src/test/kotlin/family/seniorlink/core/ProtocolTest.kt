package family.seniorlink.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolTest {
    private val source = "a".repeat(64)
    private val event = Event(1, Kind.UNLOCK, 100)
    private val batch = Batch(source = source, through = 1, latest = 1, earliest = 1, events = listOf(event))

    @Test fun `wire round trip and pairing normalization`() {
        assertEquals(batch, Wire.decode<Batch>(Wire.encode(batch), Wire.MAX_RESPONSE))
        assertEquals(source, Pairing.parse("  seniorlink:${source.uppercase()} "))
        assertFailsWith<IllegalArgumentException> { Pairing.parse("https://attacker.test/$source") }
        assertFailsWith<IllegalArgumentException> { Pairing.parse("a".repeat(63)) }
    }

    @Test fun `scanned and pasted codes use the same validation and cannot pair with self`() {
        val other = "b".repeat(64)
        assertEquals(other, Pairing.parsePeer(Pairing.code(other), source))
        assertEquals(other, Pairing.parsePeer("  ${other.uppercase()}  ", source))
        assertEquals("seniorlink:$other", Pairing.code(Pairing.parsePeer(other, source)))
        listOf(
            "", "https://example.com", "seniorlink:wrong", "seniorlink:${"g".repeat(64)}",
            "seniorlink:$other?extra=1", "seniorlink:$source", source.uppercase(),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { Pairing.parsePeer(invalid, source) }
        }
    }

    @Test fun `reject forged source invalid order and malformed data`() {
        assertFailsWith<IllegalArgumentException> { batch.validate("b".repeat(64), 0) }
        assertFailsWith<IllegalArgumentException> { batch.copy(events = listOf(event, event)).validate(source, 0) }
        assertFailsWith<IllegalArgumentException> { batch.copy(through = 0).validate(source, 0) }
        assertFailsWith<IllegalArgumentException> { batch.copy(version = 2).validate(source, 0) }
        assertFailsWith<IllegalArgumentException> {
            event.copy(kind = Kind.LOCATION, latitude = Double.NaN).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            Wire.decode<Pull>(ByteArray(Wire.MAX_REQUEST + 1), Wire.MAX_REQUEST)
        }
    }

    @Test fun `retention gap and filtered empty batch advance cursor`() {
        val filtered = Batch(source = source, through = 9, latest = 9, earliest = 6, events = emptyList())
        filtered.validate(source, 2)
        assertTrue(filtered.hasGap(2))
        assertFalse(filtered.hasGap(8))
    }

    @Test fun `ack follows durable commit and retries use persisted cursor`() = runTest {
        var cursor = 0L
        val order = mutableListOf<String>()
        val inbox = object : Inbox {
            override fun cursor(source: String) = cursor
            override fun commit(batch: Batch, receivedAt: Long) {
                order += "commit"
                cursor = batch.through
            }
        }
        val exchange = object : Exchange {
            override suspend fun pull(after: Long): Batch {
                order += "pull:$after"
                return if (after == 0L) batch else batch.copy(events = emptyList())
            }
            override suspend fun acknowledge(through: Long): Receipt {
                order += "ack"
                if (order.size == 3) error("Connection dropped after receipt")
                return Receipt(through = through)
            }
        }
        assertFailsWith<IllegalStateException> { catchUpPage(source, inbox, exchange, 200) }
        assertEquals(1, cursor)
        assertFalse(catchUpPage(source, inbox, exchange, 201))
        assertEquals(listOf("pull:0", "commit", "ack", "pull:1", "commit", "ack"), order)
    }

    @Test fun `failed storage must never acknowledge`() = runTest {
        var acknowledged = false
        val inbox = object : Inbox {
            override fun cursor(source: String) = 0L
            override fun commit(batch: Batch, receivedAt: Long) = error("disk full")
        }
        val exchange = object : Exchange {
            override suspend fun pull(after: Long) = batch
            override suspend fun acknowledge(through: Long): Receipt {
                acknowledged = true
                return Receipt(through = through)
            }
        }
        assertFailsWith<IllegalStateException> { catchUpPage(source, inbox, exchange, 200) }
        assertFalse(acknowledged)
    }

    @Test fun `SMS requires exact allowlist and suppresses likely secrets`() {
        assertTrue(SmsPolicy.permits("+420 123-456", "+420123456\nBank"))
        assertFalse(SmsPolicy.permits("+421123456", "+420123456"))
        assertFalse(SmsPolicy.permits("Bank", "*"))
        assertEquals("[Sensitive-looking message body withheld]", SmsPolicy.safeBody("Your code is 123456"))
        assertEquals("Lunch at noon", SmsPolicy.safeBody("Lunch at noon"))
        assertFalse(Settings().sms)
        assertFalse(Settings().smsBodies)
        assertFalse(Settings().telegram)
    }
}
