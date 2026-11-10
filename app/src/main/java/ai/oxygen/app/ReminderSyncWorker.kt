package ai.oxygen.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ReminderSyncWork {
    private const val UNIQUE = "oxygen_authenticated_reminder_sync"
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<ReminderSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
}

class ReminderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val refreshToken = ReminderSessionStore.read(applicationContext) ?: return@withContext Result.success()
        try {
            val refreshed = requestJson(
                "https://ktfiklylcbthojqjxcef.supabase.co/auth/v1/token?grant_type=refresh_token",
                "POST",
                JSONObject().put("refresh_token", refreshToken).toString(),
                null
            )
            val access = refreshed.optString("access_token")
            val rotated = refreshed.optString("refresh_token", refreshToken)
            if (access.isBlank()) return@withContext Result.retry()
            if (rotated.isNotBlank() && rotated != refreshToken) ReminderSessionStore.save(applicationContext, rotated)

            val prefs = applicationContext.getSharedPreferences("oxygen_reminder_sync_state", Context.MODE_PRIVATE)
            val since = prefs.getString("last_sync_at", null)
            val body = JSONObject().put("since", since ?: JSONObject.NULL).toString()
            val result = requestJson(
                "https://ktfiklylcbthojqjxcef.supabase.co/functions/v1/native-reminder-sync",
                "POST", body, access
            )
            val tasks = result.optJSONArray("tasks") ?: org.json.JSONArray()
            LocalReminderScheduler.sync(applicationContext, tasks.toString())
            val missed = result.optJSONArray("missed_runs") ?: org.json.JSONArray()
            for (i in 0 until missed.length()) {
                val run = missed.optJSONObject(i) ?: continue
                LocalReminderScheduler.notifyMissedRun(
                    applicationContext,
                    run.optString("run_id"),
                    run.optString("task_id"),
                    run.optString("title", "Scheduled reminder"),
                    run.optString("scheduled_for", "")
                )
            }
            val serverTime = result.optString("server_time")
            if (serverTime.isNotBlank()) prefs.edit().putString("last_sync_at", serverTime).apply()
            Result.success()
        } catch (_: java.net.SocketTimeoutException) {
            Result.retry()
        } catch (_: java.io.IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun requestJson(url: String, method: String, body: String, bearer: String?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", "sb_publishable_UZ-yfy_PfFExCdNjEhkJiw__6VjRU9D")
            setRequestProperty("Content-Type", "application/json")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("Reminder sync HTTP $code")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
