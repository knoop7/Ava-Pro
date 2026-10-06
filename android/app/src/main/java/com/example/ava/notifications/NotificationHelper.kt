package com.example.ava.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.ava.MainActivity
import com.example.ava.R
import com.example.ava.services.WebViewService
import com.example.ava.webcompat.EngineCapabilities

private const val VOICE_SATELLITE_SERVICE_CHANNEL_ID = "VoiceSatelliteService"
private const val BROWSER_SERVICE_CHANNEL_ID = "WebViewService"

/** White Ava mark. Adaptive [R.mipmap.ic_launcher] is circle-cropped by SystemUI. */
val NOTIFICATION_SMALL_ICON = R.drawable.ic_stat_ava

fun createVoiceSatelliteServiceNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channelName = "Voice Satellite Background Service"
        val chan = NotificationChannel(
            VOICE_SATELLITE_SERVICE_CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)
    }
}

fun createVoiceSatelliteServiceNotification(context: Context, content: String): Notification {
    val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationCompat.Builder(context, VOICE_SATELLITE_SERVICE_CHANNEL_ID)
    } else {
        @Suppress("DEPRECATION")
        NotificationCompat.Builder(context)
    }

    
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE
    } else {
        0
    }

    val mainIntent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        mainIntent,
        flags
    )
    val notification = notificationBuilder.setOngoing(true)
        .setSmallIcon(NOTIFICATION_SMALL_ICON)
        .setContentTitle(content)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setContentIntent(pendingIntent)
        .build()
    return notification
}

fun createBrowserServiceNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channelName = context.getString(
            if (EngineCapabilities.GECKO_BUNDLED) {
                R.string.gecko_engine_notification_channel
            } else {
                R.string.browser_service_notification_channel
            },
        )
        val chan = NotificationChannel(
            BROWSER_SERVICE_CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)
    }
}

fun createBrowserServiceNotification(context: Context): Notification {
    val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationCompat.Builder(context, BROWSER_SERVICE_CHANNEL_ID)
    } else {
        @Suppress("DEPRECATION")
        NotificationCompat.Builder(context)
    }

    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE
    } else {
        0
    }

    val gecko = EngineCapabilities.GECKO_BUNDLED
    val title = context.getString(
        if (gecko) R.string.gecko_engine_notification_title
        else R.string.browser_service_notification_title,
    )
    val builder = notificationBuilder.setOngoing(true)
        .setSmallIcon(NOTIFICATION_SMALL_ICON)
        .setContentTitle(title)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(Notification.CATEGORY_SERVICE)

    if (gecko) {
        val text = context.getString(R.string.gecko_engine_notification_text)
        builder.setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(
                0,
                context.getString(R.string.gecko_engine_notification_restart),
                browserServiceAction(context, WebViewService.ACTION_RESTART_SELF, 2, flags),
            )
            .addAction(
                0,
                context.getString(R.string.gecko_engine_notification_end),
                browserServiceAction(context, WebViewService.ACTION_END_SELF, 3, flags),
            )
        runCatching {
            val hostIntent = Intent().apply {
                setClassName(
                    com.example.ava.webcompat.BrowserEngine.HOST_PACKAGE,
                    MainActivity::class.java.name,
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    1,
                    hostIntent,
                    flags or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
    } else {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        builder.setContentIntent(PendingIntent.getActivity(context, 1, mainIntent, flags))
    }

    return builder.build()
}

private fun browserServiceAction(
    context: Context,
    action: String,
    requestCode: Int,
    flags: Int,
): PendingIntent {
    val intent = Intent(context, WebViewService::class.java).setAction(action)
    return PendingIntent.getService(
        context,
        requestCode,
        intent,
        flags or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

