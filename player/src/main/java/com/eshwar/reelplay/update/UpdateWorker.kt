package com.eshwar.reelplay.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eshwar.reelplay.MainActivity
import com.eshwar.reelplay.R
import java.util.concurrent.TimeUnit

/**
 * Checks the update server every few hours, even when ReelPlay isn't open, and posts one
 * notification per new version. Tapping it opens the app straight into the update dialog.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val update = try {
            Updates.fetch()
        } catch (_: Exception) {
            return Result.retry()
        } ?: return Result.success()
        if (Updates.shouldNotify(applicationContext, update)) notify(applicationContext, update)
        return Result.success()
    }

    companion object {
        private const val WORK = "reelplay-update-check"
        private const val CHANNEL = "app_updates"
        const val EXTRA_SHOW_UPDATE = "show_update"

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, update: AppUpdate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_SHOW_UPDATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("All Media Player ${update.versionName} is available")
                .setContentText(update.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "Tap to update")
                .setStyle(NotificationCompat.BigTextStyle().bigText(update.notes.ifBlank { "Tap to update" }))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(CHANNEL.hashCode(), n)
        }
    }
}
