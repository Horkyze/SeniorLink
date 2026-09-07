package family.seniorlink.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import family.seniorlink.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(
    private val check: suspend () -> UpdateCheckResult = { ReleaseChecker().check(BuildConfig.VERSION_NAME) },
) : ViewModel() {
    private val pending = MutableStateFlow<AppUpdate?>(null)
    val availableUpdate = pending.asStateFlow()
    private val busy = MutableStateFlow(false)
    val checking = busy.asStateFlow()
    private val feedback = MutableStateFlow<UpdateCheckResult?>(null)
    val manualResult = feedback.asStateFlow()

    init {
        // Independent of storage, monitoring and pairing. Survives activity recreation.
        runCheck(manual = false)
    }

    fun dismiss() { pending.value = null }

    fun checkForUpdates() = runCheck(manual = true)

    private fun runCheck(manual: Boolean) {
        // Includes the startup request: repeated taps cannot start competing checks.
        if (busy.value) return
        busy.value = true
        if (manual) { feedback.value = null; pending.value = null }
        viewModelScope.launch {
            try {
                val result = check()
                pending.value = (result as? UpdateCheckResult.Available)?.update
                if (manual) feedback.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (manual) feedback.value = UpdateCheckResult.Failed
            } finally {
                busy.value = false
            }
        }
    }
}
