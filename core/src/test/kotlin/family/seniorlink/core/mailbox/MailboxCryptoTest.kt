package family.seniorlink.core.mailbox

import family.seniorlink.core.Event
import family.seniorlink.core.Kind
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import kotlin.test.*

class MailboxCryptoTest {
    private class Identity : MailboxIdentity {
        val secret=Ed25519PrivateKeyParameters(SecureRandom())
        override val id=MailboxWire.hex(secret.generatePublicKey().encoded)
        override fun sign(bytes: ByteArray): ByteArray = Ed25519Signer().run { init(true,secret);update(bytes,0,bytes.size);generateSignature() }
        override fun verify(id: String, bytes: ByteArray, signature: ByteArray) {
            require(Ed25519Signer().run { init(false,Ed25519PublicKeyParameters(MailboxWire.unhex(id)));update(bytes,0,bytes.size);verifySignature(signature) })
        }
    }
    @Test fun `HPKE matches RFC 9180 A2 first encryption and rejects tampering`() {
        val v=Json.parseToJsonElement(javaClass.getResource("/hpke-rfc9180-a2.json")!!.readText()).jsonObject
        fun b(k:String)=MailboxWire.unhex(v.getValue(k).jsonPrimitive.content)
        val e=v.getValue("encryptions").jsonArray[0].jsonObject
        fun eb(k:String)=MailboxWire.unhex(e.getValue(k).jsonPrimitive.content)
        val h=HPKE(HPKE.mode_base,HPKE.kem_X25519_SHA256,HPKE.kdf_HKDF_SHA256,HPKE.aead_CHACHA20_POLY1305)
        val pair=h.deriveKeyPair(b("ikmR"));val ephemeral=h.deriveKeyPair(b("ikmE"))
        val sender=h.setupBaseS(pair.public,b("info"),ephemeral)
        assertContentEquals(b("enc"),sender.encapsulation)
        assertContentEquals(eb("ct"),sender.seal(eb("aad"),eb("pt")))
        assertContentEquals(eb("pt"),h.setupBaseR(b("enc"),pair,b("info")).open(eb("aad"),eb("ct")))
        assertFails { h.setupBaseR(b("enc"),pair,b("info")).open(byteArrayOf(1),eb("ct")) }
    }
    @Test fun `envelope authenticates source and recipient and retains original event`() {
        val source=Identity();val caregiver=Identity();val key=MailboxCrypto.generateKey();val now=1_000_000L
        val service=MailboxService("https://mailbox.example","a".repeat(64));service.validate()
        val descriptor=MailboxWire.sign(caregiver,"key",MailboxWire.encode(MailboxKey(owner=caregiver.id,keyId="d".repeat(64),publicKey=MailboxWire.b64(key.publicKey),created=now,expires=now+MailboxWire.RETENTION)))
        val grant=MailboxGrant(service=service.id,incarnation="e".repeat(64),source=source.id,recipient=caregiver.id,grantId="b".repeat(64),key=descriptor,created=now)
        val header=MailboxHeader(service=service.id,source=source.id,recipient=caregiver.id,grantId=grant.grantId,generation=1,keyId="d".repeat(64),messageId="c".repeat(64),expires=now+MailboxWire.RETENTION)
        val content=MailboxContent(Event(102,Kind.CHECK_IN,now),now)
        val envelope=MailboxCrypto.seal(source,header,key.publicKey,content)
        assertEquals(content,MailboxCrypto.open(caregiver,grant,key,envelope,now).second)
        assertEquals(content,MailboxCrypto.open(caregiver,grant,key,envelope,now-60_000).second)
        assertFails { MailboxCrypto.open(Identity(),grant,key,envelope,now) }
        assertFails { MailboxCrypto.open(caregiver,grant,MailboxCrypto.generateKey(),envelope,now) }
        assertFails { MailboxCrypto.open(caregiver,grant,key,envelope.copy(signature="0".repeat(128)),now) }
        assertFails { MailboxCrypto.open(caregiver,grant,key,envelope,header.expires) }
        val swapped=envelope.copy(header=MailboxWire.b64(MailboxWire.encode(header.copy(recipient=source.id))))
        assertFails { MailboxCrypto.open(caregiver,grant,key,swapped,now) }
        val other=MailboxCrypto.seal(source,header,key.publicKey,content)
        assertNotEquals(envelope.enc,other.enc)
    }
    @Test fun `strict decoding rejects duplicate escaped keys and unrecognized fields`() {
        assertFails { MailboxWire.decode<MailboxRequest>("""{"version":1,"version":1}""".toByteArray()) }
        assertFails { MailboxWire.decode<MailboxRequest>("""{"version":1,"\u0076ersion":1}""".toByteArray()) }
        assertFails { MailboxWire.decode<MailboxRequest>("""{"version":1,"unknown":true}""".toByteArray()) }
        assertEquals(MailboxRequest(),MailboxWire.decode<MailboxRequest>("""{"version":1}""".toByteArray()))
    }
    @Test fun `HTTP signature matches shared Node and Worker vector byte for byte`() {
        val v=Json.parseToJsonElement(javaClass.getResource("/mailbox-http-vector.json")!!.readText()).jsonObject
        fun text(k:String)=v.getValue(k).jsonPrimitive.content
        val raw=MailboxWire.encode(MailboxRequest())
        assertEquals(text("raw"),raw.toString(Charsets.UTF_8))
        val digest="sha-256=:${MailboxWire.b64(java.security.MessageDigest.getInstance("SHA-256").digest(raw))}:"
        assertEquals(text("digest"),digest)
        val input=MailboxWire.signatureInput(text("id"),text("created").toLong(),text("nonce"))
        assertEquals(text("input"),input)
        val base=MailboxWire.requestBase(text("url"),digest,input)
        assertEquals(text("base"),base.toString(Charsets.UTF_8))
        val secret=Ed25519PrivateKeyParameters(MailboxWire.unhex(text("seed")))
        assertEquals(text("id"),MailboxWire.hex(secret.generatePublicKey().encoded))
        val signature=Ed25519Signer().run { init(true,secret);update(base,0,base.size);generateSignature() }
        assertEquals(text("signature"),MailboxWire.b64(signature))
    }
    @Test fun `mailbox origins do not allow credentials redirects or insecure transport`() {
        for(origin in listOf("http://mailbox.example","https://user@mailbox.example","https://mailbox.example/path","https://mailbox.example?x=1","https://MAILBOX.example","https://mailbox.example:8443")) {
            assertFails { MailboxService(origin,"a".repeat(64)).validate() }
        }
    }
}
