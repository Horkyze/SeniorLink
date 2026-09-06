package family.seniorlink.core

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.test.*

class ConnectionPairingTest {
    private class Keys : PairingKeys {
        private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        override val publicId = ConnectionPairing.hex(pair.public.encoded.takeLast(32).toByteArray())
        override fun sign(bytes: ByteArray): String = ConnectionPairing.hex(Signature.getInstance("Ed25519").run {
            initSign(pair.private); update(bytes); sign()
        })
        override fun verify(publicId: String, bytes: ByteArray, signature: String) {
            val encoded = ("302a300506032b6570032100" + publicId).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
            require(Signature.getInstance("Ed25519").run {
                initVerify(key); update(bytes); verify(ConnectionPairing.unhex(signature))
            })
        }
    }
    private class Peers : PairingPeers {
        val values = mutableMapOf<String, String>()
        var writes = 0
        var fail = false
        override fun contains(id: String) = id in values
        override fun save(id: String, name: String) {
            check(!fail) { "Storage unavailable" }
            values[id] = name; writes++
        }
        override fun remove(id: String) { values.remove(id) }
    }
    private class Setup {
        val sharer = Keys()
        val caregiver = Keys()
        val sharingPeers = Peers()
        val caringPeers = Peers()
        var now = 100L
        val nonce = ConnectionPairing.randomHex()
        val invite = ConnectionInvite(
            publicId = sharer.publicId, name = "Grandad", ticket = "test-ticket", endpointId = "a".repeat(64),
            token = ConnectionPairing.randomHex(16), commitment = ConnectionPairing.digest(nonce.toByteArray()),
        )
        val hello = ConnectionHello(
            invitation = ConnectionPairing.inviteId(invite), publicId = caregiver.publicId,
            endpointId = "b".repeat(64), name = "Anna", nonce = ConnectionPairing.randomHex(),
        )
        val host = ConnectionHost(invite, sharer, sharingPeers, { now }, nonce)
        val client = ConnectionClient(invite, hello, caregiver, caringPeers)
        fun exchange(call: SignedConnectionCall = client.request()) = host.respond(call, hello.endpointId)
        fun approve() { exchange(); host.approve(ConnectionPairing.requestId(hello)) }
        fun complete() {
            approve()
            client.receive(exchange())
            val response = exchange()
            client.receive(response)
        }
        fun sign(call: ConnectionCall) = SignedConnectionCall(call, caregiver.sign(ConnectionPairing.requestBytes(call)))
    }

    @Test fun `one invitation gives matching code and saves both phones only after consent and receipt`() {
        val s = Setup()
        assertEquals(s.invite, ConnectionPairing.parse(ConnectionPairing.code(s.invite), s.caregiver.publicId))
        assertEquals(ConnectionStatus.WAITING, s.client.receive(s.exchange()))
        assertEquals(s.host.verification(), s.client.verification())
        assertEquals(4, s.client.verification().length)
        assertEquals(32, ConnectionPairing.ALPHABET.toSet().size)
        assertTrue(s.client.verification().all { it in ConnectionPairing.ALPHABET })
        assertTrue(s.sharingPeers.values.isEmpty() && s.caringPeers.values.isEmpty())
        s.host.approve(ConnectionPairing.requestId(s.hello))
        assertTrue(s.sharingPeers.values.isEmpty())
        assertEquals(ConnectionStatus.APPROVED, s.client.receive(s.exchange()))
        assertEquals("Grandad", s.caringPeers.values[s.sharer.publicId])
        val receipt = s.exchange()
        assertEquals(ConnectionStatus.COMPLETE, s.client.receive(receipt))
        assertEquals("Anna", s.sharingPeers.values[s.caregiver.publicId])
        assertEquals(ConnectionStatus.COMPLETE, s.host.status)
    }

    @Test fun `lost approval and lost receipt retry without another scan or duplicate write`() {
        val s = Setup()
        s.approve()
        val lost = s.exchange()
        assertEquals(lost, s.exchange())
        s.client.receive(s.exchange())
        s.exchange() // receipt lost after the sharer committed
        s.client.receive(s.exchange())
        assertEquals(1, s.sharingPeers.writes)
        assertEquals(1, s.caringPeers.writes)
    }

    @Test fun `neither polling nor an early acknowledgement grants access`() {
        val s = Setup()
        repeat(3) { s.client.receive(s.exchange()) }
        assertEquals(ConnectionStatus.WAITING, s.exchange(s.sign(ConnectionCall(s.hello, acknowledge = true))).reply.status)
        assertTrue(s.sharingPeers.values.isEmpty() && s.caringPeers.values.isEmpty())
    }

    @Test fun `host rejection grants no access and cannot later be confirmed`() {
        val s = Setup()
        s.exchange(); s.host.decline()
        assertEquals(ConnectionStatus.DECLINED, s.client.receive(s.exchange()))
        assertFails { s.host.approve(ConnectionPairing.requestId(s.hello)) }
        assertTrue(s.sharingPeers.values.isEmpty() && s.caringPeers.values.isEmpty())
    }

    @Test fun `caregiver rejection wins even if host approval is already in flight`() {
        val s = Setup()
        s.approve()
        val inFlight = s.exchange()
        s.client.cancel()
        assertEquals(ConnectionStatus.DECLINED, s.client.receive(inFlight))
        assertFalse(s.client.saved)
        assertEquals(ConnectionStatus.DECLINED, s.exchange(s.client.cancel()).reply.status)
        assertEquals(ConnectionStatus.DECLINED, s.exchange().reply.status)
        assertTrue(s.sharingPeers.values.isEmpty() && s.caringPeers.values.isEmpty())
    }

    @Test fun `sharer can withdraw an approval before the caregiver acknowledgement`() {
        val s = Setup()
        s.approve()
        s.host.decline()
        assertEquals(ConnectionStatus.DECLINED, s.client.receive(s.exchange()))
        assertTrue(s.sharingPeers.values.isEmpty() && s.caringPeers.values.isEmpty())
    }

    @Test fun `expiry blocks both new requests and committing an already approved request`() {
        val s = Setup()
        s.approve()
        s.client.receive(s.exchange())
        s.now += ConnectionPairing.LIFETIME_MS
        assertEquals(ConnectionStatus.EXPIRED, s.client.receive(s.exchange()))
        assertTrue(s.sharingPeers.values.isEmpty())
        val fresh = Setup()
        fresh.exchange(); fresh.now += ConnectionPairing.LIFETIME_MS
        assertFails { fresh.host.approve(ConnectionPairing.requestId(fresh.hello)) }
    }

    @Test fun `a late caregiver rejection removes only the newly created connection on both phones`() {
        val s = Setup()
        s.complete()
        val cancel = s.client.cancel()
        assertEquals(ConnectionStatus.DECLINED, s.exchange(cancel).reply.status)
        assertTrue(s.sharingPeers.values.isEmpty() && s.caringPeers.values.isEmpty())
        assertFalse(s.client.saved)
    }

    @Test fun `a second request cannot replace the identity name nonce or verification code`() {
        val s = Setup()
        s.client.receive(s.exchange())
        val code = s.host.verification()
        for (changed in listOf(s.hello.copy(name = "Another Anna"), s.hello.copy(nonce = ConnectionPairing.randomHex()))) {
            assertEquals(ConnectionStatus.BUSY, s.exchange(s.sign(ConnectionCall(changed))).reply.status)
        }
        assertEquals(s.hello, s.host.hello)
        assertEquals(code, s.host.verification())
        assertFails { s.host.approve("wrong request") }
        assertTrue(s.sharingPeers.values.isEmpty())
    }

    @Test fun `forged identity signature transport identity role and invitation are rejected`() {
        val s = Setup()
        val original = s.client.request()
        assertFails { s.exchange(original.copy(signature = "0".repeat(128))) }
        assertFails { s.exchange(original.copy(call = original.call.copy(hello = s.hello.copy(name = "Forged")))) }
        assertFails { s.host.respond(original, "c".repeat(64)) }
        for (bad in listOf(
            s.hello.copy(role = Role.SHARER), s.hello.copy(role = Role.UNSET),
            s.hello.copy(invitation = "0".repeat(64)), s.hello.copy(version = 2),
            s.hello.copy(publicId = s.sharer.publicId), s.hello.copy(name = "Fake\nName"),
        )) assertFails { s.exchange(s.sign(ConnectionCall(bad))) }
        assertNull(s.host.hello)
        assertTrue(s.sharingPeers.values.isEmpty())
    }

    @Test fun `tampered approval and signed response for another request cannot save a peer`() {
        val s = Setup()
        val waiting = s.exchange()
        assertFails { s.client.receive(waiting.copy(reply = waiting.reply.copy(status = ConnectionStatus.APPROVED))) }
        val wrong = waiting.reply.copy(request = "0".repeat(64), status = ConnectionStatus.APPROVED)
        assertFails { s.client.receive(SignedConnectionReply(wrong, s.sharer.sign(ConnectionPairing.replyBytes(wrong)))) }
        assertTrue(s.caringPeers.values.isEmpty())
    }

    @Test fun `even a signed replacement challenge must match the QR commitment`() {
        val s = Setup()
        val response = s.exchange().reply.copy(nonce = ConnectionPairing.randomHex())
        assertFails { s.client.receive(SignedConnectionReply(response, s.sharer.sign(ConnectionPairing.replyBytes(response)))) }
        assertEquals("", s.client.verification())
        assertTrue(s.caringPeers.values.isEmpty())
    }

    @Test fun `revocation cannot be undone by replaying a completed or approved request`() {
        val s = Setup()
        s.complete()
        s.sharingPeers.remove(s.caregiver.publicId)
        assertEquals(ConnectionStatus.DECLINED, s.client.receive(s.exchange()))
        assertEquals(1, s.sharingPeers.writes)
        s.caringPeers.remove(s.sharer.publicId)
        assertFails { s.client.request() }
    }

    @Test fun `failed storage can retry but cannot acknowledge before a durable save`() {
        val s = Setup()
        s.approve(); s.caringPeers.fail = true
        assertFails { s.client.receive(s.exchange()) }
        assertFalse(s.client.saved)
        assertFalse(s.client.request().call.acknowledge)
        s.caringPeers.fail = false
        s.client.receive(s.exchange())
        s.sharingPeers.fail = true
        assertFails { s.exchange() }
        s.sharingPeers.fail = false
        s.client.receive(s.exchange())
        assertEquals(1, s.sharingPeers.writes)
    }

    @Test fun `rejecting a duplicate invitation preserves an existing connection`() {
        val s = Setup()
        s.sharingPeers.save(s.caregiver.publicId, "Existing name")
        s.approve()
        s.exchange(s.client.cancel())
        assertEquals("Existing name", s.sharingPeers.values[s.caregiver.publicId])
    }

    @Test fun `manual invitations validate just like scanned ones and reject legacy self and oversized input`() {
        val s = Setup()
        assertEquals(s.invite, ConnectionPairing.parse(" ${ConnectionPairing.code(s.invite)} ", s.caregiver.publicId))
        for (invalid in listOf("https://example.com", "seniorlink-connect:bad", "x".repeat(3000), Pairing.code(s.sharer.publicId))) {
            assertFailsWith<IllegalArgumentException> { ConnectionPairing.parse(invalid, s.caregiver.publicId) }
        }
        assertFailsWith<IllegalArgumentException> { ConnectionPairing.parse(ConnectionPairing.code(s.invite), s.sharer.publicId) }
        assertFails { ConnectionPairing.code(s.invite.copy(name = " ")) }
        assertFails { ConnectionPairing.code(s.invite.copy(version = 2)) }
    }
}
