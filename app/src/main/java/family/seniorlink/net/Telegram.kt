package family.seniorlink.net

import family.seniorlink.SeniorApp
import family.seniorlink.core.Event
import family.seniorlink.core.Kind
import family.seniorlink.monitor.MonitorService
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.min

object Telegram {
    data class Result(val success: Boolean, val retrySeconds: Long?, val status: String)

    fun validToken(token: String) = Regex("[0-9]{5,20}:[A-Za-z0-9_-]{20,100}").matches(token)
    fun validChat(chat: String) = Regex("-?[0-9]{1,25}|@[A-Za-z0-9_]{5,64}").matches(chat)

    suspend fun run(app: SeniorApp) {
        while (currentCoroutineContext().isActive) {
            val settings = app.store.settings
            if (settings.telegram && MonitorService.running.value) {
                val job = app.store.nextTelegram(System.currentTimeMillis())
                if (job != null) {
                    val token = app.secrets.read("telegram")?.toString(Charsets.UTF_8).orEmpty()
                    val result = send(token, settings.telegramChat, describe(job.event))
                    app.telegramStatus.value = result.status
                    val retry = if (result.success) null else System.currentTimeMillis() +
                        (result.retrySeconds ?: min(3600, 30L shl job.attempts.coerceAtMost(7))) * 1000
                    app.store.finishTelegram(job, retry)
                    delay(1000)
                    continue
                }
            }
            delay(15_000)
        }
    }

    suspend fun send(token: String, chat: String, text: String): Result = withContext(Dispatchers.IO) {
        if (!validToken(token) || !validChat(chat)) return@withContext Result(false, 3600, "Check the bot token and chat ID")
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = buildJsonObject {
                put("chat_id", chat)
                put("text", text.take(3500))
                putJsonObject("link_preview_options") { put("is_disabled", true) }
            }.toString().toByteArray()
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            // Do not log exception messages or request URLs: they contain the bot token.
            if (code in 200..299) Result(true, null, "Telegram message delivered")
            else if (code == 429) {
                val bytes = connection.errorStream?.use { stream ->
                    val buffer = ByteArray(8192)
                    var size = 0
                    while (size < buffer.size) {
                        val count = stream.read(buffer, size, buffer.size - size)
                        if (count <= 0) break
                        size += count
                    }
                    buffer.copyOf(size)
                }
                val seconds = runCatching {
                    Json.parseToJsonElement(bytes?.toString(Charsets.UTF_8).orEmpty())
                        .jsonObject["parameters"]?.jsonObject?.get("retry_after")?.jsonPrimitive?.long
                }.getOrNull()?.coerceIn(1, 86_400) ?: 60
                Result(false, seconds, "Telegram rate limited — retry scheduled")
            } else if (code in 400..499) Result(false, 3600, "Telegram rejected the request ($code); check configuration")
            else Result(false, null, "Telegram unavailable — retry scheduled")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result(false, null, "Telegram connection failed — retry scheduled")
        } finally {
            connection?.disconnect()
        }
    }

    fun describe(event: Event): String = buildString {
        append("SeniorLink • ${Instant.ofEpochMilli(event.occurredAt)}\n")
        append(when (event.kind) {
            Kind.UNLOCK -> "Phone unlocked"
            Kind.CHECK_IN -> "I'm okay — manual check-in"
            Kind.LOCATION -> "Location: ${event.latitude}, ${event.longitude} (±${event.accuracy?.toInt()} m)"
            Kind.SMS -> "SMS from ${event.sender}\n${event.body ?: "[Message body not shared]"}"
        })
    }
}
