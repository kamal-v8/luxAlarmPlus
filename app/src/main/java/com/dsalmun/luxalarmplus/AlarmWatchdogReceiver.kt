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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Watchdog that re-fires the alarm if the app process was killed (e.g. user
 * swiped the app from Recents or the system killed it for memory).
 *
 * It is scheduled as a repeating exact alarm every [WATCHDOG_INTERVAL_MS].
 * When it fires, it checks whether the alarm is still ringing. If so, it
 * re-posts the alarm notification (which re-launches [AlarmActivity] via its
 * full-screen intent) and reschedules itself.
 *
 * This survives process death because [AlarmManager] is system-owned.
 */
class AlarmWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AppContainer.repository.isAlarmRinging()) {
            // Alarm was dismissed — cancel the watchdog.
            cancelWatchdog(context)
            return
        }

        val state = AppContainer.repository.loadRingingAlarmState()
        if (state == null) {
            cancelWatchdog(context)
            return
        }

        Log.w(TAG, "Watchdog fired — alarm should be ringing, re-posting notification")

        // Re-post the notification. The full-screen intent will re-launch
        // AlarmActivity if it's not already on screen.
        AlarmReceiver.postAlarmNotification(
            context = context,
            alarmId = state.alarmId,
            ringtoneUri = state.ringtoneUri,
            volume = state.volume,
            vibrationEnabled = state.vibrationEnabled,
        )

        // Reschedule ourselves for the next interval.
        scheduleWatchdog(context)
    }

    companion object {
        private const val TAG = "AlarmWatchdog"
        private const val WATCHDOG_REQUEST_CODE = 9999
        private const val WATCHDOG_INTERVAL_MS = 5_000L // 5 seconds

        /**
         * Schedules the watchdog to fire after [WATCHDOG_INTERVAL_MS].
         * Uses [AlarmManager.setExactAndAllowWhileIdle] so it fires even in Doze.
         * Call this when the alarm starts ringing.
         */
        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        }

        /**
         * Cancels the watchdog. Call this when the alarm is dismissed.
         */
        fun cancelWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}
