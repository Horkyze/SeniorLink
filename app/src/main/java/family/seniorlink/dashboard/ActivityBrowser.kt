package family.seniorlink.dashboard

import family.seniorlink.core.Kind
import family.seniorlink.core.Role
import family.seniorlink.data.ActivityDay
import family.seniorlink.data.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId

internal data class ActivityRequest(
    val source: String? = null,
    val date: LocalDate? = null,
    val kind: Kind? = null,
    val showing: Boolean = false,
    val fullHistory: Boolean = false,
    val limit: Int = 40,
)
internal data class ActivityState(
    val request: ActivityRequest = ActivityRequest(),
    val day: ActivityDay? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
)

/** Queries run only while the UI observes them; no new background collection or polling. */
internal class ActivityBrowser(scope: CoroutineScope, private val store: Store, private val ownId: () -> String) {
    private val selection = MutableStateFlow(ActivityRequest())
    private var cached: Pair<Long, ActivityState>? = null
    private val clock = flow {
        while (currentCoroutineContext().isActive) {
            emit(LocalDate.now() to ZoneId.systemDefault())
            delay(30_000)
        }
    }.distinctUntilChanged()
    @OptIn(ExperimentalCoroutinesApi::class)
    val state = combine(store.changes, selection, clock) { revision, request, calendar -> Triple(revision, request, calendar) }
        .transformLatest { (revision, request, calendar) ->
            // Keep the visible prefix while explicitly loading more, but discard it on data changes.
            val previous = cached?.takeIf { (savedRevision, saved) -> savedRevision == revision &&
                saved.request.limit != request.limit && saved.request.copy(limit = request.limit) == request &&
                saved.day?.date == (request.date ?: calendar.first) }?.second?.day
            emit(ActivityState(request, previous))
            try {
                val day = withContext(Dispatchers.IO) {
                    val peers = store.peers()
                    val source = when (store.settings.role) {
                        Role.SHARER -> ownId()
                        Role.CAREGIVER -> peers.firstOrNull { it.id == request.source }?.id ?: peers.firstOrNull()?.id
                        Role.UNSET -> null
                    }
                    source?.let { store.activityDay(it, request.date ?: calendar.first, request.kind,
                        if (!request.showing) 0 else if (request.fullHistory) request.limit else 4, zone = calendar.second) }
                }
                val result = ActivityState(request, day, loading = false)
                cached = revision to result
                emit(result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emit(ActivityState(request, loading = false, error = true)) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ActivityState())

    fun source(id: String) { selection.update { ActivityRequest(source = id, date = it.date) } }
    fun date(date: LocalDate) { selection.update { it.copy(date = date, limit = 40) } }
    fun today() { selection.update { it.copy(date = null, limit = 40) } }
    fun open(kind: Kind?) { selection.update { it.copy(kind = kind, showing = true, fullHistory = kind == null, limit = 40) } }
    fun openLatest(kind: Kind, at: Long?) { selection.update { it.copy(kind = kind,
        date = at?.let { time -> java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate() },
        showing = true, fullHistory = false, limit = 40) } }
    fun expand() { selection.update { it.copy(fullHistory = true, limit = 40) } }
    fun filter(kind: Kind?) { selection.update { it.copy(kind = kind, limit = 40) } }
    fun more() { selection.update { it.copy(limit = (it.limit + 40).coerceAtMost(10_000)) } }
    fun close() { selection.update { it.copy(showing = false, fullHistory = false, kind = null, limit = 40) } }
}
