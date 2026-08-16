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
package com.dsalmun.luxalarmplus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists the currently-ringing alarm's reconstruction data so that [com.dsalmun.luxalarmplus.AlarmActivity]
 * can be rebuilt after being killed (swipe-from-recents, or system kill).
 *
 * Written when the alarm starts ringing, cleared when it is dismissed.
 * Read by [com.dsalmun.luxalarmplus.MainActivity] when it detects the alarm
 * should be ringing but AlarmActivity is not on screen.
 */
class AlarmStateStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(state: RingingAlarmState) {
        prefs.edit {
            putInt(KEY_ALARM_ID, state.alarmId)
            putString(KEY_RINGTONE_URI, state.ringtoneUri)
            if (state.volume != null) putFloat(KEY_VOLUME, state.volume) else remove(KEY_VOLUME)
            putBoolean(KEY_VIBRATION_ENABLED, state.vibrationEnabled)
        }
    }

    fun load(): RingingAlarmState? {
        if (!prefs.contains(KEY_ALARM_ID)) return null
        val alarmId = prefs.getInt(KEY_ALARM_ID, -1)
        if (alarmId < 0) return null
        val ringtoneUri = prefs.getString(KEY_RINGTONE_URI, null)
        val volume = if (prefs.contains(KEY_VOLUME)) prefs.getFloat(KEY_VOLUME, 1f) else null
        val vibrationEnabled = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        return RingingAlarmState(alarmId, ringtoneUri, volume, vibrationEnabled)
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val PREFS_NAME = "alarm_ringing_state"
        private const val KEY_ALARM_ID = "alarm_id"
        private const val KEY_RINGTONE_URI = "ringtone_uri"
        private const val KEY_VOLUME = "volume"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    }
}

data class RingingAlarmState(
    val alarmId: Int,
    val ringtoneUri: String?,
    val volume: Float?,
    val vibrationEnabled: Boolean,
)
