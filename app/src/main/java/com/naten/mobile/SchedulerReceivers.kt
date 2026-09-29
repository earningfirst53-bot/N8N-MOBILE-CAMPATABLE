package com.naten.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlin.math.max

object WorkflowScheduler {
    private const val REQUEST_CODE = 90817

    fun schedule(context: Context, state: WorkflowState): String? {
        cancel(context)
        if (!state.active) return null

        val node = state.nodes.firstOrNull { it.type == "Schedule Trigger" } ?: return null
        node.ensureDefaultConfig()
        val interval = node.config.optLong("interval", 60).coerceAtLeast(1)
        val unit = node.config.optString("unit", "minutes")

        val multiplier = when (unit.lowercase()) {
            "seconds" -> 1_000L
            "minutes" -> 60_000L
            "hours" -> 3_600_000L
            "days" -> 86_400_000L
            else -> 60_000L
        }

        val intervalMs = max(60_000L, interval * multiplier)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context)
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + intervalMs,
            intervalMs,
            pending
        )
        return "Every " + interval + " " + unit
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val state = WorkflowJson.load(context) ?: return
        if (!state.active) return
        if (state.nodes.none { it.type == "Schedule Trigger" }) return
        WorkflowEngine(context.applicationContext).run(
            state = state,
            listener = null,
            background = true
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val state = WorkflowJson.load(context) ?: return
        if (state.active) WorkflowScheduler.schedule(context, state)
    }
}
