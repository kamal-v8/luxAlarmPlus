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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The schedule math that both the Room path and the device-protected
 * (direct boot) path share.
 */
class AlarmScheduleCalculatorTest {
    private fun todayAt(hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun todayDayOfWeek(): Int = Calendar.getInstance()[Calendar.DAY_OF_WEEK]

    private fun otherDayOfWeek(): Int = todayDayOfWeek() % 7 + 1

    @Test
    fun calculateNextTrigger_oneShotLaterToday_triggersToday() {
        val now = todayAt(10, 0)

        val trigger = AlarmScheduleCalculator.calculateNextTrigger(12, 30, emptySet(), now)

        assertEquals(todayAt(12, 30), trigger)
    }

    @Test
    fun calculateNextTrigger_oneShotAlreadyPassed_triggersTomorrow() {
        val now = todayAt(10, 0)

        val trigger = AlarmScheduleCalculator.calculateNextTrigger(9, 30, emptySet(), now)

        val tomorrow =
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        assertEquals(tomorrow, trigger)
    }

    @Test
    fun calculateNextTrigger_repeatingToday_triggersToday() {
        val now = todayAt(10, 0)

        val trigger =
            AlarmScheduleCalculator.calculateNextTrigger(23, 45, setOf(todayDayOfWeek()), now)

        assertEquals(todayAt(23, 45), trigger)
    }

    @Test
    fun calculateNextTrigger_repeatingDayAlreadyPassed_triggersNextWeek() {
        val now = todayAt(23, 0)

        val trigger =
            AlarmScheduleCalculator.calculateNextTrigger(7, 0, setOf(todayDayOfWeek()), now)

        val calendar = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(todayDayOfWeek(), calendar[Calendar.DAY_OF_WEEK])
        assertEquals(7, calendar[Calendar.HOUR_OF_DAY])
        assertEquals(0, calendar[Calendar.MINUTE])
    }

    @Test
    fun findMissedAlarms_oneShotThatPassedToday_isMissed() {
        val now = todayAt(10, 0)
        val alarm = AlarmItem(id = 1, hour = 9, minute = 0)

        val missed = AlarmScheduleCalculator.findMissedAlarms(listOf(alarm), now)

        assertEquals(listOf(alarm), missed)
    }

    @Test
    fun findMissedAlarms_oneShotStillAhead_isNotMissed() {
        val now = todayAt(10, 0)
        val alarm = AlarmItem(id = 1, hour = 11, minute = 0)

        val missed = AlarmScheduleCalculator.findMissedAlarms(listOf(alarm), now)

        assertTrue(missed.isEmpty())
    }

    @Test
    fun findMissedAlarms_repeatingAlarmWithinTheWindow_isMissed() {
        val now = todayAt(10, 0)
        val alarm =
            AlarmItem(id = 2, hour = 9, minute = 30, repeatDays = setOf(todayDayOfWeek()))

        val missed = AlarmScheduleCalculator.findMissedAlarms(listOf(alarm), now)

        assertEquals(listOf(alarm), missed)
    }

    @Test
    fun findMissedAlarms_repeatingAlarmOutsideTheWindow_isNotMissed() {
        val now = todayAt(12, 0)
        val alarm =
            AlarmItem(id = 3, hour = 9, minute = 0, repeatDays = setOf(todayDayOfWeek()))

        val missed = AlarmScheduleCalculator.findMissedAlarms(listOf(alarm), now)

        assertTrue(missed.isEmpty())
    }

    @Test
    fun findMissedAlarms_repeatingAlarmOnAnotherDay_isNotMissed() {
        val now = todayAt(10, 0)
        val alarm = AlarmItem(id = 4, hour = 9, minute = 0, repeatDays = setOf(otherDayOfWeek()))

        val missed = AlarmScheduleCalculator.findMissedAlarms(listOf(alarm), now)

        assertTrue(missed.isEmpty())
    }

    @Test
    fun nextTriggerSlot_picksTheEarliestAlarm() {
        val now = todayAt(10, 0)
        val early = AlarmItem(id = 1, hour = 12, minute = 0)
        val late = AlarmItem(id = 2, hour = 18, minute = 0)

        val slot = AlarmScheduleCalculator.nextTriggerSlot(listOf(late, early), now)

        assertEquals(todayAt(12, 0), slot.triggerAtMillis)
        assertEquals(listOf(early), slot.alarms)
    }

    @Test
    fun nextTriggerSlot_groupsAlarmsThatRingTogether() {
        val now = todayAt(10, 0)
        val first = AlarmItem(id = 7, hour = 12, minute = 0)
        val second = AlarmItem(id = 3, hour = 12, minute = 0)
        val other = AlarmItem(id = 5, hour = 18, minute = 0)

        val slot = AlarmScheduleCalculator.nextTriggerSlot(listOf(first, other, second), now)

        assertEquals(todayAt(12, 0), slot.triggerAtMillis)
        assertEquals(listOf(second, first), slot.alarms)
    }

    @Test
    fun nextTriggerSlot_withoutAlarms_isEmpty() {
        val slot = AlarmScheduleCalculator.nextTriggerSlot(emptyList(), System.currentTimeMillis())

        assertTrue(slot.alarms.isEmpty())
    }
}
