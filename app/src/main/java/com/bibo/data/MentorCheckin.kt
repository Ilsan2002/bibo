package com.bibo.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.bibo.MainActivity
import com.bibo.ui.Mentor
import com.bibo.ui.MentorSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

object MentorCheckin {
    const val ACTION_CHECKIN = "com.bibo.action.MENTOR_CHECKIN"
    private const val WORK = "mentor-checkins"
    private const val MAINTENANCE = "mentor-memory-maintenance"
    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        (0..14).forEach { slot ->
            PendingIntent.getBroadcast(context, 200 + slot, Intent(context, MentorCheckinReceiver::class.java).setAction(ACTION_CHECKIN), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { alarm.cancel(it); it.cancel() }
        }
        val manager = WorkManager.getInstance(context)
        if (!Mentor.isConnected(context) || !MentorSettings.enabled(context)) { manager.cancelUniqueWork(WORK); return }
        val request = PeriodicWorkRequestBuilder<MentorCheckinWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun maintenance(context: Context) {
        if (!Mentor.isConnected(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(MAINTENANCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MentorMaintenanceWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun pause(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK); WorkManager.getInstance(context).cancelUniqueWork(MAINTENANCE) }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(MAINTENANCE)
        schedule(context)
    }
    fun notify(context: Context, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("mentor_checkin", "Mentor check-ins", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_MENTOR), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(78, NotificationCompat.Builder(context, "mentor_checkin").setContentTitle("Your mentor").setContentText(message.take(140)).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setSmallIcon(android.R.drawable.ic_dialog_email).setAutoCancel(true).setContentIntent(open).build())
    }
}
class MentorCheckinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BOOT_COMPLETED) MentorCheckin.schedule(context) }
}
class MentorCheckinWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Mentor.isConnected(applicationContext) || !MentorSettings.enabled(applicationContext)) return Result.success()
        return try { withTimeout(20_000) { Mentor.checkIn(applicationContext) }?.let { MentorCheckin.notify(applicationContext, it) }; Result.success() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
class MentorMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { withTimeout(65_000) { Mentor.compactPending(applicationContext) }; Result.success() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }
}
