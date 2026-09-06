package family.seniorlink.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import family.seniorlink.SeniorApp
import family.seniorlink.core.*
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION || !MonitorService.running.value) return
        val app = context.applicationContext as SeniorApp
        val settings = app.store.settings
        if (settings.role != Role.SHARER || !settings.sms) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val sender = messages.first().originatingAddress ?: return
        if (messages.any { it.originatingAddress != sender } || !SmsPolicy.permits(sender, settings.smsSenders)) return
        val pending = goAsync()
        app.scope.launch {
            try {
                val text = messages.joinToString("") { it.messageBody.orEmpty() }
                app.record(Event(
                    0, Kind.SMS, System.currentTimeMillis(), sender = sender.take(100),
                    body = if (settings.smsBodies) SmsPolicy.safeBody(text) else null,
                ))
            } finally {
                pending.finish()
            }
        }
    }
}
