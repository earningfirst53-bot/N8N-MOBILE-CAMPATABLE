package com.naten.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object WorkflowScheduler {
    private const val EXTRA_WORKFLOW_ID = "workflow_id"
    private const val REQUEST_BASE = 48100
    private const val MIN_INTERVAL_MS = 15_000L

    fun schedule(context: Context, state: WorkflowState): String? {
        cancel(context, state.id)
        if (!state.active) return null

        val trigger = state.nodes.firstOrNull { it.type == "Schedule Trigger" } ?: return null
        trigger.ensureDefaultConfig()

        val mode = trigger.config.optString("mode", "interval").lowercase(Locale.US)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, state.id)

        return when (mode) {
            "daily", "weekly" -> {
                val next = nextWallClock(trigger.config, mode)
                setAlarm(context, alarm, pi, next.timeInMillis, wallClock = true)
                val label = SimpleDateFormat("EEE, dd MMM HH:mm", Locale.getDefault()).format(next.time)
                "Next run: " + label
            }

            "once" -> {
                val millis = trigger.config.optLong("runAtEpochMs", 0L)
                if (millis <= System.currentTimeMillis()) {
                    "One-time schedule has passed"
                } else {
                    setAlarm(context, alarm, pi, millis, wallClock = true)
                    "Next run: " + SimpleDateFormat("EEE, dd MMM HH:mm", Locale.getDefault()).format(Date(millis))
                }
            }

            else -> {
                val amount = trigger.config.optLong("interval", 60).coerceAtLeast(1)
                val unit = trigger.config.optString("unit", "minutes").lowercase(Locale.US)
                val multiplier = when (unit) {
                    "seconds" -> 1_000L
                    "minutes" -> 60_000L
                    "hours" -> 3_600_000L
                    "days" -> 86_400_000L
                    else -> 60_000L
                }
                val delay = (amount * multiplier).coerceAtLeast(MIN_INTERVAL_MS)
                val elapsedAt = SystemClock.elapsedRealtime() + delay
                alarm.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedAt,
                    pi
                )
                "Next run in " + amount + " " + unit
            }
        }
    }

    fun cancel(context: Context, workflowId: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context, workflowId))
    }

    fun rescheduleAll(context: Context) {
        WorkflowStore.list(context)
            .filter { it.active }
            .forEach { schedule(context, it) }
    }

    fun startAutomationService(context: Context, workflowId: String? = null) {
        val intent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN
            putExtra(EXTRA_WORKFLOW_ID, workflowId)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            postSchedulerNotice(
                context,
                "NATEN automation could not start",
                t.message ?: "Android blocked the background service"
            )
        }
    }

    fun startAlwaysOnService(context: Context) {
        val intent = Intent(context, AutomationService::class.java).apply {
            action = AutomationService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            postSchedulerNotice(
                context,
                "NATEN webhook mode blocked",
                t.message ?: "Android blocked the foreground service"
            )
        }
    }

    private fun setAlarm(
        context: Context,
        alarm: AlarmManager,
        pi: PendingIntent,
        triggerAtMillis: Long,
        wallClock: Boolean
    ) {
        val safeMillis = maxOf(triggerAtMillis, System.currentTimeMillis() + 1_000L)

        if (
            wallClock &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarm.canScheduleExactAlarms()
        ) {
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                safeMillis,
                pi
            )
        } else if (wallClock) {
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                safeMillis,
                pi
            )
        } else {
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + (safeMillis - System.currentTimeMillis()),
                pi
            )
        }
    }

    private fun nextWallClock(config: org.json.JSONObject, mode: String): Calendar {
        val zoneName = config.optString("timezone").trim()
        val zone = if (zoneName.isBlank()) TimeZone.getDefault() else TimeZone.getTimeZone(zoneName)

        val now = Calendar.getInstance(zone)
        val candidate = Calendar.getInstance(zone).apply {
            timeInMillis = now.timeInMillis
            set(Calendar.HOUR_OF_DAY, config.optInt("hour", 9).coerceIn(0, 23))
            set(Calendar.MINUTE, config.optInt("minute", 0).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (mode == "weekly") {
            val wanted = config.optString("days", "MON").split(',')
                .mapNotNull { weekday(it.trim()) }
                .toSet()
                .ifEmpty { setOf(Calendar.MONDAY) }

            for (offset in 0..7) {
                val day = (now.get(Calendar.DAY_OF_WEEK) + offset - 1) % 7 + 1
                candidate.set(Calendar.DAY_OF_WEEK, day)
                if (offset == 0 && candidate.timeInMillis <= now.timeInMillis) continue
                if (day in wanted) return candidate
            }
            candidate.add(Calendar.WEEK_OF_YEAR, 1)
            return candidate
        }

        if (candidate.timeInMillis <= now.timeInMillis) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate
    }

    private fun weekday(value: String): Int? = when (value.uppercase(Locale.US)) {
        "SUN" -> Calendar.SUNDAY
        "MON" -> Calendar.MONDAY
        "TUE", "TUESDAY" -> Calendar.TUESDAY
        "WED" -> Calendar.WEDNESDAY
        "THU", "THURSDAY" -> Calendar.THURSDAY
        "FRI" -> Calendar.FRIDAY
        "SAT" -> Calendar.SATURDAY
        else -> null
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

    private fun postSchedulerNotice(context: Context, title: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "naten_automation"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    "NATEN Automation",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        manager.notify(
            19018,
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body.take(160))
                .setAutoCancel(true)
                .build()
        )
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = WorkflowScheduler.workflowId(intent) ?: return
        val state = WorkflowStore.load(context, id) ?: return
        if (!state.active) return

        WorkflowScheduler.schedule(context, state)
        WorkflowScheduler.startAutomationService(context, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> WorkflowScheduler.rescheduleAll(context)
        }

        val hasWebhook = WorkflowStore.list(context).any { state ->
            state.active && state.nodes.any {
                it.type == "Webhook Trigger" || it.type == "Chat Trigger"
            }
        }
        if (hasWebhook) WorkflowScheduler.startAlwaysOnService(context)
    }
}
