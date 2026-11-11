package family.seniorlink.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One receiver owns the endpoint across activity/service handoffs, without overlapping pulls. */
internal class CaregiverReceiver(scope: CoroutineScope, receive: suspend (idle: suspend () -> Unit) -> Unit) {
    private data class Demand(val visible: Boolean = false, val background: Boolean = false)
    private val demand = MutableStateFlow(Demand())

    init {
        scope.launch {
            demand.map { it.visible || it.background }.distinctUntilChanged().collectLatest { enabled ->
                if (enabled) receive {
                    // Background pulls only warm the cache, so they are rare; opening the app refreshes at once.
                    val visible = demand.value.visible
                    withTimeoutOrNull(if (visible) VISIBLE_MS else BACKGROUND_MS) { demand.first { it.visible && !visible } }
                }
            }
        }
    }

    companion object { const val VISIBLE_MS = 15_000L; const val BACKGROUND_MS = 300_000L }

    fun visible(enabled: Boolean) { demand.update { it.copy(visible = enabled) } }
    fun background(enabled: Boolean) { demand.update { it.copy(background = enabled) } }
}
