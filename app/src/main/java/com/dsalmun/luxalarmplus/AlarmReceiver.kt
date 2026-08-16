/*
 * This file is part of luxAlarm+, authored by Daniel Salmun.
 *
 * luxAlarm+ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * luxAlarm+ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with luxAlarm+.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.dsalmun.luxalarmplus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.dsalmun.luxalarmplus.data.RingingAlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val alarmIds = intent?.getIntegerArrayListExtra("alarm_ids") ?: arrayListOf()
        val alarmId = alarmIds.firstOrNull() ?: -1
        val ringtoneUri = intent?.getStringExtra("ringtone_uri")
        val volume =
            if (intent?.hasExtra("volume") == true) intent.getFloatExtra("volume", 1.0f) else null
        val vibrationEnabled = intent?.getBooleanExtra("vibration_enabled", true) ?: true

        if (AppContainer.repository.setRingingAlarm()) {
            // Persist ringing state so AlarmActivity can reconstruct if killed.
            AppContainer.repository.saveRingingAlarmState(
                RingingAlarmState(alarmId, ringtoneUri, volume, vibrationEnabled)
            )
            // Post a notification with a full-screen intent that launches AlarmActivity.
            // No foreground service is used — this avoids the "Force stop" menu
            // in the notification shade.
            postAlarmNotification(context, alarmId, ringtoneUri, volume, vibrationEnabled)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppContainer.repository.deactivateOneShotAlarms(alarmIds.toList())
                AppContainer.repository.scheduleNextAlarm()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postAlarmNotification(
        context: Context,
        alarmId: Int,
        ringtoneUri: String?,
        volume: Float?,
        vibrationEnabled: Boolean,
    ) {
        postAlarmNotification(context, alarmId, ringtoneUri, volume, vibrationEnabled, ALARM_NOTIFICATION_ID)
    }

    private fun createNotificationChannel(context: Context, ringtoneUri: Uri?) {
        createAlarmNotificationChannel(context, ringtoneUri)
    }

    companion object {
        const val ALARM_CHANNEL_ID = "alarm_channel_id"
        const val ALARM_NOTIFICATION_ID = 1001

        /**
         * Posts an alarm notification with a full-screen intent that launches [AlarmActivity].
         * Can be called from [AlarmReceiver] (real alarm) or from [AlarmScreen] (mock/test alarm).
         */
        fun postAlarmNotification(
            context: Context,
            alarmId: Int,
            ringtoneUri: String?,
            volume: Float?,
            vibrationEnabled: Boolean,
            notificationId: Int = ALARM_NOTIFICATION_ID,
        ) {
            createAlarmNotificationChannel(context, ringtoneUri?.toUri())

            val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("alarm_id", alarmId)
                ringtoneUri?.let { putExtra("ringtone_uri", it) }
                volume?.let { putExtra("volume", it) }
                putExtra("vibration_enabled", vibrationEnabled)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("Alarm Ringing")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setOngoing(true)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            notificationManager.notify(notificationId, notification)
        }

        /**
         * Creates (or updates) the alarm notification channel with the given ringtone.
         */
        fun createAlarmNotificationChannel(context: Context, ringtoneUri: Uri?) {
            val name = "Alarm notifications"
            val descriptionText = "Notifications for triggered alarms"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(ALARM_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setBypassDnd(true)
                enableVibration(false) // Vibration handled by AlarmActivity
                setShowBadge(false)
                val soundUri = ringtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
