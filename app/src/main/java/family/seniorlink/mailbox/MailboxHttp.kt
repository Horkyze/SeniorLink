package family.seniorlink.mailbox

import computer.iroh.EndpointId
import computer.iroh.SecretKey
import computer.iroh.Signature
import family.seniorlink.core.mailbox.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class IrohMailboxIdentity(private val secret: ByteArray) : MailboxIdentity {
    override val id get() = SecretKey.fromBytes(secret).use { it.public().use { p -> p.toString() } }
    override fun sign(bytes: ByteArray): ByteArray = SecretKey.fromBytes(secret).use { it.sign(bytes).use { s -> s.toBytes() } }
    override fun verify(id: String, bytes: ByteArray, signature: ByteArray) {
        EndpointId.fromString(id).use { key -> Signature.fromBytes(signature).use { key.verify(bytes,it) } }
    }
}
class MailboxHttp(private val identity: MailboxIdentity) {
    class Error(val code: String) : Exception(code)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    fun call(service: MailboxService, source: String, operation: String, body: MailboxRequest = MailboxRequest()): MailboxResponse {
        service.validate(); require(MailboxWire.isId(source) && operation in setOf("info","register","policy","put","fetch","ack","receipts","close","confirm","reject"))
        val url="${service.origin}/v1/sources/$source/$operation"; val raw=MailboxWire.encode(body)
        require(raw.size<=MailboxWire.MAX_BODY)
        val digest="sha-256=:${MailboxWire.b64(MessageDigest.getInstance("SHA-256").digest(raw))}:"
        val input=MailboxWire.signatureInput(identity.id,System.currentTimeMillis()/1000,MailboxWire.randomId())
        val request=Request.Builder().url(url).header("Content-Type","application/json")
            .header("Content-Digest",digest).header("Signature-Input","sl=$input")
            .header("Signature","sl=:${MailboxWire.b64(identity.sign(MailboxWire.requestBase(url,digest,input)))}:")
            .post(raw.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { result ->
            val response=result.body ?: throw Error("EMPTY_RESPONSE")
            val stream=response.byteStream(); val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val n=stream.read(buffer);if(n<0)break;check(output.size()+n<=MailboxWire.MAX_BODY);output.write(buffer,0,n) }
            val bytes=output.toByteArray()
            if (!result.isSuccessful) {
                val error=runCatching { MailboxWire.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).toString() }.getOrDefault("")
                val known=listOf("REVOKED","PAUSED","EXPIRED","FULL","NOT_ADMITTED","NOT_INITIALIZED","NOT_FOUND","POLICY_STALE","STALE_INCARNATION","SIGNATURE_EXPIRED")
                throw Error(known.firstOrNull { error.contains("\"$it\"") } ?: "UNAVAILABLE")
            }
            return MailboxWire.decode<MailboxResponse>(bytes).also { require(it.version==1) }
        }
    }
}
