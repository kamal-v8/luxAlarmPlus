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

import java.util.Calendar

/**
 * The schedule math shared by every scheduling path of [AlarmRepository].
 *
 * The repository can run either against the Room database (user unlocked) or
 * against the device-protected snapshot (still locked). Both paths have to
 * produce exactly the same schedule, so the trigger/missed-alarm logic lives
 * here and is used by both.
 *
 * The functions are pure: the current time is always passed in, which also
 * makes them easy to test.
 */
object AlarmScheduleCalculator {
    /** The next moment [alarm] should ring, in epoch millis. */
    fun calculateNextTrigger(
        hour: Int,
        minute: Int,
        repeatDays: Set<Int>,
        nowMillis: Long,
    ): Long {
        val now = calendarAt(nowMillis)
        val alarmTime =
            calendarAt(nowMillis).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

        if (repeatDays.isEmpty()) {
            if (alarmTime.before(now)) {
                alarmTime.add(Calendar.DAY_OF_MONTH, 1)
            }
            return alarmTime.timeInMillis
        }

        for (i in 0 until 7) {
            val potentialNextDay = calendarAt(nowMillis).apply { add(Calendar.DAY_OF_MONTH, i) }
            val dayOfWeek = potentialNextDay[Calendar.DAY_OF_WEEK]

            if (dayOfWeek in repeatDays) {
                val triggerTime =
                    calendarAt(nowMillis).apply {
                        time = potentialNextDay.time
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                if (triggerTime.after(now)) {
                    return triggerTime.timeInMillis
                }
            }
        }

        var firstDayOfWeek = 8
        for (day in repeatDays) {
            if (day < firstDayOfWeek) {
                firstDayOfWeek = day
            }
        }

        val nextWeekAlarm =
            calendarAt(nowMillis).apply {
                add(Calendar.WEEK_OF_YEAR, 1)
                set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        return nextWeekAlarm.timeInMillis
    }

    /** Today's trigger time of an alarm, in epoch millis. */
    fun calculateTodayTrigger(hour: Int, minute: Int, nowMillis: Long): Long =
        calendarAt(nowMillis).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Alarms that should have fired while the device was off.
     *
     * One-shot (empty [AlarmItem.repeatDays]) alarms stay active until they
     * fire, so an active one-shot whose time today already passed definitely
     * missed. Repeating alarms stay active after firing, so only treat today
     * as missed when today is a repeat day and the time passed within
     * [IAlarmRepository.MISSED_WINDOW_MS] — avoids re-ringing an alarm that
     * already fired hours before the reboot.
     */
    fun findMissedAlarms(activeAlarms: List<AlarmItem>, nowMillis: Long): List<AlarmItem> {
        val todayDow = calendarAt(nowMillis)[Calendar.DAY_OF_WEEK]
        return activeAlarms.filter { alarm ->
            val todayTrigger = calculateTodayTrigger(alarm.hour, alarm.minute, nowMillis)
            if (todayTrigger > nowMillis) return@filter false
            if (alarm.repeatDays.isEmpty()) {
                true
            } else {
                todayDow in alarm.repeatDays &&
                    (nowMillis - todayTrigger) <= IAlarmRepository.MISSED_WINDOW_MS
            }
        }
    }

    /**
     * The alarms that ring next and the moment they ring at.
     *
     * Alarms that share a trigger time ring together, ordered by id.
     */
    fun nextTriggerSlot(activeAlarms: List<AlarmItem>, nowMillis: Long): TriggerSlot {
        if (activeAlarms.isEmpty()) return TriggerSlot(0L, emptyList())
        val triggers =
            activeAlarms.map { alarm ->
                alarm to calculateNextTrigger(alarm.hour, alarm.minute, alarm.repeatDays, nowMillis)
            }
        val minTriggerTime = triggers.minOf { it.second }
        val nextAlarms =
            triggers.filter { it.second == minTriggerTime }.sortedBy { it.first.id }.map { it.first }
        return TriggerSlot(minTriggerTime, nextAlarms)
    }

    private fun calendarAt(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    /** The next alarm slot: when it rings and which alarms ring together. */
    data class TriggerSlot(val triggerAtMillis: Long, val alarms: List<AlarmItem>)
}
