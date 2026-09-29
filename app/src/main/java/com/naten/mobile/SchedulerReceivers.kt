package com.naten.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

object WorkflowScheduler {
    private const val EXTRA_WORKFLOW_ID = "workflow_id"
    private const val REQUEST_BASE = 48100

    fun schedule(context: Context, state: WorkflowState): String? {
        cancel(context, state.id)
        if (!state.active) return null

        val trigger = state.nodes.firstOrNull { it.type == "Schedule Trigger" } ?: return null
        trigger.ensureDefaultConfig()

        val amount = trigger.config.optLong("interval", 60).coerceAtLeast(1)
        val unit = trigger.config.optString("unit", "minutes").lowercase(Locale.US)
        val multiplier = when (unit) {
            "seconds" -> 1_000L
            "minutes" -> 60_000L
            "hours" -> 3_600_000L
            "days" -> 86_400_000L
            else -> 60_000L
        }

        val delay = (amount * multiplier).coerceAtLeast(60_000L)

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, state.id)
        alarm.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delay,
            pi
        )
        return "Next run in " + amount + " " + unit
    }

    fun cancel(context: Context, workflowId: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context, workflowId))
    }

    fun rescheduleAll(context: Context) {
        WorkflowStore.list(context).filter { it.active }.forEach {
            schedule(context, it)
        }
    }

    fun startAutomationService(context: Context, workflowId: String? = null) {
        val intent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN
            putExtra(EXTRA_WORKFLOW_ID, workflowId)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun startAlwaysOnService(context: Context) {
        val intent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun pendingIntent(context: Context, workflowId: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            putExtra(EXTRA_WORKFLOW_ID, workflowId)
        }
        val request = REQUEST_BASE + (abs(workflowId.hashCode()) % 10_000)
        return PendingIntent.getBroadcast(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun workflowId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_WORKFLOW_ID)
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = WorkflowScheduler.workflowId(intent) ?: return
        val state = WorkflowStore.load(context, id) ?: return
        if (!state.active) return

        // Schedule the next wake-up before execution so a failed run does not disable automation.
        WorkflowScheduler.schedule(context, state)
        WorkflowScheduler.startAutomationService(context, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> WorkflowScheduler.rescheduleAll(context)
        }

        val hasWebhook = WorkflowStore.list(context).any { state ->
            state.active && state.nodes.any {
                it.type == "Webhook Trigger" || it.type == "Chat Trigger"
            }
        }
        if (hasWebhook) WorkflowScheduler.startAlwaysOnService(context)
    }
}
