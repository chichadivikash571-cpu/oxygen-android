package ai.oxygen.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Stores a minimal copy of active reminders on-device and schedules them with AlarmManager.
 * No cloud credentials or Supabase tokens are stored by this component.
 */
object LocalReminderScheduler {
    private const val PREFS = "oxygen_local_reminders"
    private const val KEY_ITEMS = "items"
    private const val ACTION_FIRE = "ai.oxygen.app.ACTION_LOCAL_REMINDER"

    fun sync(context: Context, rawJson: String) {
        val incoming = JSONArray(rawJson)
        val clean = JSONArray()
        for (i in 0 until incoming.length()) {
            val item = incoming.optJSONObject(i) ?: continue
            if (item.optString("task_type") != "reminder" || item.optString("status") != "active") continue
            val id = item.optString("id")
            val title = item.optString("title").trim()
            val whenText = item.optString("next_run_at")
            if (id.isBlank() || title.isBlank() || whenText.isBlank()) continue
            val epoch = try { Instant.parse(whenText).toEpochMilli() } catch (_: Exception) { continue }
            val now = System.currentTimeMillis()
            val fireAt = if (epoch <= now) now + 1_000L else epoch
            clean.put(JSONObject().apply {
                put("id", id)
                put("title", title)
                put("next_run_at", whenText)
                put("fire_at", Instant.ofEpochMilli(fireAt).toString())
                put("schedule_type", item.optString("schedule_type", "once"))
                put("schedule_config", item.optJSONObject("schedule_config") ?: JSONObject())
                put("timezone", item.optString("timezone", "Asia/Kolkata"))
            })
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = JSONArray(prefs.getString(KEY_ITEMS, "[]") ?: "[]")
        for (i in 0 until previous.length()) cancel(context, previous.optJSONObject(i)?.optString("id") ?: "")
        prefs.edit().putString(KEY_ITEMS, clean.toString()).apply()
        for (i in 0 until clean.length()) schedule(context, clean.getJSONObject(i))
    }

    fun restore(context: Context) {
        val items = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]") ?: "[]")
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            schedule(context, item)
        }
    }

    fun handleFire(context: Context, intent: Intent) {
        val id = intent.getStringExtra("task_id") ?: return
        val title = intent.getStringExtra("title") ?: "OXYGEN reminder"
        val scheduledFor = intent.getStringExtra("scheduled_for") ?: ""
        showNotification(context, id, title, scheduledFor)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = JSONArray(prefs.getString(KEY_ITEMS, "[]") ?: "[]")
        val updated = JSONArray()
        var recurringItem: JSONObject? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("id") == id) {
                if (item.optString("schedule_type") == "recurring") recurringItem = item
            } else updated.put(item)
        }

        recurringItem?.let { item ->
            val next = nextRecurring(item, scheduledFor)
            if (next != null) {
                item.put("next_run_at", next)
                item.put("fire_at", next)
                updated.put(item)
                schedule(context, item)
            }
        }
        prefs.edit().putString(KEY_ITEMS, updated.toString()).apply()
    }

    private fun schedule(context: Context, item: JSONObject) {
        val id = item.optString("id")
        val title = item.optString("title")
        val whenText = item.optString("next_run_at")
        val fireAtText = item.optString("fire_at", whenText)
        val parsedEpoch = try { Instant.parse(fireAtText).toEpochMilli() } catch (_: Exception) { return }
        val epoch = maxOf(parsedEpoch, System.currentTimeMillis() + 1_000L)

        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, id, title, whenText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, pending)
        }
    }

    private fun cancel(context: Context, id: String) {
        if (id.isBlank()) return
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pendingIntent(context, id, "", ""))
    }

    private fun pendingIntent(context: Context, id: String, title: String, whenText: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("task_id", id)
            putExtra("title", title)
            putExtra("scheduled_for", whenText)
        }
        val requestCode = id.hashCode()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showNotification(context: Context, id: String, title: String, whenText: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channelId = "oxygen_local_reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(channelId) == null
        ) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId, "OXYGEN reminders", android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Scheduled reminders created in OXYGEN" }
            )
        }
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_REMINDER_TEXT, title)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tap = PendingIntent.getActivity(
            context, id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OXYGEN Reminder")
            .setContentText(title)
            .setStyle(android.app.Notification.BigTextStyle().bigText(title))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .build()
        manager.notify(id.hashCode(), notification)
    }

    private fun nextRecurring(item: JSONObject, scheduledFor: String): String? {
        return try {
            val config = item.optJSONObject("schedule_config") ?: return null
            val zone = java.time.ZoneId.of(item.optString("timezone", "Asia/Kolkata"))
            val current = Instant.parse(scheduledFor).atZone(zone)
            val time = config.optString("time").split(":")
            val hour = time[0].toInt()
            val minute = time[1].toInt()
            val interval = config.optInt("interval", 1).coerceIn(1, 12)
            val base = current.plusMinutes(1).withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            val next = when (config.optString("frequency")) {
                "daily" -> {
                    var candidate = base
                    while (!candidate.isAfter(current)) candidate = candidate.plusDays(interval.toLong())
                    candidate
                }
                "weekly" -> {
                    val target = config.optInt("day_of_week", 1).coerceIn(0, 6)
                    var candidate = base
                    while (candidate.dayOfWeek.value % 7 != target || !candidate.isAfter(current)) {
                        candidate = candidate.plusDays(1)
                    }
                    if (interval > 1) candidate = candidate.plusWeeks((interval - 1).toLong())
                    candidate
                }
                "monthly" -> {
                    val day = config.optInt("day_of_month", current.dayOfMonth).coerceIn(1, 31)
                    var candidate = base.withDayOfMonth(1).plusMonths(interval.toLong())
                    candidate = candidate.withDayOfMonth(minOf(day, candidate.toLocalDate().lengthOfMonth()))
                    candidate
                }
                else -> return null
            }
            next.toInstant().toString()
        } catch (_: Exception) { null }
    }
}
