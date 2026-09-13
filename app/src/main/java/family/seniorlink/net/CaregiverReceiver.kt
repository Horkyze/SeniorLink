package family.seniorlink.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One receiver owns the endpoint across activity/service handoffs, without overlapping pulls. */
internal class CaregiverReceiver(scope: CoroutineScope, receive: suspend (pollMs: () -> Long) -> Unit) {
    private data class Demand(val visible: Boolean = false, val background: Boolean = false)
    private val demand = MutableStateFlow(Demand())

    init {
        scope.launch {
            demand.map { it.visible || it.background }.distinctUntilChanged().collectLatest { enabled ->
                if (enabled) receive { if (demand.value.visible) 15_000L else 60_000L }
            }
        }
    }

    fun visible(enabled: Boolean) { demand.update { it.copy(visible = enabled) } }
    fun background(enabled: Boolean) { demand.update { it.copy(background = enabled) } }
}
