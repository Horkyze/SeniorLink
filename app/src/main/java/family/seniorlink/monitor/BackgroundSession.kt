package family.seniorlink.monitor

import android.content.Context
import android.annotation.SuppressLint
import family.seniorlink.core.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local authorization, separate from feature settings and never sent to another phone. */
@SuppressLint("UseKtx") // Check synchronous commit results before reporting durable authorization.
class BackgroundSession(context: Context, name: String = "background-session") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(readRole())
    val role = state.asStateFlow()
    val startedAt: Long get() = prefs.getLong("startedAt", 0)
    val recoverySuppressed: Boolean get() = prefs.getBoolean("recoverySuppressed", false)

    private fun readRole() = runCatching { Role.valueOf(prefs.getString("role", "UNSET")!!) }.getOrDefault(Role.UNSET)
    fun enabled(role: Role) = role != Role.UNSET && state.value == role

    /** Apply the connection default once. A stored Pause must survive every later connection. */
    @Synchronized fun enableAfterConnection(role: Role, hasApprovedPeer: Boolean): Boolean {
        if (role == Role.UNSET || !hasApprovedPeer || prefs.contains("role")) return false
        enable(role)
        return true
    }

    @Synchronized fun enable(role: Role) {
        require(role != Role.UNSET)
        check(prefs.edit().putString("role", role.name).putLong("startedAt", System.currentTimeMillis())
            .putBoolean("recoverySuppressed", false).commit())
        state.value = role
    }

    @Synchronized fun allowRecovery() {
        check(prefs.edit().putLong("startedAt", System.currentTimeMillis()).putBoolean("recoverySuppressed", false).commit())
    }

    @Synchronized fun suppressRecovery() {
        check(prefs.edit().putBoolean("recoverySuppressed", true).commit())
    }

    @Synchronized fun pause() {
        // Close the in-process gate even if storage has become unwritable.
        state.value = Role.UNSET
        check(prefs.edit().putString("role", Role.UNSET.name).commit())
    }
}
