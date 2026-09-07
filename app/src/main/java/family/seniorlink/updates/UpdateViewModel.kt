package family.seniorlink.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import family.seniorlink.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(
    check: suspend () -> AppUpdate? = { ReleaseChecker().latest(BuildConfig.VERSION_NAME) },
) : ViewModel() {
    private val pending = MutableStateFlow<AppUpdate?>(null)
    val availableUpdate = pending.asStateFlow()

    init {
        // Independent of storage, monitoring and pairing. Survives activity recreation.
        viewModelScope.launch { pending.value = check() }
    }

    fun dismiss() { pending.value = null }
}
