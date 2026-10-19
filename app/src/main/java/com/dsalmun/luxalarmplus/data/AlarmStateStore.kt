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

/**
 * Persists the currently-ringing alarm's reconstruction data so that [com.dsalmun.luxalarmplus.AlarmActivity]
 * can be rebuilt after being killed (swipe-from-recents, or system kill).
 *
 * Written when the alarm starts ringing, cleared when it is dismissed.
 * Read by [com.dsalmun.luxalarmplus.MainActivity] when it detects the alarm
 * should be ringing but AlarmActivity is not on screen.
 *
 * The data lives in device-protected storage (see [DeviceProtectedAlarmStore])
 * so it is also readable while the device is still locked, which is exactly
 * when an alarm may fire. This class is a thin adapter on top of that store,
 * so there is a single place where the ringing state is kept.
 */
class AlarmStateStore(context: Context) {
    private val store = DeviceProtectedAlarmStore(context)

    fun save(state: RingingAlarmState) {
        store.saveRingingState(state)
    }

    fun load(): RingingAlarmState? {
        return store.loadRingingState()
    }

    fun clear() {
        store.clearRingingState()
    }
}

data class RingingAlarmState(
    val alarmId: Int,
    val ringtoneUri: String?,
    val volume: Float?,
    val vibrationEnabled: Boolean,
)
