package family.seniorlink.core.mailbox

import org.bouncycastle.crypto.hpke.HPKE

/** Lightweight BC API: never replaces Android's crypto providers. */
object MailboxCrypto {
    data class Key(val privateKey: ByteArray, val publicKey: ByteArray)
    private fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)
    fun generateKey(): Key = hpke().let { h -> h.generatePrivateKey().let { Key(h.serializePrivateKey(it.private), h.serializePublicKey(it.public)) } }
    fun seal(identity: MailboxIdentity, header: MailboxHeader, recipientKey: ByteArray, content: MailboxContent): MailboxEnvelope {
        require(header.source == identity.id && recipientKey.size == 32)
        content.event.validate(); require(content.storedAt > 0 && header.expires == content.storedAt + MailboxWire.RETENTION)
        val bytes = MailboxWire.encode(header)
        val h = hpke(); val context = h.setupBaseS(h.deserializePublicKey(recipientKey), MailboxWire.domain("hpke"))
        val envelope = MailboxEnvelope(MailboxWire.b64(bytes), MailboxWire.b64(context.encapsulation),
            MailboxWire.b64(context.seal(bytes, MailboxWire.encode(content))), "")
        return envelope.copy(signature = MailboxWire.hex(identity.sign(MailboxWire.domain("envelope") + MailboxWire.envelopeBytes(envelope))))
            .also { require(MailboxWire.encode(it).size <= MailboxWire.MAX_ENVELOPE) }
    }
    fun open(identity: MailboxIdentity, expected: MailboxGrant, key: Key, envelope: MailboxEnvelope, now: Long): Pair<MailboxHeader, MailboxContent> {
        require(MailboxWire.encode(envelope).size <= MailboxWire.MAX_ENVELOPE && envelope.signature.length == 128)
        identity.verify(expected.source, MailboxWire.domain("envelope") + MailboxWire.envelopeBytes(envelope), MailboxWire.unhex(envelope.signature))
        val bytes = MailboxWire.unb64(envelope.header); val header = MailboxWire.decode<MailboxHeader>(bytes)
        val descriptor = MailboxWire.decode<MailboxKey>(MailboxWire.verify(identity, identity.id, "key", expected.key))
        require(header.version == 1 && header.suite == "HPKE-X25519-SHA256-CHACHA20POLY1305")
        require(header.service == expected.service && header.source == expected.source && header.recipient == identity.id &&
            header.recipient == expected.recipient && header.grantId == expected.grantId && header.keyId == descriptor.keyId &&
            header.expires > now && header.expires <= now + MailboxWire.RETENTION + 120_000 && header.generation > 0 && MailboxWire.isId(header.messageId))
        val h = hpke(); val pair = h.deserializePrivateKey(key.privateKey, key.publicKey)
        val content = MailboxWire.decode<MailboxContent>(h.setupBaseR(MailboxWire.unb64(envelope.enc), pair, MailboxWire.domain("hpke"))
            .open(bytes, MailboxWire.unb64(envelope.ciphertext)))
        content.event.validate(); require(content.storedAt > 0 && header.expires == content.storedAt + MailboxWire.RETENTION)
        return header to content
    }
}
