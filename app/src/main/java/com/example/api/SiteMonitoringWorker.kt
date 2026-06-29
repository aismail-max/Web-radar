package com.example.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.SiteRepository
import com.example.data.UpdateRecord
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*

class SiteMonitoringWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "MonitoringWorker"
    private val CHANNEL_ID = "site_radar_alerts"

    override suspend fun doWork(): Result {
        Log.d(TAG, "Background monitoring worker started.")

        // Enforce the Business Hours check in Asia/Bahrain Timezone
        // "يبدأ البحث تلقائيًا يوميًا عند الساعة 07:00 صباحًا بتوقيت مملكة البحرين"
        // "ينتهي البحث يوميًا عند الساعة 11:00 مساءً بتوقيت مملكة البحرين"
        val bahrainTimeZone = TimeZone.getTimeZone("Asia/Bahrain")
        val calendar = Calendar.getInstance(bahrainTimeZone)
        
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        Log.d(TAG, "Current hour in Asia/Bahrain is: $hour")

        // 7:00 AM to 11:00 PM (inclusive of hour 7 up to 22:59)
        // Hour 23 is 11:00 PM. So if hour is < 7 or >= 23, we stop scanning.
        if (hour < 7 || hour >= 23) {
            Log.d(TAG, "Outside Bahrain working hours (07:00 AM - 11:00 PM). Monitoring suspended.")
            return Result.success()
        }

        try {
            val database = AppDatabase.getDatabase(context)
            val repository = SiteRepository(database.siteDao)

            // Gather all active sites
            val activeSites = database.siteDao.getActiveSites()
            Log.d(TAG, "Found ${activeSites.size} active sites to monitor.")

            for (site in activeSites) {
                if (isStopped) break
                repository.refreshSiteUpdates(site) { newUpdate ->
                    // Trigger a system push notification for each new discovery
                    showSystemNotification(context, newUpdate)
                }
                // Minor delay to stagger network connections and heavy parses (prevents memory spike/crash)
                kotlinx.coroutines.delay(1200)
            }

            return Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in background monitoring work: ", e)
            return Result.retry()
        }
    }

    private fun showSystemNotification(context: Context, update: UpdateRecord) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create Channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "تنبيهات رادار المواقع"
            val channelDescription = "تنبيهات تظهر عند رصد محتوى جديد في المواقع المفعلة."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Action Intent to open the news link directly in mobile browser
        val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(update.url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val webPendingIntent = PendingIntent.getActivity(
            context,
            update.id,
            urlIntent,
            pendingIntentFlags
        )

        // Capture local friendly time format
        val sdf = SimpleDateFormat("hh:mm a", Locale.forLanguageTag("ar-u-nu-latn"))
        sdf.timeZone = TimeZone.getTimeZone("Asia/Bahrain")
        val formattedTime = sdf.format(Date(update.discoveryTime))

        val titleText = "🔔 تحديث جديد: ${update.siteName}"
        val bodyText = """
            ${update.title}
            النوع: ${update.type} | الوقت: $formattedTime
        """.trimIndent()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder) // Standard icon
            .setContentTitle(titleText)
            .setContentText(update.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$bodyText\n\n${update.briefDescription}"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(webPendingIntent)
            // Arabic Action Button (زر فتح الخبر)
            .addAction(
                android.R.drawable.ic_menu_view,
                "فتح الخبر",
                webPendingIntent
            )

        notificationManager.notify(update.id, builder.build())
    }
}
