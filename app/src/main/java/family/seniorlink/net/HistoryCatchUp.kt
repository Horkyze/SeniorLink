package family.seniorlink.net

import family.seniorlink.core.Exchange
import family.seniorlink.core.Inbox
import family.seniorlink.core.catchUpPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Returns true when another connection is needed to finish history. */
internal suspend fun catchUpConnection(
    source: String,
    inbox: Inbox,
    exchange: Exchange,
    now: () -> Long = System::currentTimeMillis,
    onProgress: () -> Unit = {},
): Boolean {
    var completedPage = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val more = try {
            // Bound each page, not the entire retained history.
            withTimeout(35_000) { catchUpPage(source, inbox, exchange, now()) }
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            // Protocol-3 phones before connection reuse close after each receipt.
            // Also recover an interrupted catch-up from its durable cursor. Only
            // retry immediately after a completed page; a failed fresh connection
            // goes through the receiver's ordinary backoff.
            if (!completedPage) throw e
            return true
        }
        completedPage = true
        if (!more) return false
        onProgress()
    }
}
