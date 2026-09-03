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

import kotlinx.coroutines.flow.Flow

interface IAlarmRepository {
    fun getAllAlarms(): Flow<List<AlarmItem>>

    suspend fun addAlarm(hour: Int, minute: Int): Boolean

    suspend fun toggleAlarm(alarmId: Int, isActive: Boolean): Boolean

    suspend fun updateAlarmTime(alarmId: Int, hour: Int, minute: Int): Boolean

    suspend fun deleteAlarm(alarmId: Int)

    suspend fun setRepeatDays(alarmId: Int, repeatDays: Set<Int>)

    suspend fun setAlarmRingtone(alarmId: Int, ringtoneUri: String?)

    suspend fun setAlarmVolume(alarmId: Int, volume: Float?)

    suspend fun setAlarmVibration(alarmId: Int, enabled: Boolean)

    suspend fun scheduleNextAlarm(): Boolean

    /**
     * Re-schedules alarms after a reboot/power-off.
     *
     * All [AlarmManager] alarms are cleared on reboot, so this must be called
     * from [com.dsalmun.luxalarmplus.BootReceiver]. Unlike [scheduleNextAlarm],
     * it detects alarms that should have fired while the device was off and
     * re-fires them [REBOOT_GRACE_DELAY_MS] after boot instead of silently
     * pushing one-shots to tomorrow.
     *
     * Battery: schedules a single one-shot `setAlarmClock`, no polling.
     */
    suspend fun rescheduleAfterBoot(): Boolean

    fun canScheduleExactAlarms(): Boolean

    companion object {
        /** Delay after boot before a missed alarm rings, lets system settle. */
        const val REBOOT_GRACE_DELAY_MS = 2 * 60 * 1000L

        /** Repeating alarms missed longer ago than this just schedule next. */
        const val MISSED_WINDOW_MS = 60 * 60 * 1000L
    }

    fun isAlarmRinging(): Boolean

    fun setRingingAlarm(): Boolean

    fun clearRingingAlarm()

    fun saveRingingAlarmState(state: RingingAlarmState)

    fun loadRingingAlarmState(): RingingAlarmState?

    fun clearRingingAlarmState()

    suspend fun deactivateOneShotAlarms(ids: List<Int>)

    suspend fun cancelV1Alarms()
}
