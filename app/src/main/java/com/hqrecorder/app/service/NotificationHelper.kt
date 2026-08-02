package com.hqrecorder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hqrecorder.app.MainActivity
import com.hqrecorder.app.R
import com.hqrecorder.app.audio.RecordingState

class NotificationHelper(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(state: RecordingState, elapsedMs: Long): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.notification_recording_title))
            .setContentText(formatElapsed(elapsedMs))
            .setOngoing(true)
            .setContentIntent(openAppIntent())

        when (state) {
            RecordingState.RECORDING -> builder.addAction(
                0, context.getString(R.string.action_pause), servicePendingIntent(RecordingService.ACTION_PAUSE)
            )
            RecordingState.PAUSED -> builder.addAction(
                0, context.getString(R.string.action_resume), servicePendingIntent(RecordingService.ACTION_RESUME)
            )
            else -> Unit
        }
        builder.addAction(0, context.getString(R.string.action_stop), servicePendingIntent(RecordingService.ACTION_STOP))

        return builder.build()
    }

    fun notify(id: Int, notification: Notification) {
        manager.notify(id, notification)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    companion object {
        const val CHANNEL_ID = "recording_channel"
    }
}
